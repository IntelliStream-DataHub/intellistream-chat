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

package ai.intellistream.threadorbit.domain;

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

    public Channel(String slug, String name, String description, ChannelType type, User createdBy) {
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.type = type;
        this.createdBy = createdBy;
    }
}
