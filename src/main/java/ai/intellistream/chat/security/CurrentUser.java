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

package ai.intellistream.chat.security;

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.AccountSuspendedException;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Collection;

@Component
public class CurrentUser {

    private static final Logger log = LoggerFactory.getLogger(CurrentUser.class);

    /**
     * The standard OIDC claim carrying an IANA zone name ("Europe/Oslo"). Optional in the spec and
     * absent unless Keycloak has a mapper for it, which is why every path here tolerates null.
     */
    private static final String ZONEINFO_CLAIM = "zoneinfo";

    private final UserService userService;
    private final UserRepository users;

    public CurrentUser(UserService userService, UserRepository users) {
        this.userService = userService;
        this.users = users;
    }

    public User require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return resolve(auth);
    }

    public User resolve(Principal principal) {
        if (principal instanceof Authentication auth) {
            return resolve(auth);
        }
        throw new AccessDeniedException("Authentication required");
    }

    public User resolve(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }
        var principal = auth.getPrincipal();
        User user;
        if (principal instanceof OidcUser oidc) {
            user = userService.findUnchanged(UserService.claimsOf(oidc))
                    .orElseGet(() -> userService.provisionFromOidc(oidc));
        } else if (auth instanceof JwtAuthenticationToken jwtAuth) {
            var token = jwtAuth.getToken();
            user = userService.findUnchanged(UserService.claimsOf(token))
                    .orElseGet(() -> userService.provisionFromJwt(token));
        } else if (principal instanceof Jwt jwt) {
            user = userService.findUnchanged(UserService.claimsOf(jwt))
                    .orElseGet(() -> userService.provisionFromJwt(jwt));
        } else {
            // Don't echo the principal class name in the response — log it server-side and
            // reply with a generic message so we don't leak internal types to API clients.
            log.warn("Unsupported principal type: {}", principal == null ? "null" : principal.getClass().getName());
            throw new AccessDeniedException("Unsupported principal type");
        }
        // Suspension is judged on the row we have just loaded: every controller, every page, and
        // the STOMP CONNECT frame come through here, and none of them pays an extra query for it.
        // SuspensionEnforcementFilter answers the same question earlier and from memory so it can
        // return a useful body; this is the backstop that holds when that in-memory view is wrong,
        // and the reason a hand-edited suspended_at takes effect without a restart.
        //
        // With a read replica configured, "without a restart" becomes "within replication lag":
        // the fast path above is a read-only transaction, so the row may be a moment old. That
        // costs nothing in the case this backstop mostly exists for — a ban issued through
        // BanService updates SuspensionRegistry before it writes the row, so the filter refuses
        // the request before it ever reaches here. What lags is the case the registry cannot see:
        // a suspended_at edited straight in psql, or a ban issued by another node. Both are
        // delayed by the lag, neither is missed.
        if (user.isSuspended()) {
            throw new AccountSuspendedException(user.getUsername());
        }
        // Per-request last-active stamp; throttled internally so this is cheap.
        userService.touchActiveThrottled(user);
        syncZoneFromClaims(auth, user);
        return user;
    }

    /**
     * Keep the user's IdP-reported timezone current from the {@code zoneinfo} claim.
     *
     * <p>Here rather than in the profile page or a login listener because this is the one place
     * every entry point already passes through — page loads, API calls and the STOMP CONNECT frame
     * — and because reading claims anywhere else is the rule this class exists to enforce.
     *
     * <p>The write only happens when the value actually changed, which for a given user is once
     * ever (or once per move). {@code noteOidcZone} returning false is the common case and costs
     * nothing, so this does not add a query to any request. It never touches
     * {@link User#chooseZone}'s column: an explicit choice outranks the IdP forever, including
     * when the IdP later disagrees.
     *
     * <p>Failures are swallowed. A timezone hint is not worth failing a request over, and a save
     * can lose a race with a concurrent update of the same row.
     */
    private void syncZoneFromClaims(Authentication auth, User user) {
        try {
            var claim = readStringClaim(auth, ZONEINFO_CLAIM);
            if (claim != null && user.noteOidcZone(claim)) {
                users.save(user);
            }
        } catch (RuntimeException e) {
            log.debug("Could not record the zoneinfo claim for {}", user.getUsername(), e);
        }
    }

    /** A string claim from whichever token shape this principal carries, or null. */
    private static String readStringClaim(Authentication auth, String name) {
        Object raw = null;
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            raw = oidc.getClaim(name);
        } else if (auth instanceof JwtAuthenticationToken jat) {
            raw = jat.getToken().getClaim(name);
        } else if (auth.getPrincipal() instanceof Jwt jwt) {
            raw = jwt.getClaim(name);
        }
        if (!(raw instanceof String s) || s.isBlank()) return null;
        return s;
    }

    /**
     * The OIDC/OAuth2 subject behind an {@link Authentication}, or null for a principal type that
     * carries none (anonymous, or a test token).
     *
     * <p>Exposed so the suspension filter can identify the caller without resolving — and building
     * — a domain {@code User}, while still not reading token claims itself: keeping every claim
     * lookup in this class is what makes "always go through {@code CurrentUser}" true rather than
     * aspirational. The subject, not the username, because it is the immutable key the {@code users}
     * table is unique on; usernames get sanitized and collision-suffixed on the way in.
     */
    public static String subjectOf(Authentication auth) {
        if (auth == null) return null;
        if (auth.getPrincipal() instanceof OidcUser oidc) return oidc.getSubject();
        if (auth instanceof JwtAuthenticationToken jwtAuth) return jwtAuth.getToken().getSubject();
        if (auth.getPrincipal() instanceof Jwt jwt) return jwt.getSubject();
        return null;
    }

    /**
     * Effective per-user upload cap in bytes. Resolution order:
     * <ol>
     *   <li>{@code ROLE_ADMIN} → unlimited ({@link AttachmentBytes#UNLIMITED}).</li>
     *   <li>JWT/OIDC claim {@code chat_max_upload_bytes} present → that value
     *       (a positive byte count, or any negative value meaning unlimited).
     *       Set per-user in Keycloak: <em>Users → pick user → Attributes →
     *       chat_max_upload_bytes</em>.</li>
     *   <li>Default → {@link AttachmentBytes#UNLIMITED}. There is no size a file has to be under
     *       to be worth sending to a colleague, and the number that used to be here (50 MiB) was
     *       small enough to refuse a screen recording or a database dump — the two things people
     *       most often need to hand over. Uploads stream straight to disk and are never buffered
     *       in memory, so a large one costs disk and time rather than heap; what protects the
     *       volume is the free-space floor ({@code ichat.attachments.min-free-bytes}) and, if an
     *       operator wants one, a per-account quota.</li>
     * </ol>
     * An unknown principal type also gets unlimited, matching the signed-in default: this is a cap
     * an operator opts into per user, not a guard the application depends on. If you want one, set
     * {@code chat_max_upload_bytes} in Keycloak — for everyone, or for the accounts you don't trust
     * with the disk.
     */
    public long uploadCapBytes(Principal principal) {
        if (!(principal instanceof Authentication auth) || auth.getPrincipal() == null) {
            return AttachmentBytes.UNLIMITED;
        }
        if (hasAdminAuthority(auth.getAuthorities())) {
            return AttachmentBytes.UNLIMITED;
        }
        var claim = readClaim(auth);
        return claim == null ? AttachmentBytes.UNLIMITED : claim;
    }

    private static boolean hasAdminAuthority(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) return false;
        for (var a : authorities) if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        return false;
    }

    /**
     * Pull the {@code chat_max_upload_bytes} claim out of the underlying token. Both the
     * resource-server JWT path ({@link JwtAuthenticationToken}, {@link Jwt}) and the
     * browser OIDC path ({@link OidcUser}) carry the same claim shape; the value is
     * deserialised by Jackson as either a {@code Number} or a numeric {@code String}
     * depending on the Keycloak protocol mapper config, so we accept both.
     */
    private static Long readClaim(Authentication auth) {
        Object raw = null;
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            raw = oidc.getIdToken().getClaim("chat_max_upload_bytes");
        } else if (auth instanceof JwtAuthenticationToken jat) {
            raw = jat.getToken().getClaim("chat_max_upload_bytes");
        } else if (auth.getPrincipal() instanceof Jwt jwt) {
            raw = jwt.getClaim("chat_max_upload_bytes");
        }
        if (raw == null) return null;
        try {
            if (raw instanceof Number n) return n.longValue();
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException ex) {
            return null; // garbage in the claim → fall through to the default
        }
    }
}
