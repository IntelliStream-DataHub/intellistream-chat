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

package ai.intellistream.radiance.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted custom-status row for a user. The {@code online} side of presence is in-memory
 * only (driven by STOMP session lifecycle in {@code PresenceTracker}); this row exists so a
 * user's lunch-break status survives reconnects, refreshes, and even server restarts up
 * until {@code statusClearAt} kicks in.
 */
@Entity
@Table(name = "user_presence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPresence {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "status_emoji", length = 16)
    private String statusEmoji;

    @Column(name = "status_text", length = 120)
    private String statusText;

    @Column(name = "status_clear_at")
    private Instant statusClearAt;

    /**
     * Manual presence override (AWAY / DND / OFFLINE) — beats the auto-derived
     * connection state. {@code null} means "no override; use the auto state".
     * {@link PresenceKind#ACTIVE} is never persisted here (it's the default).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "manual_status_kind", length = 16)
    private PresenceKind manualKind;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserPresence(User user) {
        this.user = user;
    }

    public void setStatus(String emoji, String text, Instant clearAt) {
        this.statusEmoji = emoji;
        this.statusText = text;
        this.statusClearAt = clearAt;
        this.updatedAt = Instant.now();
    }

    public void clearStatus() {
        this.statusEmoji = null;
        this.statusText = null;
        this.statusClearAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Set the manual override. {@link PresenceKind#ACTIVE} clears the override
     * (treated the same as {@link #clearManualKind()}); the other values are
     * stored verbatim.
     */
    public void setManualKind(PresenceKind kind) {
        this.manualKind = (kind == null || !kind.isManual()) ? null : kind;
        this.updatedAt = Instant.now();
    }

    public void clearManualKind() {
        this.manualKind = null;
        this.updatedAt = Instant.now();
    }

    /** Treat the status as gone if its auto-clear is in the past. */
    public boolean hasActiveStatus(Instant now) {
        if (statusEmoji == null && (statusText == null || statusText.isBlank())) return false;
        return statusClearAt == null || statusClearAt.isAfter(now);
    }
}
