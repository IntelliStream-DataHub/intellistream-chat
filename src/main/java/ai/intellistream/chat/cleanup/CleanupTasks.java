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

package ai.intellistream.chat.cleanup;

import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.ConversationAttachmentRepository;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.AvatarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Scheduled backstop sweeps that reconcile the filesystem and the Lucene index against the
 * database — catching what slips through the write-path crash windows (BUG-9/10/21). All gated by
 * {@link CleanupProperties}: disabled per-node with {@code enabled=false}, and {@code dry-run=true}
 * by default (log what WOULD change, touch nothing) until an operator arms them. See
 * CLEAN-1/2/3 in AUDIT.md. Single-instance only (CLEAN-5).
 */
@Component
public class CleanupTasks {

    private static final Logger log = LoggerFactory.getLogger(CleanupTasks.class);
    private static final int REINDEX_BATCH = 500;

    private final CleanupProperties props;
    private final AttachmentService attachmentService;
    private final AvatarService avatarService;
    private final AttachmentRepository attachmentRepo;
    private final ConversationAttachmentRepository convAttachmentRepo;
    private final UserRepository userRepo;
    private final MessageRepository messageRepo;
    private final ConversationMessageRepository conversationMessageRepo;
    private final MessageIndexService messageIndex;
    private final ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviews;

    public CleanupTasks(CleanupProperties props,
                        AttachmentService attachmentService,
                        AvatarService avatarService,
                        AttachmentRepository attachmentRepo,
                        ConversationAttachmentRepository convAttachmentRepo,
                        UserRepository userRepo,
                        MessageRepository messageRepo,
                        ConversationMessageRepository conversationMessageRepo,
                        MessageIndexService messageIndex,
                        ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviews) {
        this.linkPreviews = linkPreviews;
        this.props = props;
        this.attachmentService = attachmentService;
        this.avatarService = avatarService;
        this.attachmentRepo = attachmentRepo;
        this.convAttachmentRepo = convAttachmentRepo;
        this.userRepo = userRepo;
        this.messageRepo = messageRepo;
        this.conversationMessageRepo = conversationMessageRepo;
        this.messageIndex = messageIndex;
    }

    /** CLEAN-1 + CLEAN-2: delete on-disk attachment/avatar files that no DB row references. */
    @Scheduled(fixedDelayString = "${ichat.cleanup.file-sweep-ms:3600000}",
               initialDelayString = "${ichat.cleanup.initial-delay-ms:300000}")
    public void sweepOrphanFiles() {
        if (!props.isEnabled()) return;
        sweepDir("attachments", attachmentService.storageRoot(), () -> {
            var live = new HashSet<>(attachmentRepo.findAllStorageKeys());
            live.addAll(convAttachmentRepo.findAllStorageKeys());
            return live;
        });
        sweepDir("avatars", avatarService.storageRoot(),
                () -> new HashSet<>(userRepo.findAllAvatarStorageKeys()));
        // Link-preview images: written by a background fetch and referenced by image_key; a crash
        // between the write and the row, or a lost race on the URL's unique constraint, leaves a
        // file no row names.
        sweepDir("link-previews", linkPreviews.storageRoot(),
                () -> new HashSet<>(linkPreviews.liveImageKeys()));
    }

