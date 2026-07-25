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
@Table(name = "messages", indexes = {
        @Index(name = "ix_messages_channel_created", columnList = "channel_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    private String bodyMarkdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "pinned_at")
    private Instant pinnedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pinned_by")
    private User pinnedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Message parent;

    public Message(Channel channel, User author, String bodyMarkdown) {
        this.channel = channel;
        this.author = author;
        this.bodyMarkdown = bodyMarkdown;
    }

    /**
     * Build a message that already knows its primary key and creation instant, for the write-behind
     * post path: the id is drawn from the table's sequence up front so the message can be
     * broadcast, indexed and returned to the sender immediately, while the row itself is written a
     * few milliseconds later as part of a batch. The instance is never handed to Hibernate — see
     * {@code MessageWriteBehind}.
     */
    public static Message preAssigned(Long id, Channel channel, User author, String bodyMarkdown,
                                      Instant createdAt) {
        var message = new Message(channel, author, bodyMarkdown);
        message.id = id;
        message.createdAt = createdAt;
        return message;
    }

    public Message(Channel channel, User author, String bodyMarkdown, Message parent) {
        this(channel, author, bodyMarkdown);
        this.parent = parent;
    }

    public void setBodyMarkdown(String bodyMarkdown) {
        this.bodyMarkdown = bodyMarkdown;
        this.editedAt = Instant.now();
    }

    public boolean isPinned() {
        return pinnedAt != null;
    }

    public void pin(User by) {
        this.pinnedAt = Instant.now();
        this.pinnedBy = by;
    }

    public void unpin() {
        this.pinnedAt = null;
        this.pinnedBy = null;
    }

    public boolean isThreadReply() {
        return parent != null;
    }

    /**
     * Soft delete. A removed message keeps its row and drops out of every read path; a scheduled
     * purge deletes it for real after the retention window.
     *
     * <p>Hard-deleting on the admin's click would make "clear everything this account wrote"
     * irreversible the instant it is pressed, and the first ban is sometimes the wrong ban.
     */
    @Column(name = "deleted_at")
    private java.time.Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private User deletedBy;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void softDelete(User by) {
        this.deletedAt = java.time.Instant.now();
        this.deletedBy = by;
    }

    public void restore() {
        this.deletedAt = null;
        this.deletedBy = null;
    }
}
