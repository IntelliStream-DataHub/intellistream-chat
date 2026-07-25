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
@Table(name = "attachments", indexes = {
        @Index(name = "ix_attachments_message", columnList = "message_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Who removed the file. Kept so the message can say "deleted by alice" in place of the
     * attachment — a file that vanishes with no explanation reads as a bug in the application
     * rather than as somebody's decision.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private User deletedBy;

    /**
     * The deleter's name as it was, copied so rendering a message never touches this LAZY
     * association — open-in-view is off, and reading it in a DTO throws. It is also the right
     * semantics for a tombstone: a later rename should not rewrite history.
     */
    @Column(name = "deleted_by_username", length = 120)
    private String deletedByUsername;

    /**
     * Tombstone this attachment: the bytes go, the row stays. Recording the decision is all this
     * does — crediting the quota and reaping the file belong to the caller, inside and after the
     * transaction respectively.
     */
    public void softDelete(User by) {
        this.deletedAt = Instant.now();
        this.deletedBy = by;
        this.deletedByUsername = by == null ? null : by.getUsername();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Attachment(Message message, String filename, String contentType, long sizeBytes, String storageKey) {
        this.message = message;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
    }
}
