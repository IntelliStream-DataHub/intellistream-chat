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

package ai.intellistream.chat.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Pushes account suspension through to Keycloak: disables the account at the identity provider and
 * terminates its sessions.
 *
 * <p><b>Why this exists.</b> Suspending an account locally stops an authenticated principal from
 * acting inside this app, but it does nothing at the IdP — the account can still log in and mint
 * fresh tokens, and a second service trusting the same realm would still accept them. Disabling in
 * Keycloak is the real lock: Keycloak re-checks the user on every login and every refresh, so a
 * disabled account cannot obtain a new token by any route. Neither half is sufficient alone. The
 * local flag is what takes effect <em>immediately</em> — an already-issued access token is a signed
 * JWT that stays cryptographically valid until it expires, and no IdP-side action recalls it —
 * while the Keycloak write-through is what stops the account coming back a few minutes later.
 * {@link #logoutAllSessions} then closes the gap between the two by killing live sessions now
 * rather than at their next refresh.
 *
 * <p><b>Optional and off by default.</b> The write-through needs a Keycloak service account with
 * user-management rights, which no existing deployment and none of the bundled dev realm has.
 * Requiring it would break every one of them, so it is gated on
 * {@code ichat.moderation.keycloak-writethrough.enabled} (default {@code false}). The bean always
 * exists — callers can inject it unconditionally — and when the flag is off every method returns
 * {@link Outcome#NOT_CONFIGURED} without touching the network or requiring any Keycloak config.
 *
 * <p><b>Failure is reported, never thrown.</b> An administrator pressing "ban" must not be blocked
 * by an IdP problem: the local suspension is the part that always works, and it stands on its own.
 * So every call returns a {@link Result} instead of propagating an exception, and a caller must use
 * it — the honest report is "suspended locally; Keycloak not updated", not silence. Failures are
 * also logged at ERROR with the reason and a remediation hint, because a write-through that has
 * quietly stopped working is indistinguishable from one that is working unless somebody says so.
 *
 * <h2>What an operator must configure in Keycloak</h2>
 * Against the realm this app authenticates to (the realm in {@code KEYCLOAK_ISSUER_URI}, by default
 * {@code ichat-realm}), on the app's own OIDC client (by default {@code ichat-client}):
 * <ol>
 *   <li><b>Clients → {@code ichat-client} → Settings → Capability config:</b> <i>Client
 *       authentication</i> must be <b>On</b> (the client is confidential — it already is; a public
 *       client cannot use the client-credentials grant at all) and <b>Service accounts roles</b>
 *       must be enabled ({@code serviceAccountsEnabled: true} in a realm export). Without this the
 *       token request fails with HTTP 400 {@code unauthorized_client}.</li>
 *   <li><b>Clients → {@code ichat-client} → Service accounts roles → Assign role → Filter by
 *       clients:</b> grant the <b>{@code realm-management}</b> client role
 *       <b>{@code manage-users}</b>. That single role is enough — it covers reading a user, updating
 *       one, and logging out their sessions. {@code view-users} alone is <em>not</em> enough (reads
 *       succeed, the update returns 403), and no other role is needed. Note the roles live on the
 *       {@code realm-management} client of that same realm; the {@code <realm>-realm} client in the
 *       {@code master} realm is the equivalent only if you administer the realm from {@code master},
 *       which this client does not.</li>
 *   <li><b>The app</b> then needs {@code ichat.moderation.keycloak-writethrough.enabled=true}
 *       (env {@code ICHAT_MODERATION_KEYCLOAK_WRITETHROUGH_ENABLED=true}). Credentials and the realm
 *       URL are reused from the existing OIDC client configuration — {@code KEYCLOAK_ISSUER_URI},
 *       {@code KEYCLOAK_CLIENT_ID}, {@code KEYCLOAK_CLIENT_SECRET} — read through their canonical
 *       {@code spring.security.oauth2.*} property names, so a Vault-backed deployment
 *       ({@code VaultEnvironmentPostProcessor}) picks up the same overridden values with no extra
 *       secret to manage.</li>
 * </ol>
 * Turning the flag on without step 1 or 2 does not break the app: bans still apply locally, and each
 * attempt logs the exact missing piece.
 *
 * <h2>Hazards worth knowing</h2>
 * <ul>
 *   <li><b>Offline tokens survive a logout.</b> The admin logout endpoint ends online sessions;
 *       offline sessions (granted via the {@code offline_access} scope) are not in its scope. This
 *       app never requests that scope, but another client in the same realm might. The account being
 *       <em>disabled</em> is what covers that case, since refreshing an offline token re-checks the
 *       user — which is one more reason not to ship the logout without the disable.</li>
 *   <li><b>Disabling is realm-wide.</b> It locks the account out of every client in the realm, not
 *       just this app. That is usually what "ban" means to the person pressing the button, but it is
 *       worth saying out loud if the realm is shared with unrelated services.</li>
 *   <li><b>This is not the authorization check.</b> Nothing in this class is consulted on a request
 *       path. The app's own suspension state remains the gate; this only propagates it.</li>
 * </ul>
 *
 * @see <a href="https://www.keycloak.org/docs-api/latest/rest-api/">Keycloak Admin REST API</a>
 */
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    /**
     * Renew this far ahead of the token's stated expiry. Covers the flight time of the request the
     * token is about to be used on plus any clock disagreement between us and Keycloak; without it,
     * a token fetched at the last moment can be rejected the instant it is presented.
     */
    private static final long RENEWAL_SKEW_NANOS = Duration.ofSeconds(30).toNanos();

    private final boolean configured;

    /** Realm token endpoint. Null when the write-through is disabled. */
    private final String tokenUrl;
    /** Admin API users collection, e.g. {@code https://sso/admin/realms/ichat-realm/users}. */
    private final String usersUrl;
    /** Pre-computed {@code Authorization: Basic …} for the token request. Never logged. */
    private final String clientBasicAuth;
    private final RestClient http;

    /**
     * Monotonic time source, not wall-clock: an NTP step backwards must not silently extend a
     * cached token's life. Overridable so the caching tests don't have to sleep.
     */
    private final LongSupplier nanoTime;

    private final AtomicReference<CachedToken> token = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    // Two constructors exist (this one and the package-private seam the tests use), so Spring
    // cannot infer which to call and falls back to looking for a no-arg one. Without @Autowired
    // the bean fails to instantiate and takes every integration test's context down with it.
    // The unit tests never caught this because they call the test constructor directly.
    @Autowired
    public KeycloakAdminClient(
            @Value("${ichat.moderation.keycloak-writethrough.enabled:false}") boolean enabled,
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri:}") String issuerUri,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id:}") String clientId,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-secret:}") String clientSecret,
            @Value("${ichat.moderation.keycloak-writethrough.server-url:}") String serverUrlOverride,
            @Value("${ichat.moderation.keycloak-writethrough.timeout-millis:5000}") long timeoutMillis) {
        this(enabled, issuerUri, clientId, clientSecret, serverUrlOverride,
                enabled ? httpClient(timeoutMillis) : null, System::nanoTime);
        if (configured) {
            log.info("Keycloak suspension write-through ENABLED — admin API {} as client '{}'",
                    usersUrl, clientId);
        }
    }

    /**
     * Visible for tests: takes the {@link RestClient} and the clock directly so a unit test can
     * drive the HTTP conversation and the token clock without a Spring context or a live Keycloak.
     */
    KeycloakAdminClient(boolean enabled, String issuerUri, String clientId, String clientSecret,
                        String serverUrlOverride, RestClient http, LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.configured = enabled;
        if (!enabled) {
            // Nothing is validated and nothing is built when the feature is off, so a deployment
            // that never wanted it is not asked for a single extra property.
            this.tokenUrl = null;
            this.usersUrl = null;
            this.clientBasicAuth = null;
            this.http = null;
            return;
        }
        // Fail fast, matching VaultEnvironmentPostProcessor and OidcClientSecretCheck: an operator
        // who turned this on expects bans to reach Keycloak. Degrading to "every ban reports a
        // failure forever" would be discovered by whoever reads the logs, i.e. possibly nobody.
        var issuer = require(issuerUri, "the Keycloak issuer URI"
                + " (KEYCLOAK_ISSUER_URI / spring.security.oauth2.client.provider.keycloak.issuer-uri)");
        var id = require(clientId, "the OIDC client id (KEYCLOAK_CLIENT_ID)");
        var secret = require(clientSecret, "the OIDC client secret (KEYCLOAK_CLIENT_SECRET)");

        // Keycloak's admin API sits *beside* the realm rather than under it:
        // <base>/realms/<realm> for OIDC, <base>/admin/realms/<realm> for administration. Splitting
        // the issuer on its last "/realms/" derives the base rather than assuming one, which keeps
        // working for the legacy "/auth" context path and for a Keycloak mounted under a sub-path.
        var marker = issuer.lastIndexOf("/realms/");
        if (marker < 0) {
            throw new IllegalStateException("ichat.moderation.keycloak-writethrough.enabled=true but the issuer URI '"
                    + issuer + "' is not a Keycloak realm URL (expected .../realms/<realm>)");
        }
        var realm = trimSlashes(issuer.substring(marker + "/realms/".length()));
        // The override exists for the split-hostname deployments Keycloak 26 encourages, where the
        // public issuer is not the address this app can reach the admin API on. It replaces the base
        // for both the token request and the admin calls — they go to the same server.
        var base = blank(serverUrlOverride) ? trimSlashes(issuer.substring(0, marker))
                                            : trimSlashes(serverUrlOverride);

        this.tokenUrl = base + "/realms/" + realm + "/protocol/openid-connect/token";
        this.usersUrl = base + "/admin/realms/" + realm + "/users";
        this.clientBasicAuth = basicAuth(id, secret);
        this.http = Objects.requireNonNull(http, "a RestClient is required when the write-through is enabled");
    }

    /**
     * True when the write-through is switched on and configured. Callers do not need to check this —
     * every method reports {@link Outcome#NOT_CONFIGURED} on its own — but it is useful for deciding
     * what to tell an administrator up front.
     */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Enable or disable the Keycloak account. Disabling blocks new logins and token refresh across
     * the whole realm; it does not, on its own, end sessions that already exist — pair it with
     * {@link #logoutAllSessions}, or use {@link #disableAndLogout}.
     *
     * @param keycloakUserId the Keycloak user id, i.e. the OIDC {@code sub} claim stored on
     *                       {@code User.getSubject()}
     * @return what happened; never null, never throws
     */
    public Result setEnabled(String keycloakUserId, boolean enabled) {
        if (!configured) {
            return Result.NOT_CONFIGURED;
        }
        var userId = trimmed(keycloakUserId);
        var action = enabled ? "enable" : "disable";
        if (userId == null) {
            return failure(action, "<blank>", new IllegalArgumentException(
                    "the account has no Keycloak subject, so there is nothing to " + action));
        }
        try {
            // Read-modify-write rather than PUTting a bare {"enabled": false}. The admin API takes a
            // full user representation, and what a partial one does to fields it omits has varied
            // across Keycloak versions and user-profile configurations — silently dropping a user's
            // attributes as a side effect of a ban is not a risk worth taking to save one GET. What
            // we send back is exactly what Keycloak just gave us, with one field flipped.
            var user = withToken(bearer -> http.get()
                    .uri(usersUrl + "/{id}", userId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JSON_OBJECT));
            if (user == null) {
                return failure(action, userId,
                        new IllegalStateException("Keycloak returned an empty user representation"));
            }
            var updated = new LinkedHashMap<String, Object>(user);
            updated.put("enabled", enabled);
            withToken(bearer -> http.put()
                    .uri(usersUrl + "/{id}", userId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(updated)
                    .retrieve()
                    .toBodilessEntity());
            log.info("Keycloak account {} {}d", userId, action);
            return Result.applied("Keycloak account " + action + "d");
        } catch (RuntimeException e) {
            return failure(action, userId, e);
        }
    }

    /**
     * End every online session the account has, so a suspension takes effect at the IdP now instead
     * of when the current session next refreshes. Offline sessions are out of this endpoint's scope
     * (see the class javadoc); the account being disabled is what covers those.
     *
     * @return what happened; never null, never throws
     */
    public Result logoutAllSessions(String keycloakUserId) {
        if (!configured) {
            return Result.NOT_CONFIGURED;
        }
        var userId = trimmed(keycloakUserId);
        if (userId == null) {
            return failure("log out", "<blank>", new IllegalArgumentException(
                    "the account has no Keycloak subject, so there are no sessions to end"));
        }
        try {
            withToken(bearer -> http.post()
                    .uri(usersUrl + "/{id}/logout", userId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .toBodilessEntity());
            log.info("Keycloak sessions terminated for {}", userId);
            return Result.applied("Keycloak sessions terminated");
        } catch (RuntimeException e) {
            return failure("log out", userId, e);
        }
    }

    /**
     * The suspension pair, in the order that fails safe: disable first, then log out.
     *
     * <p>The reverse order has a hole — logging out an account that is then not disabled invites the
     * user to log straight back in, and the moment between the two is exactly when they would try.
     * Disabling first means a failed logout leaves at most an existing session that cannot be
     * renewed. The logout is still attempted after a failed disable, because ending live sessions is
     * worth something on its own; the result then reports the failure either way.
     *
     * @return {@link Outcome#APPLIED} only when both halves succeeded
     */
    public Result disableAndLogout(String keycloakUserId) {
        var disabled = setEnabled(keycloakUserId, false);
        if (disabled.notConfigured()) {
            return disabled;
        }
        var loggedOut = logoutAllSessions(keycloakUserId);
        if (disabled.applied() && loggedOut.applied()) {
            return Result.applied("Keycloak account disabled and sessions terminated");
        }
        var detail = disabled.failed() && loggedOut.failed()
                ? disabled.detail() + "; " + loggedOut.detail()
                : (disabled.failed() ? disabled.detail() : loggedOut.detail());
        return Result.failed(detail);
    }

    // ---------------------------------------------------------------- token

    /**
     * Run a call with a bearer token, retrying once on 401 with a fresh one.
     *
     * <p>A cached token can be rejected for reasons we cannot see coming — the realm's signing keys
     * rotated, the service-account session was revoked, our clock drifted past Keycloak's. Those all
     * present as a single 401 that a retry fixes. The retry is deliberately limited to 401: a 403 is
     * a missing role, and hammering it twice only doubles the log noise.
     *
     * @param call must be idempotent — every use here is a GET, a full-representation PUT, or a
     *             logout, all of which are safe to repeat
     */
    private <T> T withToken(Function<String, T> call) {
        var bearer = accessToken();
        try {
            return call.apply("Bearer " + bearer);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 401) {
                throw e;
            }
            invalidate(bearer);
            return call.apply("Bearer " + accessToken());
        }
    }

    /**
     * The cached client-credentials token, fetched on demand.
     *
     * <p>The fetch happens under a lock and the cache is re-checked inside it, so a burst of
     * concurrent moderation actions produces one token request rather than one each. Holding a lock
     * across a network call is normally a smell; here the call is bounded by the client's read
     * timeout, moderation actions are rare and human-driven, and the alternative — a stampede of
     * token requests against the IdP — is the worse failure.
     */
    private String accessToken() {
        var cached = token.get();
        if (cached != null && cached.expiresAt() - nanoTime.getAsLong() > 0) {
            return cached.value();
        }
        refreshLock.lock();
        try {
            var current = token.get();
            if (current != null && current.expiresAt() - nanoTime.getAsLong() > 0) {
                return current.value();
            }
            var fresh = fetchToken();
            token.set(fresh);
            return fresh.value();
        } finally {
            refreshLock.unlock();
        }
    }

    private CachedToken fetchToken() {
        var response = http.post()
                .uri(tokenUrl)
                .header(HttpHeaders.AUTHORIZATION, clientBasicAuth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(JSON_OBJECT);
        if (response == null || !(response.get("access_token") instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Keycloak token response carried no access_token");
        }
        return new CachedToken(value, nanoTime.getAsLong() + renewAfterNanos(response.get("expires_in")));
    }

    /**
     * How long to keep a token whose stated lifetime is {@code expiresIn} seconds. Normally that is
     * the lifetime minus the renewal skew; a realm configured with a very short access-token
     * lifespan would go negative, so never keep one for less than half its life either.
     */
    private static long renewAfterNanos(Object expiresIn) {
        long seconds = switch (expiresIn) {
            case Number n -> n.longValue();
            case String s -> parseOr(s, 60);
            // Absent expires_in is out of spec for a token response; assume a short life rather
            // than caching something of unknown validity.
            case null, default -> 60;
        };
        var lifetime = Duration.ofSeconds(Math.max(1, seconds)).toNanos();
        return Math.max(lifetime / 2, lifetime - RENEWAL_SKEW_NANOS);
    }

    /**
     * Drop a token Keycloak just rejected — but only if it is still the cached one. Another thread
     * may already have installed a fresh token, and clearing that would turn one 401 into a second
     * round of token requests.
     */
    private void invalidate(String stale) {
        var current = token.get();
        if (current != null && current.value().equals(stale)) {
            token.compareAndSet(current, null);
        }
    }

    // ------------------------------------------------------------- plumbing

    private Result failure(String action, String userId, RuntimeException e) {
        var hint = remediation(e);
        var detail = "could not " + action + " Keycloak user " + userId + ": " + describe(e)
                + (hint == null ? "" : " — " + hint);
        // ERROR, with the local outcome spelled out, because the operator's first question on
        // seeing this is "did the ban happen at all?" and the answer is yes.
        log.error("KEYCLOAK WRITE-THROUGH FAILED — {}. The local suspension still applies; "
                + "the account remains usable in Keycloak until this is fixed.", detail, e);
        return Result.failed(detail);
    }

    /** Turn the failure into the sentence an operator can act on. */
    private static String remediation(RuntimeException e) {
        if (!(e instanceof RestClientResponseException http)) {
            return e instanceof IllegalArgumentException ? null
                    : "Keycloak could not be reached or answered unintelligibly — check the issuer URI, "
                    + "network reachability and ichat.moderation.keycloak-writethrough.server-url";
        }
        return switch (http.getStatusCode().value()) {
            // Keycloak answers a client-credentials request from a client without a service account
            // with 400 unauthorized_client, so 400 and 401 share the same remedy.
            case 400, 401 -> "the client-credentials grant was refused: enable 'Service accounts roles' "
                    + "on the OIDC client and check KEYCLOAK_CLIENT_SECRET";
            case 403 -> "the service account is missing the realm-management role 'manage-users' "
                    + "(view-users is not enough)";
            case 404 -> "Keycloak has no user with that id — the local account's subject may predate "
                    + "a realm rebuild";
            default -> null;
        };
    }

    /** Exception summary for the operator-facing detail. Never includes the token or the secret. */
    private static String describe(RuntimeException e) {
        if (e instanceof RestClientResponseException http) {
            return "HTTP " + http.getStatusCode().value();
        }
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static RestClient httpClient(long timeoutMillis) {
        // A hung IdP must not hold an admin's ban request open. Both halves of the timeout are set:
        // the JDK client's connect timeout does not bound a connection that opens and then stalls.
        var timeout = Duration.ofMillis(Math.max(250, timeoutMillis));
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * RFC 6749 §2.3.1: the client id and secret are form-urlencoded <em>before</em> being base64'd
     * for HTTP Basic. A secret containing a reserved character otherwise authenticates as something
     * subtly different, which surfaces as a 401 that re-reading the secret never explains.
     */
    private static String basicAuth(String clientId, String clientSecret) {
        var credentials = URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + ":" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseOr(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String require(String value, String what) {
        if (blank(value)) {
            throw new IllegalStateException(
                    "ichat.moderation.keycloak-writethrough.enabled=true but " + what + " is not set");
        }
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimmed(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("/+$", "");
    }

    /** Token plus the monotonic deadline at which we stop trusting it. Immutable on purpose. */
    private record CachedToken(String value, long expiresAt) {}

    /** What a write-through attempt did. */
    public enum Outcome {
        /** Keycloak was updated. */
        APPLIED,
        /** The write-through is switched off — Keycloak was not contacted and nothing is wrong. */
        NOT_CONFIGURED,
        /** Keycloak was contacted and did not do it. The local action still stands. */
        FAILED
    }

    /**
     * The outcome of a write-through, with a detail string fit to show an administrator or store in
     * an audit row.
     *
     * <p>Three states rather than a boolean, because "we did not try" and "we tried and it did not
     * work" call for different words in front of a human: the first is a deployment that never
     * wanted the feature, the second is a security control that is not currently working. Reporting
     * either as success would be a lie, and reporting them as the same failure would send someone
     * looking for a broken Keycloak they never configured.
     */
    public record Result(Outcome outcome, String detail) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(detail, "detail");
        }

        /** The one result a switched-off write-through ever produces. */
        public static final Result NOT_CONFIGURED =
                new Result(Outcome.NOT_CONFIGURED, "Keycloak write-through is not enabled");

        public static Result applied(String detail) {
            return new Result(Outcome.APPLIED, detail);
        }

        public static Result failed(String detail) {
            return new Result(Outcome.FAILED, detail);
        }

        public boolean applied() {
            return outcome == Outcome.APPLIED;
        }

        public boolean notConfigured() {
            return outcome == Outcome.NOT_CONFIGURED;
        }

        /** True only for a real failure — a switched-off write-through is not one. */
        public boolean failed() {
            return outcome == Outcome.FAILED;
        }
    }
}
