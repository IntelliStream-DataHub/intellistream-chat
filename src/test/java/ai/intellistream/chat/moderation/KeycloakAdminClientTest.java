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

package ai.intellistream.chat.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * This client sits between a moderation action and an identity provider that may be misconfigured,
 * unreachable, or simply not there, so the tests that matter are about what it does when Keycloak
 * does not cooperate — and about it staying completely silent when nobody asked for it.
 *
 * <p>The HTTP conversation is driven by {@link MockRestServiceServer} rather than a live Keycloak:
 * no Spring context, no container, and the request bodies are asserted exactly, which is the only
 * way to pin down details like "the ban must not drop the user's other attributes".
 */
class KeycloakAdminClientTest {

    private static final String ISSUER = "http://kc.test:8081/realms/ichat-realm";
    private static final String TOKEN_URL = ISSUER + "/protocol/openid-connect/token";
    private static final String USERS_URL = "http://kc.test:8081/admin/realms/ichat-realm/users";
    private static final String USER_URL = USERS_URL + "/u-1";

    /** A user representation shaped like Keycloak's, with fields a careless update would lose. */
    private static final String USER_JSON = """
            {"id":"u-1","username":"bob","enabled":true,"emailVerified":true,
             "attributes":{"upload_cap_bytes":["104857600"]},"requiredActions":[]}
            """;

    private final AtomicLong now = new AtomicLong();
    private final LongSupplier clock = mock(LongSupplier.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    KeycloakAdminClientTest() {
        when(clock.getAsLong()).thenAnswer(invocation -> now.get());
    }

    private KeycloakAdminClient enabledClient() {
        return new KeycloakAdminClient(true, ISSUER, "ichat-client", "s3cret", "", builder.build(), clock);
    }

    private void expectTokenRequest(String token, int expiresInSeconds) {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess("""
                        {"access_token":"%s","expires_in":%d,"token_type":"Bearer"}
                        """.formatted(token, expiresInSeconds), MediaType.APPLICATION_JSON));
    }

    private static void advance(AtomicLong clockNanos, Duration by) {
        clockNanos.addAndGet(by.toNanos());
    }

    // ------------------------------------------------------- off by default

    @Test
    void offByDefaultMeansKeycloakIsNeverContacted() {
        // The gate is the whole point: no deployment that hasn't set up a service account should
        // see a single request leave the process. An unexpected call fails the mock server.
        var client = new KeycloakAdminClient(false, "", "", "", "", builder.build(), clock);

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.setEnabled("u-1", false).outcome())
                .isEqualTo(KeycloakAdminClient.Outcome.NOT_CONFIGURED);
        assertThat(client.logoutAllSessions("u-1").notConfigured()).isTrue();
        assertThat(client.disableAndLogout("u-1").notConfigured()).isTrue();

