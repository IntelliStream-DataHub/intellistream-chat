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

package ai.intellistream.chat.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One person's bookmark on one message — Slack's "Later", Mattermost's saved posts.
 *
 * <p>Private to its owner and invisible to everyone else, including the message's author: this is a
 * note to yourself about somebody else's message, and it would be a different feature entirely if
 * they could see it.
 *
 * <p>Exactly one of {@link #message} and {@link #conversationMessage} is set — a channel message or
 * a DM. The database enforces that with a check constraint rather than trusting the service, and
 * both foreign keys cascade on delete, which is the whole answer to what happens to a save when the
 * thing it points at is deleted, purged, or destroyed with its channel.
 */
@Entity
@Table(name = "message_saves")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageSave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_message_id")
    private ConversationMessage conversationMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public MessageSave(User user, Message message) {
        this.user = user;
        this.message = message;
    }

    public MessageSave(User user, ConversationMessage conversationMessage) {
        this.user = user;
        this.conversationMessage = conversationMessage;
    }

    public boolean isChannelMessage() {
        return message != null;
    }
}
