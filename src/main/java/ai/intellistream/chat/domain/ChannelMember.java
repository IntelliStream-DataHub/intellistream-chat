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
@Table(name = "channel_members", uniqueConstraints = {
        @UniqueConstraint(name = "uk_channel_member", columnNames = {"channel_id", "user_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    /**
     * This channel's notification override for this member, <b>raw</b>:
     * {@link NotificationLevel#DEFAULT} means "follow the account default", and is what a
     * membership starts as and stays as until the user picks something for this channel
     * specifically.
     *
     * <p>It stores the inheritance, not a snapshot of what the account default resolved to at join
     * time — see {@link NotificationLevel}. Read it raw when rendering a picker (so it can show
     * "Default" selected); resolve it through {@link #effectiveNotifyLevel} when deciding whether
     * to actually notify.
     *
     * <p>No {@code @Setter}, for the same reason {@code role} has one and this doesn't: role is a
     * plain assignment, whereas this field has two distinct meanings for a caller — pin a level,
     * or go back to inheriting — and {@code setNotifyLevel(DEFAULT)} reads like neither.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_level", nullable = false, length = 16)
    private NotificationLevel notifyLevel = NotificationLevel.DEFAULT;

    /**
     * Whether this member has starred the channel — the Slack / Mattermost favourite, which groups
     * the channel at the top of their sidebar.
     *
     * <p>Per membership rather than in its own table: the star has the same subject and the same
     * lifetime as the notification override sitting beside it, and leaving the channel ends both.
     * See {@code V9__channel_favourites.sql}.
     *
     * <p>The star used to mean something else entirely in the UI — it marked a channel you were an
     * <em>admin</em> of, which is not what a star means anywhere else in this product category.
     * Nothing about that meaning was ever stored; it was read off the member's role.
     */
    @Column(nullable = false)
    private boolean favourite = false;

    public ChannelMember(Channel channel, User user, ChannelRole role) {
        this.channel = channel;
        this.user = user;
        this.role = role;
    }

    /** Star or unstar the channel for this member. */
    public void setFavourite(boolean favourite) {
        this.favourite = favourite;
    }

    /** True while this channel takes its level from the account default rather than its own. */
    public boolean followsAccountDefault() {
        return notifyLevel.isInherited();
    }

    /**
     * Set this channel's own notification level. Passing {@link NotificationLevel#DEFAULT} is the
     * supported way to clear the override and go back to following the account default — the same
     * value the picker shows, so the UI needs no special case for "unset".
     */
    public void chooseNotifyLevel(NotificationLevel level) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Notification level is required — pass DEFAULT to follow the account default");
        }
        this.notifyLevel = level;
    }

    /** Shorthand for {@code chooseNotifyLevel(DEFAULT)}, for call sites that mean exactly that. */
    public void followAccountDefault() {
        this.notifyLevel = NotificationLevel.DEFAULT;
    }

    /**
     * The level actually in force, resolving {@code DEFAULT} against the given account default.
     *
     * <p>Takes the account default as an argument rather than reading {@code user.getNotifyDefault()}
     * itself: {@code user} is a {@code LAZY} association, and resolving it here would fire a select
     * — or throw {@code LazyInitializationException} — on paths that already hold the {@link User}.
     */
    public NotificationLevel effectiveNotifyLevel(NotificationLevel accountDefault) {
        return notifyLevel.resolvedAgainst(accountDefault);
    }
}
