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

package ai.intellistream.threadorbit.security;

import ai.intellistream.threadorbit.attachments.AttachmentBytes;
import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.service.UserService;
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
        // Per-request last-active stamp; throttled internally so this is cheap.
        userService.touchActiveThrottled(user);
        return user;
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
