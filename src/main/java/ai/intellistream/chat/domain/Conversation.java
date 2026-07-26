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
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "conversations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conversations_dm_key", columnNames = "dm_key")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConversationType type;

    @Setter
    @Column(length = 120)
    private String title;

    @Column(name = "dm_key", length = 80)
    private String dmKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Conversation(ConversationType type, String title, String dmKey, User createdBy) {
        this.type = type;
        this.title = title;
        this.dmKey = dmKey;
        this.createdBy = createdBy;
    }

    /**
     * True for a DM with yourself — one member, and the {@code dm_key} carries the same user id
     * twice ({@code "7:7"}).
     *
     * <p>Read off the key rather than by counting members because the question is asked from the
     * DTO layer, after the transaction that could load them has closed, and because a self
     * conversation is self-referential by construction rather than by whoever happens to be in it.
     * A two-person key always holds two distinct ids ({@code ConversationService.directKey} sorts
     * them), so the shapes cannot be confused.
     */
    public boolean isSelfDirect() {
        if (type != ConversationType.DIRECT || dmKey == null) return false;
        var colon = dmKey.indexOf(':');
        if (colon <= 0 || colon == dmKey.length() - 1) return false;
        return dmKey.substring(0, colon).equals(dmKey.substring(colon + 1));
    }
}
