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

package ai.intellistream.threadorbit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional secret-backend integration for HashiCorp Vault / OpenBao. When
 * {@code threadorbit.vault.enabled=true} (env {@code THREADORBIT_VAULT_ENABLED}), this runs before
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
 * <p>Direct HTTP against the Vault KV-v2 API rather than {@code spring-vault-core} —
 * the upstream library targets Spring Framework 6 and trips over Spring 7's pruned
 * {@code RestTemplate} constructors. The Vault API surface we need is tiny:
 * {@code GET /v1/secret/data/<path>} with an {@code X-Vault-Token} header.
 *
 * <p>Configuration keys (resolved from env-vars + {@code application.yml} like everything else):
 * <ul>
 *   <li>{@code threadorbit.vault.enabled} — gate. Off → no-op.</li>
 *   <li>{@code threadorbit.vault.uri} — Vault/OpenBao base URL, e.g. {@code http://localhost:8200}.</li>
 *   <li>{@code threadorbit.vault.token} — token credential. AppRole / Kubernetes auth is a
 *       follow-up; tokens cover dev + most single-host deployments.</li>
 *   <li>{@code threadorbit.vault.path} — KV-v2 path. Default {@code threadorbit} maps to
 *       {@code secret/data/threadorbit}; pass {@code mymount/myapp/secrets} to override the
 *       mount.</li>
 * </ul>
 *
 * <p>Vault record fields it copies into the Spring environment:
 * {@code db.username}, {@code db.password}, {@code keycloak.client-id},
 * {@code keycloak.client-secret}, {@code keycloak.issuer-uri}.
 */
public class VaultEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(VaultEnvironmentPostProcessor.class);

    /** Property-source name; surfaces in /actuator/env so operators can confirm Vault overrode the default. */
    public static final String SOURCE_NAME = "threadorbit-vault";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!Boolean.parseBoolean(env.getProperty("threadorbit.vault.enabled", "false"))) {
            return;
        }
        var uri = env.getProperty("threadorbit.vault.uri");
        var token = env.getProperty("threadorbit.vault.token");
        var path = env.getProperty("threadorbit.vault.path", "threadorbit");
        if (uri == null || uri.isBlank() || token == null || token.isBlank()) {
            // Fail loud rather than silently fall back — an operator who set "enabled=true" expects
            // their secrets to come from Vault. A misconfigured URI/token should crash fast at boot
            // so the deploy notices instead of the app quietly running on the dev defaults.
            throw new IllegalStateException(
                    "threadorbit.vault.enabled=true but threadorbit.vault.uri or threadorbit.vault.token is missing");
        }
        log.info("Vault integration active — fetching secrets from {} at path {}", uri, path);

        Map<String, Object> secrets = fetchKvV2(URI.create(uri), token, path);
        var mapped = mapToSpringProperties(secrets);
        if (mapped.isEmpty()) {
            log.warn("Vault path {} contained no recognised keys (looked for: db.username, db.password, "
                    + "keycloak.client-id, keycloak.client-secret, keycloak.issuer-uri); "
                    + "leaving env-var defaults in place.", path);
            return;
        }
        // First in the chain → wins over command-line, env, and YAML for these specific keys.
        env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, mapped));
        log.info("Vault overrode {} properties: {}", mapped.size(), mapped.keySet());
    }

    /**
     * Read the KV-v2 record at {@code <vault>/v1/<mount>/data/<key>}. KV-v2 wraps the actual
     * fields under {@code data.data}, with {@code data.metadata} for versioning info we don't
     * use. Throws {@link IllegalStateException} on transport / 4xx / 5xx so a misconfigured
     * Vault crashes the boot loudly instead of silently falling back to env defaults.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fetchKvV2(URI vaultUri, String token, String path) {
        var slash = path.indexOf('/');
        var mount = slash < 0 ? "secret" : path.substring(0, slash);
        var key   = slash < 0 ? path     : path.substring(slash + 1);
        var url = vaultUri.toString().replaceAll("/+$", "") + "/v1/" + mount + "/data/" + key;
        try {
            var client = RestClient.builder().build();
            Map<String, Object> body = client.get()
                    .uri(url)
                    .header("X-Vault-Token", token)
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON)
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

    /**
     * Translate Vault keys into Spring's canonical property names so the rest of the app
     * doesn't need to know Vault exists. Unknown keys are dropped on purpose — a typo in the
     * Vault record shouldn't end up as a stray property polluting {@code /actuator/env}.
     */
    static Map<String, Object> mapToSpringProperties(Map<String, Object> secrets) {
        var out = new LinkedHashMap<String, Object>();
        copyIfPresent(secrets, "db.username", out, "spring.datasource.username");
        copyIfPresent(secrets, "db.password", out, "spring.datasource.password");
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
