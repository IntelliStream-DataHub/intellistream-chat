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
@Table(name = "conversation_members", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conversation_member", columnNames = {"conversation_id", "user_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    /** Last time this member viewed the conversation; messages newer than this are "unread". */
    @Column(name = "last_read_at")
    private Instant lastReadAt;

    /**
     * This conversation's notification override for this member, <b>raw</b>: {@link
     * NotificationLevel#DEFAULT} means "follow the account default", and is what a membership starts
     * as and stays as until the user picks something for this conversation specifically.
     *
     * <p>Exactly {@code ChannelMember.notifyLevel}, on exactly the same terms and against the same
     * account-wide default. One control, not two: "mute this group DM" and "mute this channel" are
     * the same request, and a separate mechanism for conversations would give the account default
     * two meanings and the user two places to look.
     *
     * <p>No {@code @Setter}, for the reason the channel one has none: the field has two distinct
     * meanings for a caller — pin a level, or go back to inheriting — and
     * {@code setNotifyLevel(DEFAULT)} reads like neither.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_level", nullable = false, length = 16)
    private NotificationLevel notifyLevel = NotificationLevel.DEFAULT;

    public ConversationMember(Conversation conversation, User user) {
        this.conversation = conversation;
        this.user = user;
    }

    public void markRead(Instant now) {
        this.lastReadAt = now;
    }

    /** True while this conversation takes its level from the account default rather than its own. */
    public boolean followsAccountDefault() {
        return notifyLevel.isInherited();
    }

    /**
     * Set this conversation's own notification level. Passing {@link NotificationLevel#DEFAULT}
     * clears the override and goes back to following the account default — the same value the
     * picker shows, so the UI needs no special case for "unset".
     */
    public void chooseNotifyLevel(NotificationLevel level) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Notification level is required — pass DEFAULT to follow the account default");
        }
        this.notifyLevel = level;
    }

    /**
     * The level actually in force, resolving {@code DEFAULT} against the given account default.
     * Takes it as an argument rather than reading {@code user.getNotifyDefault()} because
     * {@code user} is LAZY and resolving it here would fire a select — or throw — on paths that
     * already hold the {@link User}.
     */
    public NotificationLevel effectiveNotifyLevel(NotificationLevel accountDefault) {
        return notifyLevel.resolvedAgainst(accountDefault);
    }
}
