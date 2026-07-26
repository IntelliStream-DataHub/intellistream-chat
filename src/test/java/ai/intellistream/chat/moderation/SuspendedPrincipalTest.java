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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code CurrentUser.resolve} is the backstop: it judges the row it has just loaded, so it holds
 * even when the in-memory registry is wrong (a hand-edited {@code suspended_at}, a second instance
 * that never saw the ban). It is also what refuses a STOMP CONNECT, which is why a suspended
 * account cannot simply open a fresh session.
 */
class SuspendedPrincipalTest {

    private final UserService userService = mock(UserService.class);
    /** Only reached when a login carries a new zoneinfo claim; nothing here does. */
    private final ai.intellistream.chat.repository.UserRepository users =
            mock(ai.intellistream.chat.repository.UserRepository.class);
    private final CurrentUser currentUser = new CurrentUser(userService, users);

    private static Jwt token(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject(subject)
                .claim("preferred_username", "bob")
                .build();
    }

    @Test
    void resolvingASuspendedPrincipalIsDenied() {
        var suspended = new User("kc-bob", "bob", null, null);
        suspended.suspend(null, "spam");
        // Two-arg constructor: the one-arg form leaves the token unauthenticated, which resolve()
        // rejects before it ever looks at the account.
        var auth = new JwtAuthenticationToken(token("kc-bob"), AuthorityUtils.NO_AUTHORITIES);
        when(userService.provisionFromJwt(auth.getToken())).thenReturn(suspended);

        assertThatThrownBy(() -> currentUser.resolve(auth))
                .isInstanceOf(AccountSuspendedException.class)
                .hasMessageContaining("bob");

        // Not even the last-active stamp: a suspended principal does no work on our behalf.
        verify(userService, never()).touchActiveThrottled(suspended);
    }

    @Test
    void resolvingAnOrdinaryPrincipalIsUnaffected() {
        var user = new User("kc-bob", "bob", null, null);
        // Two-arg constructor: the one-arg form leaves the token unauthenticated, which resolve()
        // rejects before it ever looks at the account.
        var auth = new JwtAuthenticationToken(token("kc-bob"), AuthorityUtils.NO_AUTHORITIES);
        when(userService.provisionFromJwt(auth.getToken())).thenReturn(user);

        assertThat(currentUser.resolve(auth)).isSameAs(user);
        verify(userService).touchActiveThrottled(user);
    }

    @Test
    void theSubjectIsReadFromWhicheverTokenShapeTheChainProduced() {
        assertThat(CurrentUser.subjectOf(
                new JwtAuthenticationToken(token("kc-bob"), AuthorityUtils.NO_AUTHORITIES))).isEqualTo("kc-bob");
        // No token behind it — the filter must treat that as "cannot identify", not as a match.
        assertThat(CurrentUser.subjectOf(new TestingAuthenticationToken("bob", "n/a"))).isNull();
        assertThat(CurrentUser.subjectOf(null)).isNull();
    }
}
