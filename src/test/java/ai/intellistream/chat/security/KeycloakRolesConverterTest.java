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

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rule the user requested: only the Keycloak realm role {@code ichat-admin} grants
 * Spring authority {@code ROLE_ADMIN}. Realm role {@code admin} (and any other role) is
 * deliberately ignored — Keycloak's built-in superuser role does not unlock the chat admin.
 */
class KeycloakRolesConverterTest {

    private static Jwt jwtWith(List<String> realmRoles) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("sub-1")
                .claim("preferred_username", "alice")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60));
        if (realmRoles != null) {
            builder.claim("realm_access", Map.of("roles", realmRoles));
        }
        return builder.build();
    }

    private static boolean hasRoleAdmin(Jwt jwt) {
        var token = new KeycloakRolesConverter().convert(jwt);
        return token.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    @Test
    void chatAdminGrantsRoleAdmin() {
        assertThat(hasRoleAdmin(jwtWith(List.of("ichat-admin")))).isTrue();
    }

    @Test
    void keycloakAdminAloneDoesNotGrantRoleAdmin() {
        assertThat(hasRoleAdmin(jwtWith(List.of("admin")))).isFalse();
    }

    @Test
    void bothRolesStillGrantRoleAdmin() {
        // Even when 'admin' is on the JWT alongside 'ichat-admin', the ichat-admin entry is what counts.
        assertThat(hasRoleAdmin(jwtWith(List.of("admin", "ichat-admin", "user")))).isTrue();
    }

    @Test
    void noRealmRolesNoAdmin() {
        assertThat(hasRoleAdmin(jwtWith(List.of()))).isFalse();
        assertThat(hasRoleAdmin(jwtWith(null))).isFalse();
    }

    @Test
    void onlyUnrelatedRolesNoAdmin() {
        assertThat(hasRoleAdmin(jwtWith(List.of("user", "billing-admin", "support")))).isFalse();
    }
}
