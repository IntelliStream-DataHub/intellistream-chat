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

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.log.LogMessage;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional secret-backend integration for HashiCorp Vault / OpenBao. When
 * {@code ichat.vault.enabled=true} (env {@code ICHAT_VAULT_ENABLED}), this runs before
 * any auto-configuration reads {@code spring.datasource.*} or the OAuth client config — fetches
 * a single KV-v2 record from Vault / OpenBao and pushes the values into the
 * {@link ConfigurableEnvironment} as a high-priority {@link MapPropertySource}, so
 * {@code @Value} / {@code @ConfigurationProperties} see them as if they came from the
 * environment.
 *
 * <p>When the flag is missing or false this is a no-op — env-var → {@code application.yml}
 * default chain still works and you don't need a Vault server. That's the "optional"
 * contract.
 *
 * <p>Wired through {@code META-INF/spring.factories} so it's picked up before
 * {@code @Configuration} classes are processed (a regular bean would be too late:
 * {@code DataSourceAutoConfiguration} reads {@code spring.datasource.password} as the context
 * refreshes, before our bean would be available to mutate properties).
 *
 * <p><b>Logging goes through the {@link DeferredLogFactory} Boot injects</b>, not a static SLF4J
 * logger. An environment post-processor runs before the logging system is initialised, and
 * Logback suppresses everything until then — a plain logger's output from here is silently
 * dropped, which is how "Vault integration active" managed to be an INFO line nobody ever saw in
 * a journal. Deferred logs are replayed once logging is up. This is also why the class has one
 * constructor and it takes the factory: {@code SpringFactoriesLoader} only injects it into a
 * single public constructor.
 *
 * <p>Direct HTTP against the Vault KV-v2 API rather than {@code spring-vault-core} —
 * the upstream library targets Spring Framework 6 and trips over Spring 7's pruned
 * {@code RestTemplate} constructors. The Vault API surface we need is tiny:
 * {@code GET /v1/<mount>/data/<key>} with an {@code X-Vault-Token} header, plus — for AppRole —
 * {@code POST /v1/auth/<approle>/login} to mint that token and
 * {@code POST /v1/auth/token/revoke-self} to retire it once the record is read.
 *
 * <p>Configuration keys (resolved from env-vars + {@code application.yml} like everything else):
 * <ul>
 *   <li>{@code ichat.vault.enabled} — gate. Off → no-op.</li>
 *   <li>{@code ichat.vault.uri} — Vault/OpenBao base URL, e.g. {@code http://localhost:8200}.</li>
 *   <li>{@code ichat.vault.path} — KV-v2 path. Default {@code intellistream-chat} maps to
 *       {@code secret/data/intellistream-chat}; pass {@code mymount/myapp/secrets} to override the
 *       mount.</li>
 *   <li>Exactly one credential, either:
 *     <ul>
 *       <li>{@code ichat.vault.token} — a token the operator already holds; or</li>
 *       <li>{@code ichat.vault.role-id} plus {@code ichat.vault.secret-id} — an AppRole. Each
 *           half may instead come from a file ({@code ichat.vault.role-id-file},
 *           {@code ichat.vault.secret-id-file}), which is how systemd {@code LoadCredential=}
 *           hands a secret to a service. The token is minted at boot, used for the one read, and
 *           revoked; it never exists outside this method. {@code ichat.vault.approle-path}
 *           (default {@code approle}) is the auth mount, for installations that enabled AppRole
 *           under another name.</li>
 *     </ul>
 *     Setting both a token and an AppRole is refused as ambiguous, and so is setting neither
 *     while enabled — a misconfiguration crashes the boot rather than quietly running on the
 *     env-var defaults.</li>
 * </ul>
 *
 * <p>Vault record fields it copies into the Spring environment:
 * {@code db.url}, {@code db.username}, {@code db.password}, {@code db.replica-enabled},
 * {@code db.replica-url}, {@code db.replica-username}, {@code db.replica-password},
 * {@code keycloak.client-id}, {@code keycloak.client-secret}, {@code keycloak.issuer-uri}.
 * The four {@code db.replica-*} keys configure the optional read replica
 * ({@link ReadReplicaDataSourceConfig}); like everything else here they are optional, and a
 * record that omits them leaves the env-var chain in charge.
 */
public class VaultEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private final Log log;

    public VaultEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(VaultEnvironmentPostProcessor.class);
    }

    /** Property-source name; surfaces in /actuator/env so operators can confirm Vault overrode the default. */
    public static final String SOURCE_NAME = "intellistream-vault";

    /** The record fields that mean something to the app, in the order {@link #mapToSpringProperties} reads them. */
    static final List<String> RECOGNISED_KEYS = List.of(
            "db.url", "db.username", "db.password",
            "db.replica-enabled", "db.replica-url", "db.replica-username", "db.replica-password",
            "keycloak.client-id", "keycloak.client-secret", "keycloak.issuer-uri");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!Boolean.parseBoolean(env.getProperty("ichat.vault.enabled", "false"))) {
            return;
        }
        var uri = env.getProperty("ichat.vault.uri");
        var path = env.getProperty("ichat.vault.path", "intellistream-chat");
        if (isBlank(uri)) {
            // Fail loud rather than silently fall back — an operator who set "enabled=true" expects
            // their secrets to come from Vault. A misconfigured URI should crash fast at boot so
            // the deploy notices instead of the app quietly running on the dev defaults.
            throw new IllegalStateException("ichat.vault.enabled=true but ichat.vault.uri is missing");
        }
        var credential = Credential.resolve(env);
        var base = URI.create(uri);
        var client = RestClient.builder().build();

        // Two INFO lines an operator can grep for: this one says the backend is on and how it will
        // authenticate; the one after the fetch says the record was read and which keys it held.
        log.info(LogMessage.format("Vault / OpenBao secret backend enabled: uri=%s, path=%s, auth=%s", uri, path,
                credential.token != null ? "token"
                        : "AppRole (mount '" + credential.approlePath + "', role-id " + credential.roleId + ")"));

        Map<String, Object> secrets;
        if (credential.token != null) {
            secrets = fetchKvV2(client, base, credential.token, path);
        } else {
            var minted = loginAppRole(client, base, credential.approlePath, credential.roleId, credential.secretId);
            try {
                secrets = fetchKvV2(client, base, minted, path);
            } finally {
                // The token was minted for this one read. Retire it rather than let it idle out its
                // TTL; best effort, because a token that could not be revoked still expires and the
                // secrets are already in hand — failing the boot over hygiene would be backwards.
                revokeSelf(client, base, minted);
            }
        }

        var found = RECOGNISED_KEYS.stream().filter(secrets::containsKey).toList();
        var ignored = secrets.keySet().stream().filter(k -> !RECOGNISED_KEYS.contains(k)).sorted().toList();
        var mapped = mapToSpringProperties(secrets);
        if (mapped.isEmpty()) {
            log.warn(LogMessage.format("Vault path %s contained none of the recognised keys %s%s; "
                    + "leaving env-var defaults in place.", path, RECOGNISED_KEYS,
                    ignored.isEmpty() ? "" : " (ignored: " + ignored + ")"));
            return;
        }
        // First in the chain → wins over command-line, env, and YAML for these specific keys.
        env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, mapped));
        // Key names only, never values. The ignored list is how a typo in the record shows up —
        // "db.passwd" is silently nothing otherwise.
        log.info(LogMessage.format("Vault configuration loaded successfully: %d recognised key(s) at path %s %s "
                + "→ overriding %s%s", found.size(), path, found, mapped.keySet(),
                ignored.isEmpty() ? "" : "; ignored " + ignored.size() + " unrecognised key(s) " + ignored));
    }

    /**
     * Which of the two credential shapes the environment describes, validated so that every
     * misconfiguration has a message naming the property to fix. Exactly one of {@code token} or
     * the AppRole trio is populated on a returned instance.
     */
    record Credential(String token, String roleId, String secretId, String approlePath) {

        static Credential resolve(ConfigurableEnvironment env) {
            var token = env.getProperty("ichat.vault.token");
            var roleId = env.getProperty("ichat.vault.role-id");
            var roleIdFile = env.getProperty("ichat.vault.role-id-file");
            var secretId = env.getProperty("ichat.vault.secret-id");
            var secretIdFile = env.getProperty("ichat.vault.secret-id-file");
            var approlePath = env.getProperty("ichat.vault.approle-path", "approle");

            boolean hasToken = !isBlank(token);
            boolean hasAppRole = !isBlank(roleId) || !isBlank(roleIdFile)
                    || !isBlank(secretId) || !isBlank(secretIdFile);
            if (hasToken && hasAppRole) {
                throw new IllegalStateException("ichat.vault.token and an AppRole (ichat.vault.role-id / "
                        + "secret-id, or their -file variants) are both set; configure one credential, not two");
            }
            if (!hasToken && !hasAppRole) {
                throw new IllegalStateException("ichat.vault.enabled=true but no credential is set: "
                        + "either ichat.vault.token, or ichat.vault.role-id with ichat.vault.secret-id "
                        + "(each may be given as a -file instead)");
            }
            if (hasToken) {
                return new Credential(token, null, null, null);
            }
            roleId = valueOrFile("ichat.vault.role-id", roleId, roleIdFile);
            secretId = valueOrFile("ichat.vault.secret-id", secretId, secretIdFile);
            if (isBlank(roleId) || isBlank(secretId)) {
                throw new IllegalStateException("AppRole auth needs both ichat.vault.role-id and "
                        + "ichat.vault.secret-id (or their -file variants)");
            }
            return new Credential(null, roleId, secretId, approlePath.replaceAll("^/+|/+$", ""));
        }

        /**
         * A file rather than a value is how systemd's {@code LoadCredential=} hands a secret to a
         * service, so the pair can stay root-only on disk instead of sitting in the environment
         * file. Value and file together is refused as ambiguous. Trailing whitespace is stripped
         * because editors and {@code cat >} add a newline.
         */
        private static String valueOrFile(String property, String value, String file) {
            if (isBlank(file)) {
                return value;
            }
            if (!isBlank(value)) {
                throw new IllegalStateException(property + " and " + property + "-file are both set; "
                        + "configure one");
            }
            try {
                var read = Files.readString(Path.of(file)).strip();
                if (read.isEmpty()) {
                    throw new IllegalStateException(property + "-file " + file + " is empty");
                }
                return read;
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + property + "-file " + file
                        + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * AppRole login: {@code POST /v1/auth/<approle>/login} with the pair, answering
     * {@code auth.client_token}. A refused login is an {@link IllegalStateException} that carries
     * Vault's own error text, since "invalid role or secret ID" is the message an operator needs.
     */
    @SuppressWarnings("unchecked")
    private static String loginAppRole(RestClient client, URI vaultUri, String approlePath,
                                       String roleId, String secretId) {
        var url = baseUrl(vaultUri) + "/v1/auth/" + approlePath + "/login";
        try {
            Map<String, Object> body = client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("role_id", roleId, "secret_id", secretId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            var auth = body == null ? null : (Map<String, Object>) body.get("auth");
            var token = auth == null ? null : auth.get("client_token");
            if (token == null || token.toString().isBlank()) {
                throw new IllegalStateException("AppRole login at " + url + " returned no client_token");
            }
            return token.toString();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("AppRole login at " + url + " was refused (HTTP "
                    + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) throw e;
            throw new IllegalStateException("AppRole login at " + url + " failed: " + e.getMessage(), e);
        }
    }

    /** Best-effort {@code POST /v1/auth/token/revoke-self}; a failure is logged, never thrown. */
    private void revokeSelf(RestClient client, URI vaultUri, String token) {
        var url = baseUrl(vaultUri) + "/v1/auth/token/revoke-self";
        try {
            client.post().uri(url).header("X-Vault-Token", token).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn(LogMessage.format("Could not revoke the AppRole-minted Vault token (%s); "
                    + "it will expire on its own TTL", e.getMessage()));
        }
    }

    /**
     * Read the KV-v2 record at {@code <vault>/v1/<mount>/data/<key>}. KV-v2 wraps the actual
     * fields under {@code data.data}, with {@code data.metadata} for versioning info we don't
     * use. Throws {@link IllegalStateException} on transport / 4xx / 5xx so a misconfigured
     * Vault crashes the boot loudly instead of silently falling back to env defaults.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fetchKvV2(RestClient client, URI vaultUri, String token, String path) {
        var slash = path.indexOf('/');
        var mount = slash < 0 ? "secret" : path.substring(0, slash);
        var key   = slash < 0 ? path     : path.substring(slash + 1);
        var url = baseUrl(vaultUri) + "/v1/" + mount + "/data/" + key;
        try {
            Map<String, Object> body = client.get()
                    .uri(url)
                    .header("X-Vault-Token", token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (body == null) {
                throw new IllegalStateException("Vault returned an empty body for " + url);
            }
            var data = (Map<String, Object>) body.get("data");
            if (data == null) {
                throw new IllegalStateException("Vault response missing 'data' wrapper at " + url);
            }
            var inner = (Map<String, Object>) data.get("data");
            if (inner == null) {
                throw new IllegalStateException("Vault KV-v2 response missing 'data.data' inner block at " + url);
            }
            return inner;
        } catch (RuntimeException e) {
            // Re-wrap so the bootstrap stack trace clearly says "Vault was the issue".
            if (e instanceof IllegalStateException) throw e;
            throw new IllegalStateException("Could not load secrets from Vault at " + url + ": "
                    + e.getMessage(), e);
        }
    }

    private static String baseUrl(URI vaultUri) {
        return vaultUri.toString().replaceAll("/+$", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Translate Vault keys into Spring's canonical property names so the rest of the app
     * doesn't need to know Vault exists. Unknown keys are dropped on purpose — a typo in the
     * Vault record shouldn't end up as a stray property polluting {@code /actuator/env}.
     */
    static Map<String, Object> mapToSpringProperties(Map<String, Object> secrets) {
        var out = new LinkedHashMap<String, Object>();
        copyIfPresent(secrets, "db.url", out, "spring.datasource.url");
        copyIfPresent(secrets, "db.username", out, "spring.datasource.username");
        copyIfPresent(secrets, "db.password", out, "spring.datasource.password");
        // Optional read replica (ReadReplicaDataSourceConfig). The whole record is mappable, the
        // enabled flag included, so a deployment can be switched onto a replica by editing Vault
        // and restarting — the alternative is a topology decision split across two places.
        // The flag is read by @ConditionalOnProperty, which evaluates well after this runs.
        copyIfPresent(secrets, "db.replica-enabled", out, "ichat.datasource.replica.enabled");
        copyIfPresent(secrets, "db.replica-url", out, "ichat.datasource.replica.url");
        copyIfPresent(secrets, "db.replica-username", out, "ichat.datasource.replica.username");
        copyIfPresent(secrets, "db.replica-password", out, "ichat.datasource.replica.password");
        copyIfPresent(secrets, "keycloak.client-id",
                out, "spring.security.oauth2.client.registration.keycloak.client-id");
        copyIfPresent(secrets, "keycloak.client-secret",
                out, "spring.security.oauth2.client.registration.keycloak.client-secret");
        // The issuer-uri appears in TWO Spring locations — the OIDC client (browser login) and
        // the resource server (JWT validation on /api and /ws). Both have to agree, so we
        // mirror the single Vault value into both.
        if (secrets.containsKey("keycloak.issuer-uri")) {
            var issuer = secrets.get("keycloak.issuer-uri");
            out.put("spring.security.oauth2.client.provider.keycloak.issuer-uri", issuer);
            out.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer);
        }
        return out;
    }

    private static void copyIfPresent(Map<String, Object> src, String srcKey,
                                      Map<String, Object> dst, String dstKey) {
        if (src.containsKey(srcKey)) dst.put(dstKey, src.get(srcKey));
    }

    /** Visible for tests. */
    static Map<String, Object> mapToSpringPropertiesForTesting(Map<String, Object> secrets) {
        return new HashMap<>(mapToSpringProperties(secrets));
    }
}
