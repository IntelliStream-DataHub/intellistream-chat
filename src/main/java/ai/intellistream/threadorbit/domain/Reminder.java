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

import java.time.Instant;

/**
 * A queued reminder created by the {@code /remind} slash command. The scheduler watches for
 * rows with {@code firedAt == null && fireAt <= now()} and posts a message into the channel
 * on the creator's behalf, optionally @-mentioning {@link #target}.
 */
@Entity
@Table(name = "reminders", indexes = {
        @Index(name = "ix_reminders_due", columnList = "fire_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    /** Optional — null means "remind the channel" (no @-mention prefix). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id")
    private User target;

    @Column(name = "fire_at", nullable = false)
    private Instant fireAt;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "fired_at")
    private Instant firedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Reminder(Channel channel, User creator, User target, Instant fireAt, String body) {
        this.channel = channel;
        this.creator = creator;
        this.target = target;
        this.fireAt = fireAt;
        this.body = body;
    }

    public void markFired(Instant at) {
        this.firedAt = at;
    }
}
