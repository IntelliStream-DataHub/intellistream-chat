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

/**
 * A channel. <b>Deliberately immutable after creation</b> — there are no setters, and adding one
 * is a decision with consequences beyond this class.
 *
 * <p>{@code ChannelAccessCache} hands cached {@code Channel} instances to STOMP SUBSCRIBE
 * authorization, which calls {@code ChannelService.requireMember} — and that short-circuits to
 * "allowed" for {@link ChannelType#PUBLIC}. A mutable {@code type} therefore means a
 * PUBLIC→PRIVATE change leaves a stale cached copy authorizing non-members to subscribe to a
 * now-private channel until the cache TTL expires.
 *
 * <p>These fields previously carried Lombok {@code @Setter} with no caller, which made that hazard
 * live-but-unreached while the cache's own documentation claimed the entity was immutable. If you
 * need to rename a channel or change its type, add a method on {@code ChannelService} that
 * performs the change <em>and</em> calls {@code ChannelAccessCache.evictChannel} — don't reinstate
 * a bare setter. {@code ChannelImmutabilityTest} enforces this.
 *
 * <p><b>How that is actually done here.</b> Renaming and archiving both exist, and neither of them
 * added a mutator: they are bulk {@code UPDATE} queries on {@code ChannelRepository}
 * ({@code renameById}, {@code setArchivedById}), called from {@code ChannelService} methods that
 * evict the cache after commit. A bulk update writes the row without ever handing anyone a mutable
 * entity, so the invariant holds by construction rather than by everyone remembering it. Follow that
 * shape for the next field that has to change; a named mutator like {@code rename(...)} on this
 * class would slip past the reflection test while reintroducing exactly the hazard it guards.
 *
 * <p>Persistence is unaffected: {@code @Id} is on the field, so Hibernate uses field access and
 * never needs setters.
 */
@Entity
@Table(name = "channels", uniqueConstraints = {
        @UniqueConstraint(name = "uk_channels_slug", columnNames = "slug")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * When this channel was archived, or {@code null} while it is live.
     *
     * <p>A nullable timestamp rather than a boolean: the banner has to say <em>when</em>, so a
     * boolean would need this column beside it anyway, and two columns that must agree are one more
     * pair that can disagree. Set only by {@code ChannelService.archive} / {@code unarchive} through
     * a bulk UPDATE — there is no setter here, and adding one would put a stale archived flag inside
     * the cached instance {@code ChannelAccessCache} serves to the write check.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Who archived it. The accountable reference; {@link #archivedByUsername} is what gets rendered. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by")
    private User archivedBy;

    /**
     * The archiver's username as it was at the time, copied so rendering the banner never touches
     * the LAZY association above — the channel header is rendered with open-in-view off. Same
     * reasoning, and the same pair of columns, as the attachment tombstones V6 added.
     */
    @Column(name = "archived_by_username", length = 120)
    private String archivedByUsername;

    /**
     * Read-only channel? Derived rather than stored, so there is one fact here and not two.
     *
     * <p>Not a setter and not a mutator — {@code ChannelImmutabilityTest} is unaffected, and it must
     * stay that way: this flag is read by {@code ChannelService.requireWriteAccess} off the instance
     * {@code ChannelAccessCache} hands to the message send path, so a stale copy would let posting
     * continue into an archived channel for up to the cache TTL. That is what
     * {@code ChannelAccessCache.evictChannel} is called for on both archive and unarchive.
     */
    public boolean isArchived() {
        return archivedAt != null;
    }

    public Channel(String slug, String name, String description, ChannelType type, User createdBy) {
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.type = type;
        this.createdBy = createdBy;
    }
}
