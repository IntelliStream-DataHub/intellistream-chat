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

package com.example.chat.search;

import com.example.chat.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

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

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void rebuildIfEmpty() {
        if (!messageIndex.isEmpty()) {
            return;
        }
        var rows = new ArrayList<MessageIndexService.IndexedMessage>();
        for (var m : messageRepository.findAll()) {
            rows.add(new MessageIndexService.IndexedMessage(
                    m.getId(), m.getChannel().getId(),
                    m.getAuthor().getUsername(), m.getBodyMarkdown()));
        }
        if (rows.isEmpty()) {
            return;
        }
        log.info("Rebuilding Lucene index from {} messages", rows.size());
        messageIndex.rebuild(rows);
    }
}
