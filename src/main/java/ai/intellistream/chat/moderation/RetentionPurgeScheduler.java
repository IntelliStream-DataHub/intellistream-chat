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
import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.service.AttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Hard-deletes messages that have been soft-deleted for longer than the retention window.
 *
 * <p>Soft delete makes moderation reversible; this is what stops it also making it permanent
 * storage. Without a reaper, every message an admin ever removed stays in the table forever —
 * still occupying the index the live queries scan past, still holding its attachments on disk, and
 * still there to be recovered by anyone who reaches the database, which is not what a user who
 * asked for their words to be deleted was promised.
 *
 * <h2>Configuration</h2>
 * <table><caption>properties</caption>
 * <tr><td>{@code ichat.moderation.retention-days}</td>
 *     <td>How long a removed message stays recoverable. Default <b>30</b> — long enough for a
 *     wrong ban to be noticed and appealed over a holiday, short enough that "deleted" means
 *     something. Set to {@code 0} (or negative) to keep removed messages indefinitely, which
 *     disables the purge as surely as the flag below.</td></tr>
 * <tr><td>{@code ichat.moderation.purge-enabled}</td>
 *     <td>Master switch, default {@code true}. Set {@code false} on every node but one when
 *     running more than one — see below.</td></tr>
 * <tr><td>{@code ichat.moderation.purge-interval-ms}</td>
 *     <td>How often to sweep, default hourly. The window is measured in days; sweeping more often
 *     than that buys nothing.</td></tr>
 * <tr><td>{@code ichat.moderation.purge-batch-size}</td>
 *     <td>Rows per transaction, default 500.</td></tr>
 * <tr><td>{@code ichat.moderation.purge-max-batches}</td>
 *     <td>Batches per run, default 200 (100k rows). A backlog larger than this is carried to the
 *     next run rather than sat through in one.</td></tr>
 * </table>
 *
 * <h2>Bounded batches, one transaction each</h2>
 *
 * The first run after enabling retention on an old deployment can face a very large backlog.
 * Deleting it in one statement would hold locks and WAL for minutes and cascade through
 * attachments, reactions and mentions in a single transaction. Instead each batch is selected,
 * deleted and committed on its own, and a run stops after a fixed number of batches — the
 * remainder waits for the next sweep. Nothing is lost by going slowly; the rows are already
 * invisible.
 *
 * <p><b>Single-instance only</b>, like the {@code CleanupTasks} sweeps: {@code @EnableScheduling}
 * runs on every node, so with several nodes these runs would race (harmlessly — the batch delete
 * is idempotent — but they would duplicate audit rows and waste I/O). Disable it on all but one.
 *
 * <h2>What goes with the row</h2>
 *
 * Attachments, reactions, mentions, polls and already-removed replies are removed by the schema's
 * {@code on delete cascade}. Attachment <em>files</em> are not — nothing in the database can delete
 * a file — so this class reaps them itself, and credits their bytes back to whoever uploaded them.
 * The Lucene document was dropped when the message was soft-deleted; the purge deletes it again as
 * cheap insurance, since after this point no reconcile can ever find the message to repair the
 * index from.
 *
 * <p><b>This is where storage is actually reclaimed, and therefore where the quota credit belongs.</b>
 * The admin's "remove these messages" is a soft delete: the row stays, the files stay, not one byte
 * is freed. Crediting the account there would be a lie with a consequence — it would let an account
 * delete and re-upload its way past a quota that nothing had actually released, which is the one
 * failure a quota exists to prevent. The bytes are handed back here, at the moment they stop
 * occupying the volume, and not before. (Leaving the files to the CLEAN-1 orphan sweep, as an
 * earlier draft did, had the same shape of problem: the volume stays full for as long as it takes
 * that sweep to run, and the credit has nowhere honest to happen at all.)
 *
 * <p>One nuance of the cascade: a reply removed <em>later</em> than its parent can be reaped early,
 * riding its parent's deletion before its own window has elapsed. It has been invisible since it
 * was removed and its parent is going regardless, so restoring it alone would produce an orphan;
 * losing a few days of its window is the lesser oddity. The reverse case — a removed parent with
 * live replies under it — is refused outright by the candidate query
 * ({@code MessageRepository.findPurgeableIds}), because cascading there would destroy messages
 * nobody asked to remove.
 */
