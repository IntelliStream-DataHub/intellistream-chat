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

package ai.intellistream.chat.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java check on the Vault → Spring property mapping. The IT layer ({@code VaultIntegrationIT})
 * exercises the actual HTTP round-trip; this one nails down the contract so a refactor of
 * {@code mapToSpringProperties} can't silently rename keys without a failing test.
 */
class VaultEnvironmentPostProcessorTest {

    @Test
    void translatesEveryKnownVaultKeyToItsSpringEquivalent() {
        var vaultRecord = Map.<String, Object>of(
                "db.username", "rad",
                "db.password", "s3cret",
                "keycloak.client-id", "my-client",
                "keycloak.client-secret", "my-secret",
                "keycloak.issuer-uri", "https://kc.example.com/realms/ichat-realm"
        );

        var mapped = VaultEnvironmentPostProcessor.mapToSpringPropertiesForTesting(vaultRecord);

        assertThat(mapped)
                .containsEntry("spring.datasource.username", "rad")
                .containsEntry("spring.datasource.password", "s3cret")
                .containsEntry("spring.security.oauth2.client.registration.keycloak.client-id", "my-client")
                .containsEntry("spring.security.oauth2.client.registration.keycloak.client-secret", "my-secret")
                // issuer-uri is mirrored into both Spring properties so client + resource-server
                // stay aligned without the Vault record carrying it twice.
                .containsEntry("spring.security.oauth2.client.provider.keycloak.issuer-uri",
                        "https://kc.example.com/realms/ichat-realm")
                .containsEntry("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "https://kc.example.com/realms/ichat-realm");
    }

    @Test
    void unknownVaultKeysAreDroppedRatherThanLeakingIntoTheEnvironment() {
        var record = new LinkedHashMap<String, Object>();
        record.put("db.username", "rad");
        record.put("rogue.key", "should-be-dropped");
        record.put("api_token", "also-dropped");

        var mapped = VaultEnvironmentPostProcessor.mapToSpringPropertiesForTesting(record);

        // Only the recognised key shows up; unmapped Vault keys never become Spring properties
        // (a typo in Vault should fail loudly when the dependent service can't resolve its
        // expected property — not silently slip a stray property into /actuator/env).
        assertThat(mapped).hasSize(1).containsOnlyKeys("spring.datasource.username");
    }

    @Test
    void partialVaultRecordOnlyMapsThePresentKeys() {
        var record = Map.<String, Object>of("db.password", "p");

        var mapped = VaultEnvironmentPostProcessor.mapToSpringPropertiesForTesting(record);

        // Operators who put db creds in Vault but leave Keycloak in env-vars should get exactly
        // that — db.password mapped, Keycloak slots untouched (so the application.yml defaults
        // continue to drive those). No null/empty values ending up in the property source.
        assertThat(mapped).hasSize(1).containsOnlyKeys("spring.datasource.password");
    }

    @Test
    void emptyVaultRecordReturnsEmptyMapping() {
        assertThat(VaultEnvironmentPostProcessor.mapToSpringPropertiesForTesting(Map.of())).isEmpty();
    }
}
