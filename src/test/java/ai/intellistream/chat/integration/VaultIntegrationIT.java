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

package ai.intellistream.chat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;

import ai.intellistream.chat.config.VaultEnvironmentPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage for the optional Vault / OpenBao secret backend. Spins up a real
 * Vault dev-mode container, writes a KV-v2 record with credentials, boots the Spring
 * context with {@code ichat.vault.enabled=true}, and asserts that
 * {@code spring.datasource.password} (and friends) come back from Vault — not from the
 * {@code application.yml} default.
 *
 * <p>OpenBao keeps Vault's HTTP API; testing against the upstream Vault image gives the
 * same wire contract without a separate OpenBao container build.
 *
 * <p>This IT runs against a slim Spring context — no Postgres, no Keycloak — because
 * the only thing under test is the {@link VaultEnvironmentPostProcessor} hook into
 * {@link ConfigurableEnvironment}. {@link DataSourceAutoConfiguration} is excluded so the
 * test boot doesn't try to actually connect to whatever {@code spring.datasource.url}
 * resolves to.
 */
class VaultIntegrationIT {

    private static final String DEV_TOKEN = "test-root-token";
    private static String roleId;
    private static String secretId;

    @SuppressWarnings("resource") // closed in @AfterAll
    private static final VaultContainer<?> VAULT = new VaultContainer<>(
            DockerImageName.parse("hashicorp/vault:1.18"))
            .withVaultToken(DEV_TOKEN);

    @BeforeAll
    static void startVaultAndSeedSecrets() throws Exception {
        VAULT.start();
        // KV-v2 is mounted at /secret by default in dev mode.
        VAULT.execInContainer("vault", "kv", "put", "secret/intellistream-chat",
                "db.username=vault-user",
                "db.password=vault-pass",
                "keycloak.client-id=vault-client",
                "keycloak.client-secret=vault-client-secret",
                "keycloak.issuer-uri=https://vault-issuer.example/realms/ichat-realm");

        // AppRole: a policy that can read exactly that record, a role bound to it, and the pair
        // the app will log in with — the same four steps QUICKSTART-MANUAL.md walks through.
        VAULT.execInContainer("sh", "-c",
                "printf 'path \"secret/data/intellistream-chat\" { capabilities = [\"read\"] }' "
                        + "| vault policy write ichat-read -");
        VAULT.execInContainer("vault", "auth", "enable", "approle");
        VAULT.execInContainer("vault", "write", "auth/approle/role/ichat",
                "token_policies=ichat-read", "token_ttl=5m", "token_max_ttl=5m");
        roleId = VAULT.execInContainer("vault", "read", "-field=role_id",
                "auth/approle/role/ichat/role-id").getStdout().strip();
        secretId = VAULT.execInContainer("vault", "write", "-f", "-field=secret_id",
                "auth/approle/role/ichat/secret-id").getStdout().strip();
        assertThat(roleId).isNotEmpty();
        assertThat(secretId).isNotEmpty();
    }

    @AfterAll
    static void stopVault() {
        VAULT.stop();
    }

