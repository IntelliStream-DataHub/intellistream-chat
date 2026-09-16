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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.moderation.AccountSuspendedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A WebSocket session has to be named the way the application addresses people, or messages sent to
 * one person arrive at another.
 *
 * <p>Spring delivers {@code /user/**} destinations to the sessions whose principal carries that
 * name. Every {@code convertAndSendToUser} in this application passes the domain handle, so that is
 * what the session must be called — not Keycloak's {@code preferred_username}, which for an
 * email-shaped or collision-suffixed login is a different string belonging, possibly, to somebody
 * else.
 */
class DomainHandleHandshakeHandlerTest {

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final DomainHandleHandshakeHandler handler = new DomainHandleHandshakeHandler(providerOf(currentUser));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CurrentUser> providerOf(CurrentUser value) {
        var provider = (ObjectProvider<CurrentUser>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    private static final ServerHttpRequest REQUEST = mock(ServerHttpRequest.class);
    private static final WebSocketHandler WS = mock(WebSocketHandler.class);

    private Principal determineUser(Principal sessionPrincipal) {
        when(REQUEST.getPrincipal()).thenReturn(sessionPrincipal);
        return handler.determineUser(REQUEST, WS, new HashMap<>());
    }

    private static UsernamePasswordAuthenticationToken login(String preferredUsername) {
        return new UsernamePasswordAuthenticationToken(preferredUsername, "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    private static User user(String handle) {
        return new User("kc-" + handle, handle, handle + "@example.com", "Display " + handle);
    }

    @Test
    void anEmailShapedLoginIsNamedByItsHandle() {
        // The bug in one line: the login is olav@example.com, every notice is addressed to "olav".
        var auth = login("olav@example.com");
        when(currentUser.resolve(auth)).thenReturn(user("olav"));

        var principal = determineUser(auth);

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo("olav");
        assertThat(principal).isInstanceOf(Authentication.class);
    }

    @Test
    void aCollisionSuffixedAccountKeepsItsOwnHandleAndCannotTakeAnothersMessages() {
        // The second "bob" to arrive holds the login bob and the handle bob-kc1. Named by its login
        // it would receive the first bob's DM previews, call invitations and notices.
        var auth = login("bob");
        when(currentUser.resolve(auth)).thenReturn(user("bob-kc1"));

        assertThat(determineUser(auth).getName()).isEqualTo("bob-kc1");
    }

    @Test
    void anOrdinaryAccountIsLeftExactlyAsItWas() {
        var auth = login("alice");
        when(currentUser.resolve(auth)).thenReturn(user("alice"));

        // Same object, not a wrapper: nothing to go wrong for the overwhelming majority of accounts.
        assertThat(determineUser(auth)).isSameAs(auth);
    }

    @Test
    void theRenamedPrincipalStillCarriesEverythingElse() {
        var auth = login("olav@example.com");
        auth.setDetails("session-details");
        when(currentUser.resolve(auth)).thenReturn(user("olav"));

        var renamed = (Authentication) determineUser(auth);

        assertThat(renamed.getPrincipal()).isEqualTo(auth.getPrincipal());
        assertThat(renamed.getCredentials()).isEqualTo(auth.getCredentials());
        assertThat(renamed.getDetails()).isEqualTo("session-details");
        assertThat(renamed.getAuthorities()).isEqualTo(auth.getAuthorities());
        assertThat(renamed.isAuthenticated()).isTrue();
        assertThat(((DomainHandleHandshakeHandler.HandleNamed) renamed).loginName()).isEqualTo("olav@example.com");
    }

    @Test
    void aRenamedJwtSessionCanStillBeResolvedByCurrentUser() {
        // CurrentUser matches on the wrapped principal, never on the token's class, so a bearer-token
        // session survives the rename — if it did not, CONNECT would refuse every API client.
        var jwt = Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("kc-subject").claim("preferred_username", "olav@example.com").build();
        // Two-arg: the single-argument constructor leaves the token unauthenticated.
        var auth = new JwtAuthenticationToken(jwt, java.util.List.of());
        when(currentUser.resolve(any(Authentication.class))).thenReturn(user("olav"));

        var renamed = (Authentication) determineUser(auth);

        assertThat(renamed.getName()).isEqualTo("olav");
        assertThat(CurrentUser.subjectOf(renamed)).isEqualTo("kc-subject");
        assertThat(CurrentUser.signedInAuthentication(renamed)).isSameAs(renamed);
    }

    @Test
    void anUnresolvableOrSuspendedAccountKeepsItsLoginName() {
        // Refusing here would be a handshake failure with nowhere to report it; CONNECT resolves the
        // same principal a moment later and refuses the session where that is handled.
        var auth = login("banned");
        when(currentUser.resolve(auth)).thenThrow(new AccountSuspendedException("banned"));

        assertThat(determineUser(auth)).isSameAs(auth);
    }

    @Test
    void aRequestWithNoAuthenticationIsUntouched() {
        assertThat(determineUser(null)).isNull();

        Principal plain = () -> "not-an-authentication";
        assertThat(determineUser(plain)).isSameAs(plain);
    }
}
