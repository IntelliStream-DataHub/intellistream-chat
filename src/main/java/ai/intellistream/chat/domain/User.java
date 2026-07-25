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

package ai.intellistream.chat.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_subject", columnNames = "subject")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String subject;

    @Setter
    @Column(nullable = false, length = 100)
    private String username;

    @Setter
    @Column(length = 255)
    private String email;

    @Setter
    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Setter
    @Column(nullable = false, length = 32)
    private String theme = "default";

    @Setter
    @Column(name = "tutorial_dismissed", nullable = false)
    private boolean tutorialDismissed = false;

    /**
     * Storage key (UUID) for the avatar file under {@code chat.avatars.dir}, or {@code null}
     * when the user is on the auto-generated initial+colour fallback. The {@code updatedAt}
     * timestamp is what we cache-bust avatar URLs with: a fresh value invalidates the
     * browser-side cache without needing per-request {@code Cache-Control: no-store}.
     */
    @Column(name = "avatar_storage_key", length = 255)
    private String avatarStorageKey;

    @Column(name = "avatar_content_type", length = 64)
    private String avatarContentType;

    @Column(name = "avatar_updated_at")
    private Instant avatarUpdatedAt;

    /** Last time we observed this user authenticated against any request — surfaced on the admin page. */
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /**
     * Cached ichat-admin flag, refreshed from the {@code ichat-admin} Keycloak realm role on
     * every login. Read-by-default for other-user lookups (hovercard, admin console rows) so
     * we don't round-trip Keycloak per request. The authoritative source remains Spring's
     * {@code ROLE_ADMIN} authority on the live request — never make access decisions from
     * this column alone.
     */
    @Setter
    @Column(nullable = false)
    private boolean admin = false;

    /**
     * When set, this account is suspended: authenticated but not allowed to use the chat.
     *
     * <p>Local to this application on purpose. Keycloak decides whether the account can obtain a
     * token at all; this decides whether a principal holding one may act. Both are needed, and
     * neither is sufficient: disabling in Keycloak leaves an already-open WebSocket posting until
     * its token expires, and setting this alone does not stop them getting a fresh token.
     * {@code BanService} drives the pair.
     */
    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suspended_by")
    private User suspendedBy;

    @Column(name = "suspension_note", length = 500)
    private String suspensionNote;

    public User(String subject, String username, String email, String displayName) {
        this.subject = subject;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
    }

    public boolean hasAvatar() {
        return avatarStorageKey != null;
    }

    public void setAvatar(String storageKey, String contentType) {
        this.avatarStorageKey = storageKey;
        this.avatarContentType = contentType;
        this.avatarUpdatedAt = Instant.now();
    }

    public void clearAvatar() {
        this.avatarStorageKey = null;
        this.avatarContentType = null;
        this.avatarUpdatedAt = Instant.now();
    }

    /** Millis since epoch for cache-busting query strings; {@code 0} when no avatar yet. */
    public long avatarVersion() {
        return avatarUpdatedAt == null ? 0L : avatarUpdatedAt.toEpochMilli();
    }

    public void touchActive(Instant now) {
        this.lastActiveAt = now;
    }

    /** True when this account is suspended. Read on every authenticated request. */
    public boolean isSuspended() {
        return suspendedAt != null;
    }

    /**
     * Suspend or restore. Deliberately a pair of intention-revealing methods rather than setters:
     * suspension is an authorization state, and {@code setSuspendedAt(null)} at a call site reads
     * like a field assignment rather than like unbanning somebody.
     */
    public void suspend(User by, String note) {
        this.suspendedAt = Instant.now();
        this.suspendedBy = by;
        this.suspensionNote = note;
    }

    public void unsuspend() {
        this.suspendedAt = null;
        this.suspendedBy = null;
        this.suspensionNote = null;
    }
}