    private void sweepDir(String label, Path root, Supplier<Set<String>> liveKeys) {
        Set<String> live;
        try {
            live = liveKeys.get();
        } catch (RuntimeException e) {
            // Never delete against a partial live set — a failed DB read must not read as "orphan".
            log.warn("[cleanup:{}] skipping — could not load the live key set", label, e);
            return;
        }
        // Extra caution (mirrors datahub's OrphanTenantFolderCleanupTask): if the DB says NOTHING
        // is live, don't sweep the directory — report only, in case the empty is a data glitch.
        boolean liveEmpty = live.isEmpty();
        long graceCutoff = System.currentTimeMillis() - props.getGrace().toMillis();
        int orphans = 0, deleted = 0;
        try (var stream = Files.list(root)) {
            for (var path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) continue;
                var key = path.getFileName().toString();
                if (live.contains(key)) continue;
                if (Files.getLastModifiedTime(path).toMillis() > graceCutoff) continue; // within grace
                orphans++;
                if (props.isDryRun() || liveEmpty) {
                    log.info("[cleanup:{}] [{}] would delete orphan {}",
                            label, liveEmpty ? "empty-live-set, skipped" : "dry-run", key);
                } else {
                    try {
                        Files.deleteIfExists(path);
                        deleted++;
                    } catch (IOException e) {
                        log.warn("[cleanup:{}] failed to delete {}", label, key, e);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[cleanup:{}] failed to list {}", label, root, e);
            return;
        }
        if (orphans > 0) {
            log.info("[cleanup:{}] {} orphan file(s){}", label, orphans,
                    props.isDryRun() || liveEmpty ? " (none deleted)" : ", deleted " + deleted);
        }
    }

    /** CLEAN-3: reconcile the Lucene index with the messages table — index the missing, drop the stale. */
    @Scheduled(fixedDelayString = "${ichat.cleanup.reconcile-ms:3600000}",
               initialDelayString = "${ichat.cleanup.initial-delay-ms:300000}")
    // Deliberately NOT readOnly = true. This method reads nothing but it deletes on what it read,
    // and readOnly is what routes a transaction to the read replica when one is configured
    // (ReadReplicaDataSourceConfig). A replica lags; a message committed a moment ago is in the
    // index and not yet in the replica's copy of `messages`, which reads here as "stale" — and
    // the sweep would delete the search document for a message that exists. The read ordering
    // below (N3) closes the same window for the primary; only a primary read closes it at all.
    @Transactional
    public void reconcileSearchIndex() {
        if (!props.isEnabled()) return;
        // Snapshot the INDEX first, then the DB (N3). A message posted+indexed between the two reads
        // is then absent from indexIds but present in dbIds → classified "missing" → re-indexed
        // (an idempotent no-op), instead of the reverse order which classified it "stale" and
        // DELETED its fresh doc. The stale direction stays correct: a message genuinely removed
        // from the DB is in indexIds but not dbIds → dropped.
        var indexIds = messageIndex.allIndexedIds();
        Set<Long> dbIds;
        try {
            dbIds = new HashSet<>(messageRepo.findAllMessageIds());
        } catch (RuntimeException e) {
            log.warn("[cleanup:lucene] skipping reconcile — DB read failed", e);
            return;
        }
        var missing = new ArrayList<Long>();
        for (var id : dbIds) {
            if (!indexIds.contains(id)) missing.add(id);
        }
        var stale = new ArrayList<Long>();
        for (var id : indexIds) {
            if (!dbIds.contains(id)) stale.add(id);
        }
        if (missing.isEmpty() && stale.isEmpty()) return;
        if (props.isDryRun()) {
            log.info("[cleanup:lucene] [dry-run] would index {} missing + remove {} stale doc(s)",
                    missing.size(), stale.size());
            return;
        }
        if (!stale.isEmpty()) {
            messageIndex.deleteAll(stale);
        }
        for (int i = 0; i < missing.size(); i += REINDEX_BATCH) {
            var batch = missing.subList(i, Math.min(i + REINDEX_BATCH, missing.size()));
            // The attachment filenames belong to the document as much as the body does. A sweep
            // that rebuilt these without them would be the worst version of this bug: correct on
            // the day it ships, and quietly stripping files out of search an hour later.
            var filenames = MessageIndexService.groupFilenames(
                    messageRepo.findIndexFilenamesByIds(batch));
            var docs = new ArrayList<MessageIndexService.IndexedMessage>(batch.size());
            for (var m : messageRepo.findAllByIdWithAuthor(batch)) {
                docs.add(new MessageIndexService.IndexedMessage(m.getId(), m.getChannel().getId(),
                        m.getAuthor().getUsername(), m.getBodyMarkdown(),
                        filenames.getOrDefault(m.getId(), List.of())));
            }
            messageIndex.reindex(docs); // one commit per batch, not per document (N26)
        }
        log.info("[cleanup:lucene] reconciled: indexed {} missing, removed {} stale",
                missing.size(), stale.size());
    }

    /**
     * CLEAN-3, conversation half: the same reconcile against {@code conversation_messages}. Runs as
     * its own sweep rather than being folded into the one above because the two halves diff
     * disjoint document sets — a channel document is never "stale" because a DM row is missing, and
     * mixing the id spaces of two tables into one comparison is how that mistake gets made.
     */
    @Scheduled(fixedDelayString = "${ichat.cleanup.reconcile-ms:3600000}",
               initialDelayString = "${ichat.cleanup.initial-delay-ms:300000}")
    // Not readOnly = true, for the reason spelled out on reconcileSearchIndex above: this one
    // deletes too, so it has to read the primary.
    @Transactional
    public void reconcileConversationSearchIndex() {
        if (!props.isEnabled()) return;
        // Index first, then the DB — see reconcileSearchIndex above (N3).
        var indexIds = messageIndex.allIndexedConversationIds();
        Set<Long> dbIds;
        try {
            dbIds = new HashSet<>(conversationMessageRepo.findAllMessageIds());
        } catch (RuntimeException e) {
            log.warn("[cleanup:lucene-dm] skipping reconcile — DB read failed", e);
            return;
        }
        var missing = new ArrayList<Long>();
        for (var id : dbIds) {
            if (!indexIds.contains(id)) missing.add(id);
        }
        var stale = new ArrayList<Long>();
        for (var id : indexIds) {
            if (!dbIds.contains(id)) stale.add(id);
        }
        if (missing.isEmpty() && stale.isEmpty()) return;
        if (props.isDryRun()) {
            log.info("[cleanup:lucene-dm] [dry-run] would index {} missing + remove {} stale doc(s)",
                    missing.size(), stale.size());
            return;
        }
        if (!stale.isEmpty()) {
            messageIndex.deleteAllConversationMessages(stale);
        }
        for (int i = 0; i < missing.size(); i += REINDEX_BATCH) {
            var batch = missing.subList(i, Math.min(i + REINDEX_BATCH, missing.size()));
            messageIndex.reindexConversations(MessageIndexService.IndexedConversationMessage
                    .fromRows(conversationMessageRepo.findIndexRowsByIds(batch),
                            MessageIndexService.groupFilenames(
                                    conversationMessageRepo.findIndexFilenamesByIds(batch))));
        }
        log.info("[cleanup:lucene-dm] reconciled: indexed {} missing, removed {} stale",
                missing.size(), stale.size());
    }
}
