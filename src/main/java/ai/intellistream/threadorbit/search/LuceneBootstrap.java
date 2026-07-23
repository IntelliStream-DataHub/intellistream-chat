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

package ai.intellistream.threadorbit.search;

import ai.intellistream.threadorbit.repository.MessageRepository;
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

    public LuceneBootstrap(MessageIndexService messageIndex, MessageRepository messageRepository) {
        this.messageIndex = messageIndex;
        this.messageRepository = messageRepository;
    }

    /** Messages fetched per keyset page — bounds the working set during a rebuild. */
    private static final int PAGE_SIZE = 5000;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void rebuildIfEmpty() {
        if (!messageIndex.isEmpty()) {
            return;
        }
        if (messageRepository.count() == 0) {
            return;
        }
        log.info("Rebuilding Lucene index from the messages table (streaming, {} per page)…", PAGE_SIZE);
        // Feed rebuild a LAZY iterable that keyset-pages a flat (id, channelId, author, body)
        // projection — no Message entities, no per-author N+1, and only one page in memory at a
        // time, instead of findAll() materialising the whole table (BUG-24).
        messageIndex.rebuild(this::streamingIterator);
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
