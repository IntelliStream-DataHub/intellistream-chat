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

package ai.intellistream.chat.search;

import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * On startup, if the Lucene index is empty (fresh deployment, wiped data dir, or a recent
 * cutover from the old tsvector column), rebuild it from the {@code messages} table. The
 * write path keeps the index in sync afterwards.
 */
@Component
public class LuceneBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LuceneBootstrap.class);

    private final MessageIndexService messageIndex;
    private final MessageRepository messageRepository;
    private final ConversationMessageRepository conversationMessageRepository;

    public LuceneBootstrap(MessageIndexService messageIndex,
                           MessageRepository messageRepository,
                           ConversationMessageRepository conversationMessageRepository) {
        this.messageIndex = messageIndex;
        this.messageRepository = messageRepository;
        this.conversationMessageRepository = conversationMessageRepository;
    }

    /** Messages fetched per keyset page — bounds the working set during a rebuild. */
    private static final int PAGE_SIZE = 5000;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void rebuildOrReconcile() {
        if (messageIndex.isEmpty()) {
            if (messageRepository.count() > 0) {
                log.info("Rebuilding Lucene index from the messages table (streaming, {} per page)…", PAGE_SIZE);
                // Feed rebuild a LAZY iterable that keyset-pages a flat (id, channelId, author, body)
                // projection — no Message entities, no per-author N+1, and only one page in memory at a
                // time, instead of findAll() materialising the whole table (BUG-24).
                messageIndex.rebuild(this::streamingIterator);
            }
        } else {
            // Index already populated — heal any tail an unclean shutdown left behind: with async
            // commits (see MessageIndexService / scalability.md), docs indexed since the last commit
            // are lost from the index on a crash but still durable in the DB. Re-index what's missing
            // and drop docs whose message is gone. This heals the tail in seconds at startup; the
            // periodic CLEAN-3 reconcile is the ongoing backstop.
            reconcileTail();
        }
        // Conversations, always, and AFTER the channel branch: rebuild() starts with a deleteAll(),
        // which would take conversation documents with it if the order were reversed.
        //
        // This one sweep covers three cases with the same code. Fresh index: every conversation
        // message reads as "missing" and gets indexed. Upgrade from a build that only indexed
        // channels: identical — the index is non-empty, but it holds no conversation documents, so
        // the whole DM corpus backfills on first boot. Steady state: it heals the crash tail, the
        // same way reconcileTail() does for channels.
        reconcileConversationTail();
    }

    private void reconcileTail() {
        // Read the INDEX first, then the DB (N3): a message posted after ApplicationReadyEvent (the
        // server is already serving) is then "missing" → re-indexed (a no-op), never "stale" → dropped.
        var indexIds = messageIndex.allIndexedIds();
        var dbIds = new java.util.HashSet<>(messageRepository.findAllMessageIds());
        var missing = new ArrayList<Long>();
        for (var id : dbIds) {
            if (!indexIds.contains(id)) missing.add(id);
        }
        var stale = new ArrayList<Long>();
        for (var id : indexIds) {
            if (!dbIds.contains(id)) stale.add(id);
        }
        if (missing.isEmpty() && stale.isEmpty()) {
            return; // clean shutdown — nothing to heal
        }
        log.info("Startup index reconcile: re-indexing {} missing, dropping {} stale doc(s)",
                missing.size(), stale.size());
        if (!stale.isEmpty()) {
            messageIndex.deleteAll(stale);
        }
        for (int i = 0; i < missing.size(); i += PAGE_SIZE) {
            var batch = missing.subList(i, Math.min(i + PAGE_SIZE, missing.size()));
            var docs = new ArrayList<MessageIndexService.IndexedMessage>(batch.size());
            for (var m : messageRepository.findAllByIdWithAuthor(batch)) {
                docs.add(new MessageIndexService.IndexedMessage(m.getId(), m.getChannel().getId(),
                        m.getAuthor().getUsername(), m.getBodyMarkdown()));
            }
            messageIndex.reindex(docs);
        }
    }

    /**
     * The conversation-message mirror of {@link #reconcileTail()}: index what the DB has and the
     * index doesn't, drop documents whose row is gone. Same index-then-DB read order (N3), for the
     * same reason — a DM posted after {@code ApplicationReadyEvent} must read as "missing" (a
     * re-index no-op) rather than "stale" (its fresh document deleted).
     *
     * <p>Conversation messages have no soft delete, so "in the DB" and "should be searchable" are
     * the same set; there is no equivalent of the {@code deletedAt is null} subtlety the channel
     * side has to be careful about.
     */
    private void reconcileConversationTail() {
        var indexIds = messageIndex.allIndexedConversationIds();
        var dbIds = new java.util.HashSet<>(conversationMessageRepository.findAllMessageIds());
        var missing = new ArrayList<Long>();
        for (var id : dbIds) {
            if (!indexIds.contains(id)) missing.add(id);
        }
        var stale = new ArrayList<Long>();
        for (var id : indexIds) {
            if (!dbIds.contains(id)) stale.add(id);
        }
        if (missing.isEmpty() && stale.isEmpty()) {
            return;
        }
        log.info("Startup conversation index reconcile: indexing {} missing, dropping {} stale doc(s)",
                missing.size(), stale.size());
        if (!stale.isEmpty()) {
            messageIndex.deleteAllConversationMessages(stale);
        }
        for (int i = 0; i < missing.size(); i += PAGE_SIZE) {
            var batch = missing.subList(i, Math.min(i + PAGE_SIZE, missing.size()));
            messageIndex.reindexConversations(MessageIndexService.IndexedConversationMessage
                    .fromRows(conversationMessageRepository.findIndexRowsByIds(batch)));
        }
    }

    private Iterator<MessageIndexService.IndexedMessage> streamingIterator() {
        return new Iterator<>() {
            private long lastId = 0;
            private Iterator<MessageIndexService.IndexedMessage> page = List.<MessageIndexService.IndexedMessage>of().iterator();
            private boolean exhausted = false;

            private void ensurePage() {
                if (page.hasNext() || exhausted) return;
                var rows = messageRepository.findIndexRowsAfter(lastId, PageRequest.of(0, PAGE_SIZE));
                if (rows.isEmpty()) { exhausted = true; return; }
                var mapped = new ArrayList<MessageIndexService.IndexedMessage>(rows.size());
                for (var r : rows) {
                    long id = ((Number) r[0]).longValue();
                    mapped.add(new MessageIndexService.IndexedMessage(
                            id, ((Number) r[1]).longValue(), (String) r[2], (String) r[3]));
                    lastId = id;
                }
                page = mapped.iterator();
            }

            @Override
            public boolean hasNext() {
                ensurePage();
                return page.hasNext();
            }

            @Override
            public MessageIndexService.IndexedMessage next() {
                ensurePage();
                return page.next();
            }
        };
    }
}
