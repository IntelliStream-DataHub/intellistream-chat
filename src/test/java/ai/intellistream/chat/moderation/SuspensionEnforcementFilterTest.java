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

import ai.intellistream.chat.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The HTTP half of enforcement: who gets refused, what they get told, and what a suspended user is
 * still allowed to reach.
 */
class SuspensionEnforcementFilterTest {

    private final SuspensionRegistry registry = new SuspensionRegistry(mock(DataSource.class));
    private final SuspensionEnforcementFilter filter = new SuspensionEnforcementFilter(registry);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject) {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject(subject)
                .claim("preferred_username", "bob")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    private void suspend(String subject) {
        var user = new User(subject, "bob", null, null);
        ReflectionTestUtils.setField(user, "id", 42L);
        registry.suspend(user);
    }

    private static MockHttpServletRequest get(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void anUnsuspendedUserPassesThrough() throws Exception {
        suspend("kc-someone-else");
        authenticateAs("kc-bob");
        var chain = new MockFilterChain();

        var response = run(get("/api/channels"), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void anAnonymousRequestPassesThrough() throws Exception {
        suspend("kc-bob");
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        var chain = new MockFilterChain();

        run(get("/"), chain);

        // Anonymous carries no subject, so there is nobody to be suspended.
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aSuspendedUserGetsAJsonEnvelopeOnTheApi() throws Exception {
        suspend("kc-bob");
        authenticateAs("kc-bob");
        var chain = new MockFilterChain();

        var response = run(get("/api/channels/1/messages"), chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":\"account_suspended\"");
        // The suspension note is written for administrators; it must not leak back to the account.
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void theWebSocketHandshakeIsRefusedTheSameWay() throws Exception {
        suspend("kc-bob");
        authenticateAs("kc-bob");
        var chain = new MockFilterChain();

        var response = run(get("/ws"), chain);

        // Otherwise a client hung up on by the evictor simply reconnects.
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).startsWith("application/json");
    }

    @Test
    void aSuspendedUserGetsAnExplanatoryPageInTheBrowser() throws Exception {
        suspend("kc-bob");
        authenticateAs("kc-bob");
        var chain = new MockFilterChain();

        var response = run(get("/channels"), chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(response.getContentAsString()).contains("Account suspended");
        // The CSP bans inline script; the page must not need it.
        assertThat(response.getContentAsString()).doesNotContain("<script");
    }

    @Test
    void logoutAndStaticAssetsStayReachableWhileSuspended() throws Exception {
        suspend("kc-bob");
        authenticateAs("kc-bob");

        for (var path : new String[]{"/logout", "/css/app.css", "/js/chat.js", "/actuator/health"}) {
            var chain = new MockFilterChain();
            run(get(path), chain);
            assertThat(chain.getRequest())
                    .describedAs("%s must stay reachable so a suspended user can read the page and sign out", path)
                    .isNotNull();
        }
    }

    @Test
    void theContextPathIsStrippedBeforeMatching() throws Exception {
        suspend("kc-bob");
        authenticateAs("kc-bob");
        var request = get("/chat/api/channels");
        request.setContextPath("/chat");
        var chain = new MockFilterChain();

        var response = run(request, chain);

        // Deployed under a context path, /chat/api/... is still the API and must get JSON.
        assertThat(response.getContentType()).startsWith("application/json");
    }
}
