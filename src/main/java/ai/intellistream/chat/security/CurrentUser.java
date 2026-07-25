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

    private final UserService userService;

    public CurrentUser(UserService userService) {
        this.userService = userService;
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
            user = userService.provisionFromOidc(oidc);
        } else if (auth instanceof JwtAuthenticationToken jwtAuth) {
            user = userService.provisionFromJwt(jwtAuth.getToken());
        } else if (principal instanceof Jwt jwt) {
            user = userService.provisionFromJwt(jwt);
        } else {
            // Don't echo the principal class name in the response — log it server-side and
            // reply with a generic message so we don't leak internal types to API clients.
            log.warn("Unsupported principal type: {}", principal == null ? "null" : principal.getClass().getName());
            throw new AccessDeniedException("Unsupported principal type");
        }
        // Suspension is judged on the row we have just loaded, which makes this the one check that
        // cannot be stale: every controller, every page, and the STOMP CONNECT frame come through
        // here, and none of them pays an extra query for it. SuspensionEnforcementFilter answers
        // the same question earlier and from memory so it can return a useful body; this is the
        // backstop that holds when that in-memory view is wrong, and the reason a hand-edited
        // suspended_at takes effect without a restart.
        if (user.isSuspended()) {
            throw new AccountSuspendedException(user.getUsername());
        }
        // Per-request last-active stamp; throttled internally so this is cheap.
        userService.touchActiveThrottled(user);
        return user;
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
     *   <li>Default → {@link AttachmentBytes#DEFAULT_MAX_BYTES} (50 MiB).</li>
     * </ol>
     * Falls back to the default for any unknown principal type so we never
     * accidentally grant unlimited uploads on a misconfigured request.
     */
    public long uploadCapBytes(Principal principal) {
        if (!(principal instanceof Authentication auth) || auth.getPrincipal() == null) {
            return AttachmentBytes.DEFAULT_MAX_BYTES;
        }
        if (hasAdminAuthority(auth.getAuthorities())) {
            return AttachmentBytes.UNLIMITED;
        }
        var claim = readClaim(auth);
        return claim == null ? AttachmentBytes.DEFAULT_MAX_BYTES : claim;
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
