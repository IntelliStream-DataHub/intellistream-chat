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

package ai.intellistream.chat.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AppRole branch of {@link VaultEnvironmentPostProcessor} against a scripted stand-in for the
 * three Vault endpoints it touches — login, KV-v2 read, revoke-self — so the credential
 * resolution rules and the mint → read → revoke sequence are pinned without a container.
 * {@code VaultIntegrationIT} covers the same flow against a real Vault.
 */
class VaultAppRoleAuthTest {

    private static final String ROLE_ID = "0f5c8b0e-role";
    private static final String SECRET_ID = "3d1a9c77-secret";
    private static final String MINTED = "hvs.minted-for-this-boot";

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer vault;
    private String uri;
    /** Every request the fake saw, as "METHOD path [X-Vault-Token]" — the sequence is the contract. */
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Object> record = new HashMap<>(Map.of(
            "db.password", "from-vault",
            "keycloak.client-secret", "kc-from-vault"));
    private int loginStatus = 200;

    @BeforeEach
    void startFakeVault() throws IOException {
        vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vault.createContext("/v1/auth/approle/login", this::login);
        vault.createContext("/v1/auth/custom-mount/login", this::login);
        vault.createContext("/v1/intellistream-chat/data/config", this::readKv);
        vault.createContext("/v1/auth/token/revoke-self", this::revoke);
        vault.start();
        uri = "http://127.0.0.1:" + vault.getAddress().getPort();
    }

