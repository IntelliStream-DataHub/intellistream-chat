/*
 * Copyright 2026 IntelliStream AS
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

import ai.intellistream.chat.domain.Reminder;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ReminderRepository;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
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
import java.util.Map;

/**
 * Polls {@code reminders} every 30 seconds; for each row whose {@code fireAt} has passed and which
 * hasn't been delivered yet, delivers it as a <b>direct message</b> and marks the row
 * {@code firedAt = now()}.
 *
 * <p>A DM, not a channel message. Firing into the originating channel meant
 * {@code /remind me in 2h to ask about my salary review} was read out to the whole room two hours
 * later, which is the kind of feature people learn to stop using. A conversation is the delivery
 * mechanism rather than a notification of our own because it already carries everything a reminder
 * needs and none of it is worth reimplementing: it survives the recipient being offline, it badges
 * unread, it is searchable, and it has a permalink.
 *
 * <p>Two shapes:
 * <ul>
 *   <li>{@code /remind me} → the requester's conversation with themself. One member; see
 *       {@link ConversationService#directBetween}.</li>
 *   <li>{@code /remind @bob} → the existing two-person DM between requester and target, with the
 *       body attributing it, because a reminder arriving out of nowhere is a puzzle.</li>
 * </ul>
 *
 * <p>The channel the reminder was set in is still recorded on the row and still named in the
 * delivered body ("set in #general") — useful context, just not an audience.
 *
 * <p>30-second cadence balances "feels prompt" against "doesn't hammer Postgres on idle
 * deployments". For sub-minute precision we'd swap this for a Quartz-style trigger or compute the
 * delay until the next pending row; until reminders feel sluggish in practice, fixed-rate polling
 * stays cheaper to reason about.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderRepository repo;
    private final ConversationService conversations;
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
                             ConversationService conversations,
                             MarkdownRenderer markdown,
                             SimpMessagingTemplate broker,
                             @Lazy @Autowired ReminderScheduler self) {
        this.repo = repo;
        this.conversations = conversations;
        this.markdown = markdown;
        this.broker = broker;
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${ichat.reminders.poll-ms:30000}")
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
                var delivered = self.fireOne(id, now);
                if (delivered != null) {
                    publish(delivered);
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
     * The delivered DM, ready to announce once {@link #fireOne}'s transaction has committed (N30).
     *
     * @param recipientUsername who the reminder is for — the target, or the creator for "me". The
     *        alert goes to exactly this one person: a reminder has an addressee, unlike an ordinary
     *        message where everyone but the author is notified.
     * @param title what the conversation is called from the recipient's side.
     */
    public record FiredReminder(Long conversationId, ConversationMessageDto dto,
                                String recipientUsername, String title) {}

    /**
     * Fire a single reminder in a fresh transaction. Returns the payload to announce, or null when
     * the row went away or was already delivered. On failure it THROWS (its own
     * {@code REQUIRES_NEW} tx rolls back cleanly) — the caller in {@link #runOnce} catches it
     * per-row. It must not swallow-then-return: the inner writes mark this tx rollback-only on
     * failure, so returning normally would make the commit throw
     * {@code UnexpectedRollbackException} and abort the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FiredReminder fireOne(Long reminderId, Instant now) {
        var r = repo.findById(reminderId).orElse(null);
        if (r == null || r.getFiredAt() != null) return null;
        var creator = r.getCreator();
        var recipient = r.getTarget() == null ? creator : r.getTarget();
        // For "me" this is the one-member self conversation; for "@bob" it is the pair's existing
        // DM, reused rather than created, so the reminder lands in the thread they already have.
        var conversation = conversations.directBetween(creator, recipient);
        var saved = conversations.post(conversation, creator, bodyFor(r, creator, recipient));
        r.markFired(now);
        repo.save(r);
        // Built inside the tx, where the associations are loaded; sent by runOnce after it commits.
        var dto = ConversationMessageDto.from(saved, markdown.renderInConversation(saved.getBodyMarkdown()));
        var title = recipient.getId().equals(creator.getId())
                ? ai.intellistream.chat.web.dto.ConversationDto.SELF_TITLE
                : displayName(creator);
        return new FiredReminder(conversation.getId(), dto, recipient.getUsername(), title);
    }

    /**
     * The delivered text. Attribution first when someone else set it — "a reminder" from nobody in
     * particular is a puzzle, and the requester's handle is the answer. The channel is context, not
     * a destination.
     */
    static String bodyFor(Reminder r, User creator, User recipient) {
        var from = recipient.getId().equals(creator.getId())
                ? "⏰ Reminder"
                : "⏰ Reminder from @" + creator.getUsername();
        return from + " (set in #" + r.getChannel().getSlug() + "): " + r.getBody();
    }

    /**
     * Announce a delivered reminder, after its transaction committed. Broadcasting inside the tx
     * would show clients a message that a subsequent commit failure then discarded, while the row
     * was force-marked fired and so lost (N30).
     *
     * <p>Two sends, both on destinations that already exist. The topic reaches the conversation
     * page if the recipient happens to have it open; the user queue is what reaches them anywhere
     * else. {@code ConversationAlertPublisher} does the same job for interactive sends but skips the
     * author, which for a reminder to yourself is the only person there is — so the payload is built
     * here instead, with the same keys the clients already read.
     */
    private void publish(FiredReminder delivered) {
        broker.convertAndSend("/topic/conversations/" + delivered.conversationId(), delivered.dto());
        try {
            broker.convertAndSendToUser(delivered.recipientUsername(), "/queue/conversation-alerts",
                    Map.of(
                            "conversationId", delivered.conversationId(),
                            "type", "DIRECT",
                            "title", delivered.title(),
                            "author", delivered.dto().authorDisplayName() == null
                                    ? delivered.dto().authorUsername()
                                    : delivered.dto().authorDisplayName(),
                            "authorUsername", delivered.dto().authorUsername(),
                            "messageId", delivered.dto().id(),
                            "preview", preview(delivered.dto().bodyMarkdown())));
        } catch (RuntimeException e) {
            // The message is stored and the badge will show on next load; a failed toast is not
            // worth losing the row's fired mark over.
            log.warn("Could not alert {} about a fired reminder", delivered.recipientUsername(), e);
        }
    }

    private static String displayName(User u) {
        return u.getDisplayName() == null || u.getDisplayName().isBlank()
                ? u.getUsername() : u.getDisplayName();
    }

    /** Toast-sized excerpt, same 200-char shape ConversationAlertPublisher uses for a normal DM. */
    private static String preview(String body) {
        if (body == null) return "";
        var oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 199) + "…";
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFiredInNewTx(Long reminderId, Instant now) {
        repo.findById(reminderId).ifPresent(r -> {
            r.markFired(now);
            repo.save(r);
        });
    }
}
