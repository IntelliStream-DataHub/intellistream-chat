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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.domain.AdminAudit;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.security.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Administrative removal and restoration of messages.
 *
 * <p>Removal here is a <b>soft</b> delete: {@code messages.deleted_at} is stamped, the row stays,
 * and every read path filters it out (see {@link MessageRepository} — the filter lives in the
 * queries, not in the callers). The scheduled {@link RetentionPurgeScheduler} deletes the rows for
 * real once the retention window has passed. Between those two points the action is reversible,
 * which is the whole point: "clear everything this account ever wrote" is pressed under pressure,
 * often against a shared or compromised account, and sometimes against the wrong one.
 *
 * <h2>Why bulk statements and not entities</h2>
 *
 * A prolific account can hold tens of thousands of messages. Loading them as entities to call
 * {@code Message.softDelete} on each would materialise the lot, put every one in the persistence
 * context, and emit an UPDATE per row. The bulk path instead pages ids and issues one UPDATE per
 * page. The cost of bypassing Hibernate is that the persistence context is not aware of the change
 * — hence {@code clearAutomatically} on the modifying queries, and hence this class never mixes a
 * bulk update with entity reads of the same rows in one transaction.
 *
 * <h2>A page at a time, in its own transaction</h2>
 *
 * Each page commits on its own ({@link Propagation#REQUIRES_NEW}) rather than the whole purge
 * being one transaction. A single 40,000-row UPDATE holds row locks against the live send path for
 * as long as it takes and gives the search index nothing to act on until the very end. The trade
 * is that a failure midway leaves a <em>partial</em> removal — which is why the count that reaches
 * the audit trail is the count actually committed, not the count intended. A partial removal is
 * also trivially resumable: run it again, the {@code deleted_at is null} predicate makes it
 * idempotent.
 *
 * <p>Because the pages use {@code REQUIRES_NEW}, the caller must not already hold locks on these
 * rows in an enclosing transaction — a controller method that has updated the same messages and
 * not yet committed would deadlock against its own suspended transaction.
 *
 * <h2>Search</h2>
 *
 * Lucene is updated after each page commits, never before: the index must not describe a state the
 * database might roll back. Search is immediate — {@code MessageIndexService} refreshes its
 * searcher at the start of every query, so a removed message stops being findable as soon as the
 * page it was in commits, without waiting for the batched index commit.
 *
 * <p>The index write is the one step that can fail without failing the operation. That is
 * deliberate: a message that is flagged in the database but lingering in the index is wrong for at
 * most one reconcile interval (the CLEAN-3 sweep drops docs whose id is no longer a live message —
 * see {@code MessageRepository.findAllMessageIds}), whereas rolling back a committed removal
 * because Lucene hiccuped would leave the admin's action half-applied with no record of why.
 *
 * <h2>Authorization</h2>
 *
 * Not decided here. The admin filter chain gates who may reach these methods; this class records
 * <em>who</em> acted so the decision is accountable afterwards.
 *
 * <h2>Racing the sender</h2>
 *
 * A bulk removal reads the {@code messages} table, and the write-behind batcher holds accepted
 * messages outside it for a few milliseconds ({@code MessageWriteBehind}). A message the target
 * posted while the purge was running can therefore land after the purge has passed its id and
 * survive as a live message. There is no lock that closes this without putting the purge on the
 * send path, and it does not need one: the operational answer is to suspend the account first and
 * clear its messages second, and re-running the removal is idempotent and cheap.
 */
@Service
public class MessageModerationService {

    private static final Logger log = LoggerFactory.getLogger(MessageModerationService.class);

    /**
     * Ids per page. Small enough that one UPDATE's lock footprint is unremarkable next to normal
     * traffic, large enough that a 50,000-message account is a hundred round trips rather than
     * fifty thousand.
     */
    static final int PAGE_SIZE = 500;

    private final MessageRepository messages;
    private final MessageIndexService messageIndex;
    private final AuditService audit;
    /** Self-proxy: a plain {@code this.page(...)} call would skip the proxy, and with it the
     *  {@code REQUIRES_NEW} that makes each page its own transaction. */
    private final MessageModerationService self;

    public MessageModerationService(MessageRepository messages,
                                    MessageIndexService messageIndex,
                                    AuditService audit,
                                    @Lazy MessageModerationService self) {
        this.messages = messages;
        this.messageIndex = messageIndex;
        this.audit = audit;
        this.self = self;
    }

    /** A message that was removed or restored, and the channel it lives in — enough for a caller
     *  to tell that channel's subscribers about it. */
    public record MessageRef(Long messageId, Long channelId) {}

    /**
     * Remove every message written by {@code target}. The "clear all their messages" action.
     *
     * <p>Reversible via {@link #restoreAllByAuthor} until the retention purge reaps the rows.
     * Messages already removed are left alone, so re-running this neither double-counts nor
     * resets the retention clock on the earlier batch.
     *
     * @return how many messages this call actually flagged
     */
    public int deleteAllByAuthor(User admin, User target) {
        Objects.requireNonNull(target, "target");
        var at = Instant.now();
        long cursor = 0;
        int removed = 0;
        try {
            while (true) {
                var page = messages.findLiveIdsByAuthorAfter(target, cursor, PageRequest.of(0, PAGE_SIZE));
                if (page.isEmpty()) {
                    break;
                }
                // Strictly increasing cursor — this is what guarantees the loop terminates even
                // though the predicate it pages over is the same column the loop is writing.
                cursor = page.getLast();
                removed += self.softDeletePage(page, at, admin);
                // After the page's commit. Everything in the page is now removed, whether this
                // call flagged it or an earlier one did, so dropping all of it from the index is
                // correct and is also a free repair of any index write lost earlier.
                dropFromIndex(page);
            }
        } finally {
            // In the finally block so a failure partway still leaves a record of what was done.
            audit.recordOnUser(admin, AdminAudit.PURGE_MESSAGES, target,
                    "soft-deleted " + removed + " message(s)");
        }
        log.info("Moderation: {} removed {} message(s) by {}",
                admin != null ? admin.getUsername() : "system", removed, target.getUsername());
        return removed;
    }

    /**
     * Undo {@link #deleteAllByAuthor}: bring back every message by {@code target} that is still
     * within the retention window. Rows the purge has already reaped are gone for good — that is
     * what the window is for, and the audit trail records both halves.
     *
     * @return how many messages this call actually restored
     */
    public int restoreAllByAuthor(User admin, User target) {
        Objects.requireNonNull(target, "target");
        long cursor = 0;
        int restored = 0;
        try {
            while (true) {
                var page = messages.findDeletedIdsByAuthorAfter(target, cursor, PageRequest.of(0, PAGE_SIZE));
                if (page.isEmpty()) {
                    break;
                }
                cursor = page.getLast();
                restored += self.restorePage(page);
                reindex(page);
            }
        } finally {
            audit.recordOnUser(admin, AdminAudit.RESTORE_MESSAGES, target,
                    "restored " + restored + " message(s)");
        }
        log.info("Moderation: {} restored {} message(s) by {}",
                admin != null ? admin.getUsername() : "system", restored, target.getUsername());
        return restored;
    }

    /**
     * Remove a single message. Unlike {@link ai.intellistream.chat.service.MessageService#delete},
     * which is the author's/channel-admin's irreversible delete, this one is reversible and
     * leaves an audit row naming the moderator.
     *
     * @throws ResourceNotFoundException if no such message exists
     */
    public MessageRef deleteOne(User admin, Long messageId) {
        var ref = self.softDeleteSingle(admin, messageId);
        dropFromIndex(List.of(messageId));
        return ref;
    }

    /** Undo {@link #deleteOne}. Restoring a message that was never removed is a no-op apart from
     *  the audit row. */
    public MessageRef restoreOne(User admin, Long messageId) {
        var ref = self.restoreSingle(admin, messageId);
        reindex(List.of(messageId));
        return ref;
    }

    // ----------------------------------------------------------------- one page ----
    // Public only because Spring's proxy-based transaction advice ignores non-public methods —
    // a package-private @Transactional here would silently join the caller's transaction (or run
    // without one) and the per-page commit boundary this class is built on would not exist.
    // Call the methods above, not these.

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int softDeletePage(List<Long> ids, Instant at, User by) {
        return messages.softDeleteByIds(ids, at, by);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int restorePage(List<Long> ids) {
        return messages.restoreByIds(ids);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MessageRef softDeleteSingle(User admin, Long messageId) {
        var message = load(messageId);
        boolean wasLive = !message.isDeleted();
        if (wasLive) {
            message.softDelete(admin);
        }
        audit.record(admin, AdminAudit.DELETE_MESSAGE, message.getAuthor(), ref(messageId),
                wasLive ? "soft-deleted 1 message" : "already removed; no change");
        return new MessageRef(messageId, message.getChannel().getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MessageRef restoreSingle(User admin, Long messageId) {
        var message = load(messageId);
        boolean wasDeleted = message.isDeleted();
        if (wasDeleted) {
            message.restore();
        }
        audit.record(admin, AdminAudit.RESTORE_MESSAGES, message.getAuthor(), ref(messageId),
                wasDeleted ? "restored 1 message" : "was not removed; no change");
        return new MessageRef(messageId, message.getChannel().getId());
    }

    private Message load(Long messageId) {
        // Including deleted: a restore has to be able to find what it is restoring, and a
        // double-delete should read as "already removed" rather than "no such message".
        return messages.findByIdIncludingDeleted(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));
    }

    private static String ref(Long messageId) {
        return "message:" + messageId;
    }

    // -------------------------------------------------------------------- index ----

    private void dropFromIndex(List<Long> ids) {
        try {
            messageIndex.deleteAll(ids);
        } catch (RuntimeException e) {
            // See the class javadoc: the database is the source of truth and the reconcile sweep
            // will drop these docs. Loud, because until it runs the messages are still findable.
            log.warn("Removed {} message(s) but failed to drop them from the search index; "
                    + "they stay searchable until the next reconcile", ids.size(), e);
        }
    }

    private void reindex(List<Long> ids) {
        try {
            var rows = messages.findIndexRowsByIds(ids);
            // With their attachment filenames: a restored message has to come back findable by the
            // whole of what it said, files included, or the restore is only half a restore.
            var docs = MessageIndexService.IndexedMessage.fromRows(rows,
                    MessageIndexService.groupFilenames(messages.findIndexFilenamesByIds(ids)));
            // updateDocument semantics, not addDocument: a restore of something the index never
            // dropped must not leave two documents with the same id.
            messageIndex.reindex(docs);
        } catch (RuntimeException e) {
            log.warn("Restored {} message(s) but failed to re-index them; they stay unsearchable "
                    + "until the next reconcile", ids.size(), e);
        }
    }
}