    @AfterEach
    void stopFakeVault() {
        vault.stop(0);
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    void appRoleMintsAToken_readsTheRecordWithIt_thenRevokesIt() {
        var env = environment(Map.of(
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.secret-id", SECRET_ID));

        processor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-vault");
        assertThat(env.getProperty("spring.security.oauth2.client.registration.keycloak.client-secret"))
                .isEqualTo("kc-from-vault");
        assertThat(env.getPropertySources().contains(VaultEnvironmentPostProcessor.SOURCE_NAME)).isTrue();
        // The order matters: the read must use the minted token, and the revoke must come after.
        assertThat(seen).containsExactly(
                "POST /v1/auth/approle/login",
                "GET /v1/intellistream-chat/data/config " + MINTED,
                "POST /v1/auth/token/revoke-self " + MINTED);
        // The minted token is a local of the post-processor, never a property anyone else can read.
        assertThat(env.getProperty("ichat.vault.token")).isNullOrEmpty();
    }

    @Test
    void theSecretIdCanComeFromAFile_asSystemdLoadCredentialHandsItOver(@TempDir Path dir) throws IOException {
        var file = dir.resolve("bao-secret-id");
        Files.writeString(file, SECRET_ID + "\n");   // editors and `cat >` leave a trailing newline

        var env = environment(Map.of(
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.secret-id-file", file.toString()));

        processor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-vault");
        assertThat(seen.get(0)).isEqualTo("POST /v1/auth/approle/login");
    }

    @Test
    void bothHalvesOfThePairCanComeFromFiles_twoLoadCredentialLines(@TempDir Path dir) throws IOException {
        var roleFile = Files.writeString(dir.resolve("bao-role-id"), ROLE_ID + "\n");
        var secretFile = Files.writeString(dir.resolve("bao-secret-id"), SECRET_ID + "\n");

        var env = environment(Map.of(
                "ichat.vault.role-id-file", roleFile.toString(),
                "ichat.vault.secret-id-file", secretFile.toString()));

        processor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-vault");
        assertThat(seen.get(0)).isEqualTo("POST /v1/auth/approle/login");
    }

    @Test
    void theAppRoleMountCanLiveUnderAnotherName() {
        var env = environment(Map.of(
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.secret-id", SECRET_ID,
                "ichat.vault.approle-path", "custom-mount"));

        processor().postProcessEnvironment(env, null);

        assertThat(seen.get(0)).isEqualTo("POST /v1/auth/custom-mount/login");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-vault");
    }

    @Test
    void aTokenStillWorksAndNeverTouchesTheAppRoleEndpoints() {
        var env = environment(Map.of("ichat.vault.token", MINTED));

        processor().postProcessEnvironment(env, null);

        // An operator-supplied token is theirs to manage: read with it, do not revoke it.
        assertThat(seen).containsExactly("GET /v1/intellistream-chat/data/config " + MINTED);
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-vault");
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void aRefusedLoginCrashesTheBootWithVaultsOwnErrorText() {
        loginStatus = 400;
        var env = environment(Map.of(
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.secret-id", "wrong"));

        assertThatThrownBy(() -> processor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AppRole login")
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("invalid role or secret ID");
        assertThat(seen).containsExactly("POST /v1/auth/approle/login");
        assertThat(env.getPropertySources().contains(VaultEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
    }

    @Test
    void tokenAndAppRoleTogetherAreRefusedAsAmbiguous() {
        var env = environment(Map.of(
                "ichat.vault.token", "t",
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.secret-id", SECRET_ID));

        assertThatThrownBy(() -> processor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both set");
        assertThat(seen).isEmpty();
    }

    @Test
    void enabledWithNoCredentialAtAllIsRefused() {
        var env = environment(Map.of());

        assertThatThrownBy(() -> processor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no credential is set")
                .hasMessageContaining("ichat.vault.token")
                .hasMessageContaining("ichat.vault.role-id");
        assertThat(seen).isEmpty();
    }

    @Test
    void aRoleIdWithoutASecretIdIsRefused() {
        var env = environment(Map.of("ichat.vault.role-id", ROLE_ID));

        assertThatThrownBy(() -> processor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs both");
        assertThat(seen).isEmpty();
    }

    @Test
    void secretIdAndSecretIdFileTogetherAreRefused(@TempDir Path dir) throws IOException {
        var file = Files.writeString(dir.resolve("s"), SECRET_ID);
        var env = environment(Map.of(
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.secret-id", SECRET_ID,
                "ichat.vault.secret-id-file", file.toString()));

        assertThatThrownBy(() -> processor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ichat.vault.secret-id and ichat.vault.secret-id-file are both set");
    }

    @Test
    void roleIdAndRoleIdFileTogetherAreRefused(@TempDir Path dir) throws IOException {
        var file = Files.writeString(dir.resolve("r"), ROLE_ID);
        var env = environment(Map.of(
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.role-id-file", file.toString(),
                "ichat.vault.secret-id", SECRET_ID));

        assertThatThrownBy(() -> processor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ichat.vault.role-id and ichat.vault.role-id-file are both set");
    }

    @Test
    void anUnreadableSecretIdFileIsRefusedByName(@TempDir Path dir) {
        var missing = dir.resolve("does-not-exist");
        var env = environment(Map.of(
                "ichat.vault.role-id", ROLE_ID,
                "ichat.vault.secret-id-file", missing.toString()));

        assertThatThrownBy(() -> processor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not read ichat.vault.secret-id-file")
                .hasMessageContaining(missing.toString());
    }

    /** Boot injects a DeferredLogFactory; here a pass-through one (the supplier's own logger) is enough. */
    private static VaultEnvironmentPostProcessor processor() {
        return new VaultEnvironmentPostProcessor(Supplier::get);
    }

    // ---------------------------------------------------------------- fake Vault

    private StandardEnvironment environment(Map<String, String> extra) {
        var props = new HashMap<String, Object>(Map.of(
                "ichat.vault.enabled", "true",
                "ichat.vault.uri", uri,
                "ichat.vault.path", "intellistream-chat/config"));
        props.putAll(extra);
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }

    @SuppressWarnings("unchecked")
    private void login(HttpExchange ex) throws IOException {
        seen.add("POST " + ex.getRequestURI().getPath());
        var body = JSON.readValue(ex.getRequestBody().readAllBytes(), Map.class);
        boolean pairOk = ROLE_ID.equals(body.get("role_id")) && SECRET_ID.equals(body.get("secret_id"));
        if (loginStatus != 200 || !pairOk) {
            reply(ex, 400, "{\"errors\":[\"invalid role or secret ID\"]}");
            return;
        }
        reply(ex, 200, "{\"auth\":{\"client_token\":\"" + MINTED + "\",\"lease_duration\":300}}");
    }

    private void readKv(HttpExchange ex) throws IOException {
        var token = ex.getRequestHeaders().getFirst("X-Vault-Token");
        seen.add("GET " + ex.getRequestURI().getPath() + " " + token);
        if (!MINTED.equals(token)) {
            reply(ex, 403, "{\"errors\":[\"permission denied\"]}");
            return;
        }
        reply(ex, 200, JSON.writeValueAsString(Map.of("data", Map.of("data", record, "metadata", Map.of()))));
    }

    private void revoke(HttpExchange ex) throws IOException {
        seen.add("POST " + ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("X-Vault-Token"));
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
