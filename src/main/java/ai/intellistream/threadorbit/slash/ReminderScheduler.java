/*
 * Copyright 2026 Olav Gjerde
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.intellistream.threadorbit.slash;

import ai.intellistream.threadorbit.repository.ReminderRepository;
import ai.intellistream.threadorbit.service.MessageService;
import ai.intellistream.threadorbit.web.dto.MessageDto;
import ai.intellistream.threadorbit.web.dto.MessageEvent;
import ai.intellistream.threadorbit.service.MarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Polls {@code reminders} every 30 seconds; for each row whose {@code fireAt} has passed and
 * which hasn't been delivered yet, posts a message into the originating channel and marks
 * the row {@code firedAt = now()}. Posting goes through {@link MessageService#post} so
 * mention rows and the search index get the same treatment as a normal message — and so the
 * @-mention notification path lights up for the target user.
 *
 * <p>30-second cadence balances "feels prompt" against "doesn't hammer Postgres on idle
 * deployments". For sub-minute precision we'd swap this for a Quartz-style trigger or
 * compute the delay until the next pending row; until reminders feel sluggish in practice,
 * fixed-rate polling stays cheaper to reason about.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderRepository repo;
    private final MessageService messageService;
    private final MarkdownRenderer markdown;
    private final SimpMessagingTemplate broker;

    /**
     * Self-reference resolved through the Spring proxy so {@code REQUIRES_NEW} propagation
     * actually fires on {@code fireOne(...)} / {@code markFiredInNewTx(...)}. A direct
     * {@code this.fireOne(...)} call bypasses the proxy and runs in whatever tx the caller
     * is in (here: none, since {@link #runOnce} is no longer transactional). {@code @Lazy}
     * breaks the constructor cycle.
     */
    private final ReminderScheduler self;

    public ReminderScheduler(ReminderRepository repo,
                             MessageService messageService,
                             MarkdownRenderer markdown,
                             SimpMessagingTemplate broker,
                             @Lazy @Autowired ReminderScheduler self) {
        this.repo = repo;
        this.messageService = messageService;
        this.markdown = markdown;
        this.broker = broker;
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${threadorbit.reminders.poll-ms:30000}")
    public int fireDue() {
        return runOnce(Instant.now());
    }

    /**
     * Test-visible single-pass trigger so specs don't have to wait on the scheduler thread.
     * Intentionally NOT {@code @Transactional}: each reminder runs in its own
     * {@code REQUIRES_NEW} transaction via {@link #fireOne}. Sharing one tx across the
     * whole batch lets a single failure (e.g. constraint violation, slow Hibernate flush)
     * mark the EntityManager rollback-only and break every subsequent iteration's
     * {@code repo.save(r)} with {@code TransactionRequiredException} /
     * {@code UnexpectedRollbackException}.
     */
    public int runOnce(Instant now) {
        var due = repo.findDue(now);
        if (due.isEmpty()) return 0;
        int fired = 0;
        for (var r : due) {
            if (self.fireOne(r.getId(), now)) fired++;
        }
        return fired;
    }

    /**
     * Fire a single reminder in a fresh transaction. Returns {@code true} when the message
     * was posted; on failure, marks the reminder fired anyway (in another fresh tx) so a
     * single bad row doesn't block the schedule forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fireOne(Long reminderId, Instant now) {
        var r = repo.findById(reminderId).orElse(null);
        if (r == null || r.getFiredAt() != null) return false;
        try {
            var saved = messageService.post(r.getChannel(), r.getCreator(), r.getBody());
            r.markFired(now);
            repo.save(r);
            // Same shape as a normal channel post — clients render it via the existing
            // /topic/channels/{id} subscription. Mentions list is left empty here because
            // the body's @-mention (if any) is parsed by the renderer; the WS-side caller
            // refresh path handles the badge bump.
            var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()),
                    List.of(), List.of(), 0L, List.of());
            broker.convertAndSend("/topic/channels/" + r.getChannel().getId(),
                    MessageEvent.created(dto));
            return true;
        } catch (RuntimeException e) {
            log.warn("Reminder {} failed to fire and will be skipped", reminderId, e);
            // The current tx is rollback-only after the failed post; flag the row in a
            // separate tx so the next runOnce() doesn't pick it up again forever.
            self.markFiredInNewTx(reminderId, now);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFiredInNewTx(Long reminderId, Instant now) {
        repo.findById(reminderId).ifPresent(r -> {
            r.markFired(now);
            repo.save(r);
        });
    }
}
