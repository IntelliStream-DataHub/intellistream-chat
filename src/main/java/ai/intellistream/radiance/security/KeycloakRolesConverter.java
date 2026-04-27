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

package ai.intellistream.radiance.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Keycloak {@code realm_access.roles} into Spring Security authorities.
 *
 * <p>Only the {@code chat-admin} realm role is recognised — it grants
 * {@code ROLE_ADMIN} (the authority every admin-only path in this app checks).
 * Other realm roles, including Keycloak's built-in {@code admin}, are
 * deliberately ignored: the Keycloak realm admin is not a chat admin.
 */
public class KeycloakRolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String ADMIN_REALM_ROLE = "chat-admin";

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopes.convert(jwt));
        authorities.addAll(adminAuthority(jwt));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username"));
    }

    private Collection<GrantedAuthority> adminAuthority(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            Object roles = map.get("roles");
            if (roles instanceof List<?> list) {
                for (var r : list) {
                    if (ADMIN_REALM_ROLE.equals(String.valueOf(r))) {
                        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    }
                }
            }
        }
        return List.of();
    }
}
