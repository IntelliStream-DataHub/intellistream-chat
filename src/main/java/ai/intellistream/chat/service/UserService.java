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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.KeycloakRolesConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class UserService {

    public static final Set<String> ALLOWED_THEMES = Set.of(
            "default", "dark", "orange", "pink", "green", "purple", "red", "cyan");

    /**
     * Accepts only handles that look like reasonable usernames: 1–100 chars of letters,
     * digits, and a few separators. Anything else (control chars, spaces, parents like
     * {@code ..}, mixed-direction unicode) gets rewritten to a stable fallback derived
     * from the OIDC subject so we never persist a hostile-looking name.
     */
    static final Pattern SAFE_USERNAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    /** Cap last_active_at writes to once per minute per user — admin overview doesn't need finer. */
    static final Duration ACTIVE_BUMP_INTERVAL = Duration.ofMinutes(1);

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final ai.intellistream.chat.repository.MessageRepository messageRepository;
    private final ai.intellistream.chat.search.MessageIndexService messageIndex;
    /** In-memory throttle: userId -> instant of the most recent persisted bump. */
    private final ConcurrentHashMap<Long, Instant> lastBumpByUser = new ConcurrentHashMap<>();

    public UserService(UserRepository userRepository,
                       ai.intellistream.chat.repository.MessageRepository messageRepository,
                       ai.intellistream.chat.search.MessageIndexService messageIndex) {
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.messageIndex = messageIndex;
    }

    /**
     * The realm role that maps to {@code ROLE_ADMIN}. Referenced from
     * {@link KeycloakRolesConverter#ADMIN_REALM_ROLE} rather than re-declared: this used to be a
     * second copy of the literal, and two independently-maintained copies of the string that
     * decides who is an administrator is exactly the kind of thing that drifts silently.
     */
    private static final String ADMIN_REALM_ROLE = KeycloakRolesConverter.ADMIN_REALM_ROLE;

    @Transactional
    public User provisionFromOidc(OidcUser oidc) {
        var subject = oidc.getSubject();
        var username = sanitizeUsername(firstNonBlank(
                oidc.getPreferredUsername(),
                oidc.getEmail(),
                subject), subject);
        var email = oidc.getEmail();
        var displayName = firstNonBlank(oidc.getFullName(), username);
        var admin = isAdminFromClaims(oidc.getClaimAsMap("realm_access"));
        return upsert(subject, username, email, displayName, admin);
    }

    @Transactional
    public User provisionFromJwt(Jwt jwt) {
        var subject = jwt.getSubject();
        var username = sanitizeUsername(firstNonBlank(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                subject), subject);
        var email = jwt.getClaimAsString("email");
        var displayName = firstNonBlank(jwt.getClaimAsString("name"), username);
        var admin = isAdminFromClaims(jwt.getClaimAsMap("realm_access"));
        return upsert(subject, username, email, displayName, admin);
    }

    @SuppressWarnings("unchecked")
    private static boolean isAdminFromClaims(Map<String, Object> realmAccess) {
        if (realmAccess == null) return false;
        var roles = realmAccess.get("roles");
        return roles instanceof java.util.Collection<?> col && col.contains(ADMIN_REALM_ROLE);
    }

    /**
     * Force the username to the safe shape — local part of an email if it's an email,
     * otherwise pass-through if it matches the safe pattern, otherwise fall back to
     * "user-&lt;subject prefix&gt;" so we always have a deterministic non-hostile handle.
     */
    public static String sanitizeUsername(String candidate, String subject) {
        if (candidate == null) candidate = "";
        var localPart = candidate.contains("@") ? candidate.substring(0, candidate.indexOf('@')) : candidate;
        // Validate against the raw local part — no trim() — so control characters / spaces in
        // the input force the fallback rather than being silently stripped.
        if (SAFE_USERNAME.matcher(localPart).matches()) {
            return localPart;
        }
        var subjPrefix = subject == null ? "anon" : subject.replaceAll("[^A-Za-z0-9]", "");
        if (subjPrefix.length() > 12) subjPrefix = subjPrefix.substring(0, 12);
        if (subjPrefix.isEmpty()) subjPrefix = "anon";
        return "user-" + subjPrefix;
    }

    @Transactional
    public User upsert(String subject, String username, String email, String displayName) {
        return upsert(subject, username, email, displayName, false);
    }

    /**
     * Return {@code desired} if it's free or already owned by {@code subject}; otherwise append a
     * subject-derived suffix so distinct principals never collapse to the same handle (which the
     * new {@code uk_users_username_lower} index now forbids, and which would otherwise mis-route
     * username-keyed private notices / mentions / presence).
     */
    private String uniqueUsername(String desired, String subject) {
        var holder = userRepository.findByUsernameIgnoreCase(desired);
        if (holder.isEmpty() || java.util.Objects.equals(holder.get().getSubject(), subject)) {
            return desired;
        }
        var suffix = subject == null ? "x" : subject.replaceAll("[^A-Za-z0-9]", "");
        if (suffix.length() > 6) suffix = suffix.substring(0, 6);
        if (suffix.isEmpty()) suffix = "x";
        var candidate = desired + "-" + suffix;
        int n = 2;
        while (takenByOther(candidate, subject)) {
            candidate = desired + "-" + suffix + n++;
        }
        return candidate;
    }

    private boolean takenByOther(String username, String subject) {
        return userRepository.findByUsernameIgnoreCase(username)
                .filter(u -> !java.util.Objects.equals(u.getSubject(), subject))
                .isPresent();
    }

    @Transactional
    public User upsert(String subject, String username, String email, String displayName, boolean admin) {
        var existing = userRepository.findBySubject(subject);
        if (existing.isPresent()) {
            var u = existing.get();
            var oldUsername = u.getUsername();
            var newUsername = uniqueUsername(username, subject);
            u.setUsername(newUsername);
            u.setEmail(email);
            u.setDisplayName(displayName);
            u.setAdmin(admin);
            // The Lucene doc stores the author's username at write time; on a rename, reindex this
            // user's messages so search-by-author finds them under the new handle (N23). Rare, and
            // afterCommit so it reads the committed rename.
            if (!newUsername.equalsIgnoreCase(oldUsername)) {
                reindexAuthorMessagesAfterCommit(u.getId());
            }
            return u;
        }
        // Insert-or-ignore on the subject unique constraint (N1): two concurrent first-time logins
        // for the same subject no longer abort the tx (the old saveAndFlush + catch re-read a
        // poisoned transaction and threw). We then re-read the winning row and refresh its fields
        // so the latest claims win regardless of which INSERT landed first.
        var resolvedUsername = uniqueUsername(username, subject);
        userRepository.insertIgnore(subject, resolvedUsername, email, displayName, admin);
        var u = userRepository.findBySubject(subject).orElseThrow();
        u.setUsername(resolvedUsername);
        u.setEmail(email);
        u.setDisplayName(displayName);
        u.setAdmin(admin);
        return u;
    }

    /** After the rename commits, rebuild this author's Lucene docs (which cache the username) so
     *  search-by-author matches the new handle. Best-effort — a failure just leaves those docs
     *  stale until the message is next edited/reconciled; it must not fail the login (N23). */
    private void reindexAuthorMessagesAfterCommit(Long authorId) {
        Runnable reindex = () -> {
            try {
                var rows = messageRepository.findIndexRowsByAuthor(authorId);
                if (rows.isEmpty()) return;
                var docs = new java.util.ArrayList<
                        ai.intellistream.chat.search.MessageIndexService.IndexedMessage>(rows.size());
                for (var r : rows) {
                    docs.add(new ai.intellistream.chat.search.MessageIndexService.IndexedMessage(
                            ((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                            (String) r[2], (String) r[3]));
                }
                messageIndex.reindex(docs);
            } catch (RuntimeException e) {
                log.warn("Failed to reindex messages for renamed user {}; search-by-author may be "
                        + "stale for their older messages until next edit", authorId, e);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { reindex.run(); }
                    });
        } else {
            reindex.run();
        }
    }

    @Transactional(readOnly = true)
    public User requireBySubject(String subject) {
        return userRepository.findBySubject(subject)
                .orElseThrow(() -> new IllegalStateException("User not provisioned: " + subject));
    }

    @Transactional(readOnly = true)
    public User requireByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));
    }

    /**
     * Throttled per-user write of {@code users.last_active_at}. Returns immediately when the
     * user was bumped within {@link #ACTIVE_BUMP_INTERVAL}, so callers can invoke this on every
     * authenticated request without hammering the DB. Persists to the entity inside a fresh
     * transaction; the in-memory map prevents repeated writes between server restarts only —
     * after a restart the first request from each user pays one bump.
     */
    @Transactional
    public void touchActiveThrottled(User user) {
        if (user == null || user.getId() == null) return;
        var now = Instant.now();
        var prev = lastBumpByUser.get(user.getId());
        if (prev != null && Duration.between(prev, now).compareTo(ACTIVE_BUMP_INTERVAL) < 0) {
            return;
        }
        lastBumpByUser.put(user.getId(), now);
        userRepository.findById(user.getId()).ifPresent(managed -> managed.touchActive(now));
    }

    @Transactional
    public User dismissTutorial(User user) {
        var managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User missing: " + user.getId()));
        managed.setTutorialDismissed(true);
        return managed;
    }

    @Transactional
    public User updateTheme(User user, String theme) {
        if (theme == null || !ALLOWED_THEMES.contains(theme)) {
            throw new IllegalArgumentException("Unknown theme: " + theme);
        }
        var managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User missing: " + user.getId()));
        managed.setTheme(theme);
        return managed;
    }

    private static String firstNonBlank(String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

}