@Component
public class RetentionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeScheduler.class);

    private final MessageRepository messages;
    private final AttachmentRepository attachments;
    private final MessageIndexService messageIndex;
    private final AuditService audit;
    /** Only for {@code deleteFiles} — it owns the storage root and the "never escape it" check. */
    private final AttachmentService attachmentFiles;
    private final StorageQuotaService quotas;
    /** Self-proxy so each batch's {@code REQUIRES_NEW} actually takes effect — a direct call
     *  would run inside whatever transaction the scheduler thread has, which is none, making the
     *  "one transaction per batch" guarantee accidental rather than real. */
    private final RetentionPurgeScheduler self;

    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatches;

    public RetentionPurgeScheduler(MessageRepository messages,
                                   AttachmentRepository attachments,
                                   MessageIndexService messageIndex,
                                   AuditService audit,
                                   AttachmentService attachmentFiles,
                                   StorageQuotaService quotas,
                                   @Lazy RetentionPurgeScheduler self,
                                   @Value("${ichat.moderation.purge-enabled:true}") boolean enabled,
                                   @Value("${ichat.moderation.retention-days:30}") int retentionDays,
                                   @Value("${ichat.moderation.purge-batch-size:500}") int batchSize,
                                   @Value("${ichat.moderation.purge-max-batches:200}") int maxBatches) {
        this.messages = messages;
        this.attachments = attachments;
        this.messageIndex = messageIndex;
        this.audit = audit;
        this.attachmentFiles = attachmentFiles;
        this.quotas = quotas;
        this.self = self;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    /**
     * Hourly sweep. Not transactional itself — see {@link #purgeBatch}, which is; a transaction
     * open across the whole run would defeat the batching.
     */
    @Scheduled(fixedDelayString = "${ichat.moderation.purge-interval-ms:3600000}",
               initialDelayString = "${ichat.moderation.purge-initial-delay-ms:600000}")
    public void purgeExpired() {
        if (!enabled || retentionDays <= 0) {
            return;
        }
        purgeDeletedBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
    }

    /**
     * Purge everything removed before {@code cutoff}, in batches. Exposed separately from the
     * schedule so an operator-triggered purge or a test can pick its own cutoff.
     *
     * @return how many messages were hard-deleted (cascaded replies and dependents are not counted)
     */
    public int purgeDeletedBefore(Instant cutoff) {
        int purged = 0;
        int batches = 0;
        try {
            while (batches < maxBatches) {
                var batch = messages.findPurgeableIds(cutoff, batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                batches++;
                // Read the attachment rows before the delete removes them. `attachments.message_id`
                // is `on delete cascade`, so once the batch commits the storage key, the size and
                // the uploader are all gone together — the file left on disk names none of them,
                // and there is no later pass that could work out whose bytes these were.
                var doomed = attachments.findByMessageIdsIncludingReplies(batch);
                purged += self.purgeBatch(batch);
                // Everything below runs after the batch's commit: purgeBatch is REQUIRES_NEW, so it
                // has committed by the time it returns. Nothing here may run before it — a file
                // deleted for a row that then failed to delete is a message that renders with a
                // broken attachment forever.
                //
                // The index first. The rows are gone for good now, so an index doc that somehow
                // survived the soft delete has no other way of ever being cleaned up: the
                // reconcile sweep repairs the index from the messages table, and these ids are no
                // longer in it.
                dropFromIndex(batch);
                reapAttachments(doomed);
            }
        } catch (RuntimeException e) {
            // Log and record what was achieved rather than propagating into the scheduler, where
            // an escaping exception would only reach the default error handler and the next run
            // would have no idea a partial purge had happened.
            log.error("Retention purge failed after removing {} message(s); will retry next run",
                    purged, e);
        }
        if (purged == 0) {
            return 0; // The common case. An audit row per idle hour would bury the real ones.
        }
        if (batches >= maxBatches) {
            log.info("Retention purge hit its per-run batch cap ({}); {} message(s) removed, "
                    + "remainder deferred to the next run", maxBatches, purged);
        }
        // actor null: this is the system acting on a policy, not an administrator acting on an
        // account, and attributing it to a person would make the trail lie.
        audit.record(null, AdminAudit.RETENTION_PURGE, null, null,
                "purged " + purged + " message(s) removed before " + cutoff);
        log.info("Retention purge removed {} message(s) soft-deleted before {}", purged, cutoff);
        return purged;
    }

    /**
     * One batch, one transaction. Public for the same reason as the page methods in
     * {@link MessageModerationService}: Spring's transaction advice ignores non-public methods.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeBatch(List<Long> ids) {
        return messages.deleteByIdIn(ids);
    }

    private void dropFromIndex(List<Long> ids) {
        try {
            messageIndex.deleteAll(ids);
        } catch (RuntimeException e) {
            log.warn("Purged {} message(s) but failed to drop them from the search index; those "
                    + "docs are now unreachable by the reconcile sweep and will need a rebuild",
                    ids.size(), e);
        }
    }

    /**
     * Take the files off disk and hand their bytes back to the accounts that uploaded them.
     *
     * <p>Two steps, each guarded, because they fail independently and neither should cost the other.
     * The credit is a database write in its own transaction (the purge's has already committed —
     * that is the point), so a connection blip can lose it while the files are gone. That leaves the
     * account charged for storage it no longer occupies, and {@code UserStorage} offers only an
     * atomic delta, never an absolute set, so nothing here can put the number right afterwards.
     * Hence the ERROR: the drift is small, permanent and invisible unless someone is told, and the
     * only repair is an operator raising the account's quota override. Failing the whole run instead
     * would be worse — the rows are already gone, so a retry would find nothing to credit either.
     */
    private void reapAttachments(List<Attachment> doomed) {
        if (doomed.isEmpty()) {
            return;
        }
        try {
            attachmentFiles.deleteFiles(doomed.stream().map(Attachment::getStorageKey).toList());
        } catch (RuntimeException e) {
            // deleteFiles already swallows per-file IOExceptions; this is the unexpected residue.
            // Caught so it cannot skip the credit below, which is the half nothing else can repair.
            log.warn("Purged {} attachment(s) but failed to remove their files; they are orphans "
                    + "on disk until the CLEAN-1 sweep runs", doomed.size(), e);
        }
        try {
            quotas.releaseAll(AttachmentService.creditsFor(doomed));
        } catch (RuntimeException e) {
            log.error("Purged {} attachment(s) and freed their files, but failed to credit the "
                    + "bytes back; the uploading accounts stay charged for storage they no longer "
                    + "use and there is no reconcile that can fix it. Adjust the affected quota "
                    + "overrides by hand if an account is now stuck.", doomed.size(), e);
        }
    }
}
