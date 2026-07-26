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

@Entity
@Table(name = "conversation_messages", indexes = {
        @Index(name = "ix_conv_messages_created", columnList = "conversation_id, created_at"),
        @Index(name = "ix_conv_messages_parent", columnList = "parent_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    /**
     * The message this one replies to, or {@code null} for a top-level message. Exactly
     * {@code Message.parent}'s shape, and for the same reason: a thread is a parent plus the
     * messages that name it, not a separate table with its own lifecycle.
     *
     * <p>One level only. A reply may not be replied to — {@code ConversationService.replyInThread}
     * refuses it — so this is a two-deep tree and never a chain, which is what lets the panel render
     * a flat list and the reply count be a single {@code count(*)}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ConversationMessage parent;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    private String bodyMarkdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "edited_at")
    private Instant editedAt;

    public ConversationMessage(Conversation conversation, User author, String bodyMarkdown) {
        this(conversation, author, bodyMarkdown, null);
    }

    public ConversationMessage(Conversation conversation, User author, String bodyMarkdown,
                               ConversationMessage parent) {
        this.conversation = conversation;
        this.author = author;
        this.bodyMarkdown = bodyMarkdown;
        this.parent = parent;
    }

    /** True when this message lives in a thread rather than in the conversation feed. */
    public boolean isThreadReply() {
        return parent != null;
    }

    public void setBodyMarkdown(String bodyMarkdown) {
        this.bodyMarkdown = bodyMarkdown;
        this.editedAt = Instant.now();
    }
}