        server.verify();
    }

    @Test
    void notConfiguredIsNotAFailure() {
        // BanService has to be able to tell "nobody wanted this" from "this is broken", so the two
        // must not collapse into one boolean.
        var result = new KeycloakAdminClient(false, "", "", "", "", null, clock).setEnabled("u-1", false);

        assertThat(result.notConfigured()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.applied()).isFalse();
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void disabledClientDemandsNoKeycloakConfigurationAtAll() {
        // Constructing it must be free for the deployments that will never use it — otherwise
        // adding this class breaks every existing one at boot.
        assertThatCode(() -> new KeycloakAdminClient(false, "", "", "", "", null, System::nanoTime))
                .doesNotThrowAnyException();
    }

    @Test
    void enablingItWithoutTheConfigurationFailsAtStartup() {
        // The opposite case: an operator who turned it on gets told immediately, rather than
        // discovering months later that every ban has been half-applied.
        assertThatThrownBy(() -> new KeycloakAdminClient(true, "", "ichat-client", "s3cret", "", null, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");

        assertThatThrownBy(() -> new KeycloakAdminClient(true, ISSUER, "ichat-client", "", "", null, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KEYCLOAK_CLIENT_SECRET");

        assertThatThrownBy(() -> new KeycloakAdminClient(
                true, "http://kc.test:8081/not-a-realm", "ichat-client", "s3cret", "", null, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("realm");
    }

    // ------------------------------------------------------- token handling

    @Test
    void fetchesOneTokenAndReusesItAcrossCalls() {
        // One token request per burst of moderation actions, not one per HTTP call: the ordered
        // expectations below fail if a second token request appears anywhere in the sequence.
        expectTokenRequest("token-1", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withNoContent());
        server.expect(requestTo(USER_URL + "/logout")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withNoContent());

        var client = enabledClient();
        assertThat(client.setEnabled("u-1", false).applied()).isTrue();
        assertThat(client.logoutAllSessions("u-1").applied()).isTrue();

        server.verify();
    }

    @Test
    void refetchesTheTokenOnlyOnceItNearsExpiry() {
        // Renewal is early on purpose — a token that expires between our check and Keycloak's is a
        // 401 for no reason — so 269s into a 300s lifetime the cached token must still be used.
        expectTokenRequest("token-1", 300);
        server.expect(requestTo(USER_URL + "/logout"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withNoContent());
        server.expect(requestTo(USER_URL + "/logout"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withNoContent());
        expectTokenRequest("token-2", 300);
        server.expect(requestTo(USER_URL + "/logout"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-2"))
                .andRespond(withNoContent());

        var client = enabledClient();
        client.logoutAllSessions("u-1");
        advance(now, Duration.ofSeconds(269));
        client.logoutAllSessions("u-1");
        advance(now, Duration.ofSeconds(2));
        client.logoutAllSessions("u-1");

        server.verify();
    }

    @Test
    void keepsAShortLivedTokenForAtLeastHalfItsLife() {
        // A realm with a 20s access-token lifespan would otherwise compute a renewal deadline in
        // the past and refetch a token for every single request.
        expectTokenRequest("token-1", 20);
        server.expect(requestTo(USER_URL + "/logout"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withNoContent());
        server.expect(requestTo(USER_URL + "/logout"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withNoContent());

        var client = enabledClient();
        client.logoutAllSessions("u-1");
        advance(now, Duration.ofSeconds(9));
        client.logoutAllSessions("u-1");

        server.verify();
    }

    @Test
    void replacesATokenKeycloakRejectsAndRetriesOnce() {
        // Signing keys rotate and service-account sessions get revoked; both look like one 401 on a
        // token we believed was good. Retrying once with a fresh token is the whole fix.
        expectTokenRequest("stale", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale"))
                .andRespond(withUnauthorizedRequest());
        expectTokenRequest("fresh", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh"))
                .andRespond(withNoContent());

        assertThat(enabledClient().setEnabled("u-1", false).applied()).isTrue();

        server.verify();
    }

    @Test
    void authenticatesWithFormEncodedCredentialsOverBasic() {
        // RFC 6749 §2.3.1: id and secret are form-urlencoded before base64. A secret with a
        // reserved character otherwise authenticates as something subtly different, and the 401
        // that follows looks like a wrong secret rather than a wrong encoding.
        var expected = "Basic " + Base64.getEncoder().encodeToString(
                "ichat-client:p%40ss+word%2Bx".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(TOKEN_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expected))
                .andRespond(withSuccess("""
                        {"access_token":"token-1","expires_in":300}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL + "/logout")).andRespond(withNoContent());

        new KeycloakAdminClient(true, ISSUER, "ichat-client", "p@ss word+x", "", builder.build(), clock)
                .logoutAllSessions("u-1");

        server.verify();
    }

    // ------------------------------------------------------ the write itself

    @Test
    void disablingPreservesTheRestOfTheUserRepresentation() {
        // Read-modify-write, not a bare {"enabled": false}: a ban must not be a way to silently
        // lose a user's attributes.
        expectTokenRequest("token-1", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.attributes.upload_cap_bytes[0]").value("104857600"))
                .andRespond(withNoContent());

        assertThat(enabledClient().setEnabled("u-1", false).applied()).isTrue();

        server.verify();
    }

    @Test
    void reEnablingSendsEnabledTrue() {
        expectTokenRequest("token-1", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"u-1","username":"bob","enabled":false}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(true))
                .andRespond(withNoContent());

        assertThat(enabledClient().setEnabled("u-1", true).applied()).isTrue();

        server.verify();
    }

    @Test
    void disableAndLogoutDisablesBeforeEndingSessions() {
        // Ordering is load-bearing: logging out first would invite the user to log back in during
        // the gap. MockRestServiceServer enforces the order by default.
        expectTokenRequest("token-1", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(false))
                .andRespond(withNoContent());
        server.expect(requestTo(USER_URL + "/logout")).andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        assertThat(enabledClient().disableAndLogout("u-1").applied()).isTrue();

        server.verify();
    }

    // ------------------------------------------- failure is reported, not thrown

    @Test
    void aMissingRoleIsReportedNotThrown() {
        // The 403 an operator gets when the service account exists but only has view-users. The ban
        // has already been applied locally by the time we get here, so throwing would undo nothing
        // and only turn a working ban into a 500 in the admin's face.
        expectTokenRequest("token-1", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
                .andRespond(withForbiddenRequest());

        var result = enabledClient().setEnabled("u-1", false);

        assertThat(result.failed()).isTrue();
        assertThat(result.applied()).isFalse();
        assertThat(result.detail())
                .contains("403")
                .contains("manage-users");   // the remediation, not just the symptom
        server.verify();
    }

    @Test
    void aClientWithoutAServiceAccountIsReportedNotThrown() {
        // Keycloak answers a client-credentials grant from a client with no service account with
        // 400 unauthorized_client, which is the single most likely first-run failure.
        server.expect(requestTo(TOKEN_URL)).andRespond(withBadRequest());

        var result = enabledClient().setEnabled("u-1", false);

        assertThat(result.failed()).isTrue();
        assertThat(result.detail()).contains("Service accounts roles");
        server.verify();
    }

    @Test
    void anUnreachableKeycloakIsReportedNotThrown() {
        // Network down: an IdP problem must never block a moderation action.
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new IOException("connection refused")));

        var result = enabledClient().logoutAllSessions("u-1");

        assertThat(result.failed()).isTrue();
        assertThat(result.detail()).contains("could not log out");
        server.verify();
    }

    @Test
    void aFailedDisableStillEndsSessionsAndStillReportsFailure() {
        // Killing live sessions is worth doing even when the disable failed, but it must not be
        // allowed to dress the result up as success — the account can still log back in.
        expectTokenRequest("token-1", 300);
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        server.expect(requestTo(USER_URL + "/logout")).andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        var result = enabledClient().disableAndLogout("u-1");

        assertThat(result.failed()).isTrue();
        assertThat(result.detail()).contains("could not disable");
        server.verify();
    }

    @Test
    void anAccountWithNoKeycloakSubjectIsReportedWithoutCallingKeycloak() {
        // A local-only User row (a fixture, or a subject cleared by hand) should not produce a
        // request to /users//logout.
        var client = enabledClient();

        assertThat(client.setEnabled("  ", false).failed()).isTrue();
        assertThat(client.logoutAllSessions(null).failed()).isTrue();

        server.verify();
    }
}