    @Test
    void vaultValuesOverrideApplicationYmlDefaults() {
        try (ConfigurableApplicationContext ctx = bootWithVault(true)) {
            ConfigurableEnvironment env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("vault-user");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("vault-pass");
            assertThat(env.getProperty("spring.security.oauth2.client.registration.keycloak.client-id"))
                    .isEqualTo("vault-client");
            assertThat(env.getProperty("spring.security.oauth2.client.registration.keycloak.client-secret"))
                    .isEqualTo("vault-client-secret");
            // The Vault record carries one issuer-uri; it must end up in BOTH Spring slots so
            // the OIDC client and the JWT resource server agree.
            assertThat(env.getProperty("spring.security.oauth2.client.provider.keycloak.issuer-uri"))
                    .isEqualTo("https://vault-issuer.example/realms/ichat-realm");
            assertThat(env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                    .isEqualTo("https://vault-issuer.example/realms/ichat-realm");
            // The intellistream-vault property source registered itself by name so an operator can
            // verify the override fired by hitting /actuator/env.
            assertThat(env.getPropertySources().contains(VaultEnvironmentPostProcessor.SOURCE_NAME)).isTrue();
        }
    }

    @Test
    void appRoleLoginMintsAToken_readsTheRecord_andRevokesTheTokenAgain() throws Exception {
        int accessorsBefore = tokenAccessorCount();

        var app = new SpringApplication(VaultTestApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run(
                "--ichat.vault.enabled=true",
                "--ichat.vault.uri=" + VAULT.getHttpHostAddress(),
                "--ichat.vault.role-id=" + roleId,
                "--ichat.vault.secret-id=" + secretId,
                "--ichat.vault.path=intellistream-chat")) {
            ConfigurableEnvironment env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("vault-pass");
            assertThat(env.getProperty("spring.security.oauth2.client.registration.keycloak.client-secret"))
                    .isEqualTo("vault-client-secret");
            assertThat(env.getPropertySources().contains(VaultEnvironmentPostProcessor.SOURCE_NAME)).isTrue();
            // No token property anywhere: the minted one lived and died inside the post-processor.
            assertThat(env.getProperty("ichat.vault.token")).isNullOrEmpty();
        }

        // The login created a token; revoke-self removed it again, so Vault's accessor list is
        // back where it started. A leak here would grow by one every boot until the TTL caught up.
        assertThat(tokenAccessorCount()).isEqualTo(accessorsBefore);
    }

    @Test
    void aWrongSecretIdIsRefusedWithVaultsOwnMessage() {
        assertThatThrownBy(() -> {
            var app = new SpringApplication(VaultTestApp.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.run(
                    "--ichat.vault.enabled=true",
                    "--ichat.vault.uri=" + VAULT.getHttpHostAddress(),
                    "--ichat.vault.role-id=" + roleId,
                    "--ichat.vault.secret-id=not-the-secret-id",
                    "--ichat.vault.path=intellistream-chat"
            ).close();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AppRole login")
          .hasMessageContaining("invalid");
    }

    private static int tokenAccessorCount() throws Exception {
        var out = VAULT.execInContainer("vault", "list", "-format=json", "auth/token/accessors").getStdout();
        return new ObjectMapper().readValue(out, List.class).size();
    }

    @Test
    void offByDefaultLeavesEnvironmentUnchanged() {
        // Same boot, flag flipped off. The Vault EnvironmentPostProcessor must short-circuit
        // and never even contact the container — proving the "optional" contract.
        try (ConfigurableApplicationContext ctx = bootWithVault(false)) {
            ConfigurableEnvironment env = ctx.getEnvironment();
            // No vault-sourced properties: spring.datasource.password should resolve to the
            // application.yml default ("ichat_role"), not "vault-pass".
            assertThat(env.getProperty("spring.datasource.password")).isNotEqualTo("vault-pass");
            assertThat(env.getPropertySources().contains(VaultEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
        }
    }

    @Test
    void enabledWithoutUriOrTokenFailsFastSoMisconfigsAreLoud() {
        // Empty token in particular is the most likely operator mistake. Boot must throw
        // rather than silently fall back to the env-var defaults — those defaults are usually
        // the dev-only weak credentials, and silently using them in a "vault-enabled" deploy
        // would be a security bug.
        assertThatThrownBy(() -> {
            var app = new SpringApplication(VaultTestApp.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.run(
                    "--ichat.vault.enabled=true",
                    "--ichat.vault.uri=" + VAULT.getHttpHostAddress(),
                    "--ichat.vault.token=",
                    "--ichat.vault.path=intellistream-chat"
            ).close();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ichat.vault.enabled=true but no credential is set");
    }

    /** Common boot helper — slim context with no DB/JPA so we don't need a Postgres around. */
    private ConfigurableApplicationContext bootWithVault(boolean enabled) {
        var app = new SpringApplication(VaultTestApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run(
                "--ichat.vault.enabled=" + enabled,
                "--ichat.vault.uri=" + VAULT.getHttpHostAddress(),
                "--ichat.vault.token=" + DEV_TOKEN,
                "--ichat.vault.path=intellistream-chat"
        );
    }

    /**
     * Bare Spring config — no @SpringBootApplication so we avoid pulling in
     * {@link IntegrationTestApplication} via package scanning. The Vault
     * EnvironmentPostProcessor still fires because it's registered through
     * {@code META-INF/spring/.EnvironmentPostProcessor.imports} (boot-internal
     * infrastructure that runs before any auto-config scan).
     */
    @Configuration
    static class VaultTestApp {
    }
}
