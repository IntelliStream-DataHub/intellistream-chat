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

package com.example.chat.slash;

import com.example.chat.repository.ReminderRepository;
import com.example.chat.service.MessageService;
import com.example.chat.web.dto.MessageDto;
import com.example.chat.web.dto.MessageEvent;
import com.example.chat.service.MarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    public ReminderScheduler(ReminderRepository repo,
                             MessageService messageService,
                             MarkdownRenderer markdown,
                             SimpMessagingTemplate broker) {
        this.repo = repo;
        this.messageService = messageService;
        this.markdown = markdown;
        this.broker = broker;
    }

    @Scheduled(fixedDelayString = "${chat.reminders.poll-ms:30000}")
    public int fireDue() {
        return runOnce(Instant.now());
    }

    /** Test-visible single-pass trigger so specs don't have to wait on the scheduler thread. */
    @Transactional
    public int runOnce(Instant now) {
        var due = repo.findDue(now);
        if (due.isEmpty()) return 0;
        int fired = 0;
        for (var r : due) {
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
                fired++;
            } catch (RuntimeException e) {
                // One bad reminder shouldn't stop the rest of the batch; mark it fired so we
                // don't retry forever and log the underlying issue for the operator.
                log.warn("Reminder {} failed to fire and will be skipped", r.getId(), e);
                r.markFired(now);
                repo.save(r);
            }
        }
        return fired;
    }
}
