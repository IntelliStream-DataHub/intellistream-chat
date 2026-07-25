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

package ai.intellistream.chat.slash;

import ai.intellistream.chat.repository.ReminderRepository;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.web.dto.MessageDto;
import ai.intellistream.chat.web.dto.MessageEvent;
import ai.intellistream.chat.service.MarkdownRenderer;
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

    @Scheduled(fixedDelayString = "${intellistream.reminders.poll-ms:30000}")
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
            Long id = r.getId();
            try {
                var posted = self.fireOne(id, now);
                if (posted != null) {
                    // Broadcast AFTER fireOne's REQUIRES_NEW tx has committed (it returns only on a
                    // clean commit). Broadcasting inside the tx (as before) would show clients a
                    // message that a subsequent commit failure then discarded, and the row would be
                    // force-marked fired so it's lost (N30).
                    broker.convertAndSend("/topic/channels/" + posted.channelId(),
                            MessageEvent.created(posted.dto()));
                    fired++;
                }
            } catch (RuntimeException e) {
                // fireOne's REQUIRES_NEW tx rolled back cleanly (the exception propagated
                // instead of being swallowed-then-committed, which used to throw
                // UnexpectedRollbackException and abort the WHOLE batch). Flag the bad row in
                // a separate tx so it isn't re-selected forever, and carry on with the rest.
                log.warn("Reminder {} failed to fire and will be skipped", id, e);
                try {
                    self.markFiredInNewTx(id, now);
                } catch (RuntimeException markEx) {
                    log.warn("Also failed to mark reminder {} fired; will retry next cycle", id, markEx);
                }
            }
        }
        return fired;
    }

    /**
     * Fire a single reminder in a fresh transaction. Returns {@code true} when the message was
     * posted. On failure it THROWS (its own {@code REQUIRES_NEW} tx rolls back cleanly) — the
     * caller in {@link #runOnce} catches it per-row. It must not swallow-then-return: the inner
     * {@code messageService.post} marks this tx rollback-only on failure, so returning normally
     * would make the commit throw {@code UnexpectedRollbackException} and abort the batch.
     */
    /** Posted-message payload the caller broadcasts after this tx commits (N30). */
    public record FiredMessage(Long channelId, MessageDto dto) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FiredMessage fireOne(Long reminderId, Instant now) {
        var r = repo.findById(reminderId).orElse(null);
        if (r == null || r.getFiredAt() != null) return null;
        var saved = messageService.post(r.getChannel(), r.getCreator(), r.getBody());
        r.markFired(now);
        repo.save(r);
        // Build the broadcast payload here (inside the tx, where the associations are loaded), but
        // let runOnce send it after this tx commits. Mentions list is left empty — the body's
        // @-mention (if any) is parsed by the renderer; the WS-side refresh path bumps the badge.
        var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()),
                List.of(), List.of(), 0L, List.of());
        return new FiredMessage(r.getChannel().getId(), dto);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFiredInNewTx(Long reminderId, Instant now) {
        repo.findById(reminderId).ifPresent(r -> {
            r.markFired(now);
            repo.save(r);
        });
    }
}
