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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

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
     * IANA zone the user picked on their profile page, or null for "whatever my account says".
     * Deliberately not a {@code @Setter} — see {@link #chooseZone}, which validates.
     */
    @Column(name = "zone_id", length = 64)
    private String zoneId;

    /**
     * IANA zone from the identity provider's {@code zoneinfo} claim, refreshed on sign-in, or
     * null when the IdP supplies none (common — it is an optional OIDC claim). Kept apart from
     * {@link #zoneId} so a login can update the guess without undoing a deliberate choice.
     */
    @Column(name = "oidc_zone_id", length = 64)
    private String oidcZoneId;

    /**
     * The account-wide notification default: how much a channel interrupts this user when they
     * have not said otherwise for that specific channel. Ships as {@code MENTIONS}, which is what
     * the app already did for everyone.
     *
     * <p>Deliberately not a {@code @Setter}: the bottom of the inheritance chain has to be a
     * concrete level, and {@code setNotifyDefault(DEFAULT)} would compile. See
     * {@link #chooseNotifyDefault}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_default", nullable = false, length = 16)
    private NotificationLevel notifyDefault = NotificationLevel.MENTIONS;

    /**
     * The account-wide default for direct and group conversations, kept separate from
     * {@link #notifyDefault} because the two want different answers. MENTIONS is right for a
     * channel and wrong for a DM: a message sent to you and nobody else is addressed to you whether
     * or not it spells your name. Seeded ALL, which is both Slack's behaviour and today's.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_dm_default", nullable = false, length = 16)
    private NotificationLevel notifyDmDefault = NotificationLevel.ALL;

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

    /**
     * The zone to interpret this user's wall-clock times in: their own choice, else what their
     * identity provider reported, else {@code fallback} (the {@code ichat.default-zone} property,
     * which itself defaults to the server zone — so an install that configures nothing behaves
     * exactly as it did before zones existed).
     *
     * <p>A stored id that tzdb no longer knows is skipped rather than thrown: zone names are
     * retired between tzdb releases, and a reminder firing an hour off is a better outcome than a
     * profile page that 500s because the JVM was upgraded.
     */
    public ZoneId effectiveZone(ZoneId fallback) {
        var chosen = parseZone(zoneId);
        if (chosen != null) return chosen;
        var reported = parseZone(oidcZoneId);
        if (reported != null) return reported;
        return fallback;
    }

    /**
     * Record the user's explicit choice. {@code null} or blank clears it, putting them back on
     * whatever their account reports — which is a real setting, not an absence, so the profile
     * page offers it.
     *
     * @throws IllegalArgumentException for a name tzdb does not know. The profile page's options
     *         come from {@code ZoneId.getAvailableZoneIds()}, so anything else is a hand-crafted
     *         request; fixed offsets ({@code +02:00}) are rejected with them, because a user in a
     *         fixed offset stops observing their own daylight saving.
     */
    public void chooseZone(String ianaName) {
        if (ianaName == null || ianaName.isBlank()) {
            this.zoneId = null;
            return;
        }
        var trimmed = ianaName.trim();
        if (!ZoneId.getAvailableZoneIds().contains(trimmed)) {
            throw new IllegalArgumentException("Unknown time zone: " + trimmed);
        }
        this.zoneId = trimmed;
    }

    /**
     * Take the identity provider's {@code zoneinfo} claim. Garbage and unknown names are ignored
     * rather than rejected — this runs on every sign-in and an IdP with a mis-typed attribute must
     * not lock its users out.
     *
     * @return true when the stored value changed, so the caller can skip the write (which is
     *         almost always: a user's zone changes when they move house)
     */
    public boolean noteOidcZone(String ianaName) {
        var trimmed = ianaName == null ? null : ianaName.trim();
        if (trimmed != null && (trimmed.isEmpty() || !ZoneId.getAvailableZoneIds().contains(trimmed))) {
            return false;
        }
        if (java.util.Objects.equals(this.oidcZoneId, trimmed)) return false;
        this.oidcZoneId = trimmed;
        return true;
    }

    /**
     * Read an {@code ichat.default-zone} property value into the fallback zone: the configured one,
     * or the server's when it is blank or unparseable.
     *
     * <p>Static here rather than duplicated at each injection point so that "unset means the server
     * zone" — the promise that installs which configure nothing keep today's behaviour — has one
     * definition. An unparseable value falls back rather than failing startup: a typo in a property
     * that only shifts what "at 14:00" means should not take the application down.
     */
    public static ZoneId zoneOrSystemDefault(String configured) {
        var parsed = parseZone(configured);
        return parsed == null ? ZoneId.systemDefault() : parsed;
    }

    private static ZoneId parseZone(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return ZoneId.of(name.trim());
        } catch (DateTimeException unknownZone) {
            return null;
        }
    }

    /**
     * Pick the account-wide notification default. Every channel this user has not explicitly
     * overridden follows it — they store {@link NotificationLevel#DEFAULT}, not a copy — so this
     * one write moves all of them.
     *
     * @throws IllegalArgumentException for {@code null} or {@link NotificationLevel#DEFAULT}. The
     *         account default is the bottom of the chain: "inherit" here would have nothing to
     *         inherit from. Mirrored by {@code users_notify_default_chk} in the schema.
     */
    public void chooseNotifyDefault(NotificationLevel level) {
        if (level == null || level.isInherited()) {
            throw new IllegalArgumentException(
                    "The account notification default must be ALL, MENTIONS or NONE — "
                            + "there is nothing above it to inherit from");
        }
        this.notifyDefault = level;
    }

    /**
     * Pick the account-wide default for conversations. Same rule as {@link #chooseNotifyDefault}:
     * a real level, never {@code DEFAULT}. Mirrored by {@code users_notify_dm_default_chk}.
     */
    public void chooseNotifyDmDefault(NotificationLevel level) {
        if (level == null || level.isInherited()) {
            throw new IllegalArgumentException(
                    "The account conversation-notification default must be ALL, MENTIONS or NONE — "
                            + "there is nothing above it to inherit from");
        }
        this.notifyDmDefault = level;
    }
}
