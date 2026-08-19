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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.KeycloakRolesConverter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class UserService {

    public static final Set<String> ALLOWED_THEMES = Set.of(
            "default", "dark", "orange", "pink", "green", "purple", "red", "cyan",
            "indigo", "teal", "amber", "slate", "mocha", "ocean", "plum", "lime",
            "midnight", "carbon", "forest", "dusk");

    /**
     * Accepts only handles that look like reasonable usernames: 1–100 chars of letters,
     * digits, and a few separators. Anything else (control chars, spaces, parents like
     * {@code ..}, mixed-direction unicode) gets rewritten to a stable fallback derived
     * from the OIDC subject so we never persist a hostile-looking name.
     */
    static final Pattern SAFE_USERNAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    /** Cap last_active_at writes to once per minute per user — admin overview doesn't need finer. */
    static final Duration ACTIVE_BUMP_INTERVAL = Duration.ofMinutes(1);

    /** Hard cap on {@link #searchInviteCandidates}, regardless of what the caller asks for — the
     *  channel settings "Find user" browser is a bounded browse, not a paged directory. */
    public static final int MAX_INVITE_CANDIDATES = 100;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final ai.intellistream.chat.repository.MessageRepository messageRepository;
    private final ai.intellistream.chat.repository.ConversationMessageRepository conversationMessageRepository;
    private final ai.intellistream.chat.search.MessageIndexService messageIndex;
    /** In-memory throttle: userId -> instant of the most recent persisted bump. */
    private final ConcurrentHashMap<Long, Instant> lastBumpByUser = new ConcurrentHashMap<>();
    /** See {@link #upsert(String, String, String, String, boolean, boolean)}. */
    private final boolean linkByVerifiedEmail;

    @org.springframework.beans.factory.annotation.Autowired
    public UserService(UserRepository userRepository,
                       ai.intellistream.chat.repository.MessageRepository messageRepository,
                       ai.intellistream.chat.repository.ConversationMessageRepository conversationMessageRepository,
                       ai.intellistream.chat.search.MessageIndexService messageIndex,
                       @org.springframework.beans.factory.annotation.Value("${ichat.identity.link-by-verified-email:true}")
                       boolean linkByVerifiedEmail) {
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.conversationMessageRepository = conversationMessageRepository;
        this.messageIndex = messageIndex;
        this.linkByVerifiedEmail = linkByVerifiedEmail;
    }

    /** Linking on, which is the shipped default; the tests and the integration test app use this one. */
    public UserService(UserRepository userRepository,
                       ai.intellistream.chat.repository.MessageRepository messageRepository,
                       ai.intellistream.chat.repository.ConversationMessageRepository conversationMessageRepository,
                       ai.intellistream.chat.search.MessageIndexService messageIndex) {
        this(userRepository, messageRepository, conversationMessageRepository, messageIndex, true);
    }

    /**
     * The realm role that maps to {@code ROLE_ADMIN}. Referenced from
     * {@link KeycloakRolesConverter#ADMIN_REALM_ROLE} rather than re-declared: this used to be a
     * second copy of the literal, and two independently-maintained copies of the string that
     * decides who is an administrator is exactly the kind of thing that drifts silently.
     */
    private static final String ADMIN_REALM_ROLE = KeycloakRolesConverter.ADMIN_REALM_ROLE;

    /**
     * What the identity provider says an account should look like, extracted from a token and
     * carrying no database state. One shape for both token flavours so the provisioning path and
     * the read-only fast path below cannot drift in how they read a claim — the failure that
     * would produce is a fast path that thinks nothing changed while {@link #upsert} thinks
     * something did, which is a write on every single request and no error anywhere.
     */
    public record ClaimView(String subject, String username, String email,
                            String displayName, boolean admin, boolean emailVerified) {
        /** No {@code email_verified} claim, which the write path treats as "not verified". */
        public ClaimView(String subject, String username, String email, String displayName, boolean admin) {
            this(subject, username, email, displayName, admin, false);
        }
    }

    public static ClaimView claimsOf(OidcUser oidc) {
        var subject = oidc.getSubject();
        var username = sanitizeUsername(firstNonBlank(
                oidc.getPreferredUsername(),
                oidc.getEmail(),
                subject), subject);
        return new ClaimView(subject, username, oidc.getEmail(),
                firstNonBlank(oidc.getFullName(), username),
                isAdminFromClaims(oidc.getClaimAsMap("realm_access")),
                Boolean.TRUE.equals(oidc.getEmailVerified()));
    }

    public static ClaimView claimsOf(Jwt jwt) {
        var subject = jwt.getSubject();
        var username = sanitizeUsername(firstNonBlank(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                subject), subject);
        return new ClaimView(subject, username, jwt.getClaimAsString("email"),
                firstNonBlank(jwt.getClaimAsString("name"), username),
                isAdminFromClaims(jwt.getClaimAsMap("realm_access")),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")));
    }

    /**
     * The row for these claims, if it already exists and already agrees with them — the common
     * case on every request after the first.
     *
     * <p><b>Why this exists.</b> {@code CurrentUser} resolves a principal on every authenticated
     * request, and {@link #upsert} answers it with a read-write transaction and two queries: one
     * to find the row, one inside {@link #uniqueUsername} to check the handle is free. For an
     * account whose claims have not changed since last request — which is essentially all of them,
     * essentially always — both the second query and the writable transaction are pure overhead.
     * This answers the same question with one {@code select} in a read-only transaction, which
     * also makes it eligible for the read replica when one is configured.
     *
     * <p>Returns empty for anything that might need a write: no row yet, a renamed handle, a
     * changed email or display name, a role change. The caller falls back to {@link #upsert},
     * which is unchanged and still the only thing that writes. That fallback is stable rather than
     * repeating: {@code upsert} settles the row to exactly the shape this method tests for, so an
     * account takes the slow path once and the fast path afterwards.
     *
     * <p>The comparison is deliberately exact, not case-insensitive. A row holding {@code Alice}
     * for a claim of {@code alice} is a rename that {@code upsert} should perform once, not a
     * match to be tolerated forever.
     */
    @Transactional(readOnly = true)
    public Optional<User> findUnchanged(ClaimView claims) {
        return userRepository.findBySubject(claims.subject()).filter(u -> agreesWith(u, claims));
    }

    private static boolean agreesWith(User user, ClaimView claims) {
        return Objects.equals(user.getUsername(), claims.username())
                && Objects.equals(user.getEmail(), claims.email())
                && Objects.equals(user.getDisplayName(), claims.displayName())
                && user.isAdmin() == claims.admin();
    }

    @Transactional
    public User provisionFromOidc(OidcUser oidc) {
        var claims = claimsOf(oidc);
        return upsert(claims.subject(), claims.username(), claims.email(),
                claims.displayName(), claims.admin(), claims.emailVerified());
    }

    @Transactional
    public User provisionFromJwt(Jwt jwt) {
        var claims = claimsOf(jwt);
        return upsert(claims.subject(), claims.username(), claims.email(),
                claims.displayName(), claims.admin(), claims.emailVerified());
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
        return uniqueUsername(desired, subject, null);
    }

    /**
     * As above, but skipping the collision query when {@code held} — the row already owned by
     * {@code subject} — is itself the holder of {@code desired}.
     *
     * <p>{@code uk_users_username_lower} (V2) makes at most one row match a handle
     * case-insensitively, so if this subject's own row already holds it, the query can only come
     * back with that same row and return {@code desired} unchanged. Skipping it is the second of
     * the two per-request queries {@code CurrentUser} used to pay, and it is what keeps the
     * fall-through path cheap for the accounts that never reach the fast path — a handle that had
     * to be suffixed for a collision never equals its own claim, so those requests land here every
     * time.
     */
    private String uniqueUsername(String desired, String subject, User held) {
        if (held != null && held.getUsername() != null
                && held.getUsername().equalsIgnoreCase(desired)) {
            return desired;
        }
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

    /** {@link #upsert(String, String, String, String, boolean, boolean)} with the email treated as unverified. */
    @Transactional
    public User upsert(String subject, String username, String email, String displayName, boolean admin) {
        return upsert(subject, username, email, displayName, admin, false);
    }

    /**
     * Settle the row for {@code subject} to what the token says, creating it if needed.
     *
     * <p><b>A subject the app has never seen may be an account it already has.</b> The subject is
     * the key because it is the one claim the identity provider promises not to change — but the
     * promise is per realm. Move the app to a dedicated Keycloak realm and broker the old realm in
     * as an identity provider, and every existing person arrives with a brand-new subject and their
     * old email; keying blindly on the subject then creates a second account per person, with the
     * history stranded on the first. So before inserting, if the token says the email is
     * <em>verified</em> and exactly one existing account carries it, that account is re-keyed to the
     * new subject ({@link User#relink}) and refreshed as usual. Everything referencing the user does
     * so by id, so this is the whole merge.
     *
     * <p>Verified is the bar because it is what makes "same email" mean "same person": Keycloak only
     * sets {@code email_verified} after its own verification, an admin's say-so, or a brokered
     * identity provider marked <em>Trust Email</em>. An unverified claim — an open self-registration
     * typing someone else's address — is never linked. Two existing accounts with the email are
     * never linked either; that is the state linking exists to prevent, and if it has already
     * happened the fix is an operator's decision, not a guess at login. Off with
     * {@code ichat.identity.link-by-verified-email=false}, for deployments where an address can be
     * reassigned to a different person and must not inherit the previous holder's account.
     */
    @Transactional
    public User upsert(String subject, String username, String email, String displayName,
                       boolean admin, boolean emailVerified) {
        var existing = userRepository.findBySubject(subject);
        if (existing.isEmpty()) {
            existing = linkableByVerifiedEmail(subject, email, emailVerified);
        }
        if (existing.isPresent()) {
            var u = existing.get();
            var oldUsername = u.getUsername();
            var newUsername = uniqueUsername(username, subject, u);
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

    /**
     * The one existing account {@code subject} is the new key for, if there is exactly one and the
     * token vouches for the email; empty otherwise. Re-keys it before returning, so the caller's
     * ordinary refresh — including {@link #uniqueUsername}, which excludes the row by its subject —
     * sees the row already under the new subject.
     */
    private Optional<User> linkableByVerifiedEmail(String subject, String email, boolean emailVerified) {
        if (!linkByVerifiedEmail || !emailVerified || email == null || email.isBlank()) {
            return Optional.empty();
        }
        var candidates = userRepository.findAllByEmailIgnoreCase(email);
        if (candidates.size() != 1) {
            if (candidates.size() > 1) {
                log.warn("Not linking new subject to any account: {} accounts share the verified email of "
                        + "the arriving token (ids {}); resolve the duplicates by hand",
                        candidates.size(), candidates.stream().map(User::getId).toList());
            }
            return Optional.empty();
        }
        var u = candidates.get(0);
        log.info("Linking account #{} ({}) to new subject {} by verified email; previous subject {}",
                u.getId(), u.getUsername(), subject, u.getSubject());
        u.relink(subject);
        return Optional.of(u);
    }

    /** After the rename commits, rebuild this author's Lucene docs (which cache the username) so
     *  search-by-author matches the new handle. Best-effort — a failure just leaves those docs
     *  stale until the message is next edited/reconciled; it must not fail the login (N23). */
    private void reindexAuthorMessagesAfterCommit(Long authorId) {
        Runnable reindex = () -> {
            try {
                var rows = messageRepository.findIndexRowsByAuthor(authorId);
                if (!rows.isEmpty()) {
                    // Their attachment filenames too. This rewrites whole documents, so leaving
                    // them out would make a rename the thing that silently un-finds every file the
                    // renamed account ever shared.
                    var ids = rows.stream().map(r -> ((Number) r[0]).longValue()).toList();
                    messageIndex.reindex(
                            ai.intellistream.chat.search.MessageIndexService.IndexedMessage.fromRows(
                                    rows, ai.intellistream.chat.search.MessageIndexService
                                            .groupFilenames(messageRepository.findIndexFilenamesByIds(ids))));
                }
                // The same is true of their DMs and group messages: those documents cache the
                // username too, so `@newhandle` has to find them as well.
                var convRows = conversationMessageRepository.findIndexRowsByAuthor(authorId);
                if (!convRows.isEmpty()) {
                    var convIds = convRows.stream().map(r -> ((Number) r[0]).longValue()).toList();
                    messageIndex.reindexConversations(
                            ai.intellistream.chat.search.MessageIndexService.IndexedConversationMessage
                                    .fromRows(convRows, ai.intellistream.chat.search.MessageIndexService
                                            .groupFilenames(conversationMessageRepository
                                                    .findIndexFilenamesByIds(convIds))));
                }
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
     * Accounts not already in {@code channelId}, for the channel settings "Find user" browser —
     * a deliberate, bounded exception to the "no unscoped user directory" stance documented on
     * {@code UserRestController.mentionCandidates}: it exists at the same authorization bar as
     * inviting itself ({@code requireWriteAccess}, checked by the caller before this runs), is
     * capped at {@link #MAX_INVITE_CANDIDATES} regardless of {@code limit}, and never returns an
     * email address — only what a caller filtered by, never what they'd otherwise have no reason
     * to see.
     *
     * <p>{@code usernameQuery} accepts {@code *}/{@code ?} wildcards; with neither present it is
     * a plain substring match. {@code emailDomainQuery} matches accounts whose email's domain
     * <em>starts with</em> the given text (an optional leading {@code @} is ignored), so
     * {@code "example"} also finds {@code example.org} — not just {@code example.com}. Sorted
     * newest-created first when {@code recentFirst}, else by username.
     */
    @Transactional(readOnly = true)
    public List<User> searchInviteCandidates(Long channelId, String usernameQuery, String emailDomainQuery,
                                              boolean recentFirst, int limit) {
        var sort = recentFirst
                ? Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))
                : Sort.by(Sort.Direction.ASC, "username").and(Sort.by(Sort.Direction.ASC, "id"));
        var page = PageRequest.of(0, Math.clamp(limit, 1, MAX_INVITE_CANDIDATES), sort);
        return userRepository.searchNotInChannel(channelId, usernamePattern(usernameQuery),
                emailDomainPattern(emailDomainQuery), page);
    }

    /** Escapes {@code %}/{@code _}/{@code !} with {@code !} so a literal one in the input can't
     *  be mistaken for a SQL wildcard — mirrors {@code UserFileService.likePattern}. */
    private static String escapeLikeLiteral(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' || c == '_' || c == '!') sb.append('!');
            sb.append(c);
        }
        return sb.toString();
    }

    /** {@code *}/{@code ?} become SQL {@code %}/{@code _}; with neither present, wraps in
     *  {@code %...%} for a substring match. Blank input matches everything. */
    static String usernamePattern(String query) {
        if (query == null || query.isBlank()) return "%";
        var trimmed = query.trim();
        if (trimmed.indexOf('*') < 0 && trimmed.indexOf('?') < 0) {
            return "%" + escapeLikeLiteral(trimmed) + "%";
        }
        var sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            switch (c) {
                case '%', '_', '!' -> sb.append('!').append(c);
                case '*' -> sb.append('%');
                case '?' -> sb.append('_');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Blank input disables the filter ({@code ""}, which {@code searchNotInChannel} treats as
     *  "skip this predicate"); otherwise a domain-starts-with pattern, an optional leading
     *  {@code @} stripped so "example.com" and "@example.com" behave the same. */
    static String emailDomainPattern(String query) {
        if (query == null || query.isBlank()) return "";
        var trimmed = query.trim();
        if (trimmed.startsWith("@")) trimmed = trimmed.substring(1);
        if (trimmed.isEmpty()) return "";
        return "%@" + escapeLikeLiteral(trimmed) + "%";
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
