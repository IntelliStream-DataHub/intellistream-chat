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

package ai.intellistream.chat.web;

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The endpoint exists to be able to answer <em>no</em>, so that is what these are mostly about.
 *
 * <p>The two ways a request arrives unauthenticated are genuinely different objects — a null
 * {@code Authentication} when nothing populated the context at all, and a populated but anonymous
 * token when Spring substituted one — and a check that only handles the first reports a dead
 * session as a live one, which is exactly the silence this whole thing replaces.
 *
 * <p>The username it reports is the domain handle from {@link CurrentUser}, never the principal
 * name: those differ for every email-shaped login, and the page compares against the handle.
 */
class SessionRestControllerTest {

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final SessionRestController controller = new SessionRestController(currentUser);

    private static Authentication signedInAs(String login) {
        return new UsernamePasswordAuthenticationToken(
                login, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    private void resolvesTo(Authentication auth, String handle) {
        when(currentUser.resolve(auth)).thenReturn(new User("kc-" + handle, handle, handle + "@example.com", handle));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSignedInSessionReportsItselfAndItsUser() {
        var auth = signedInAs("alice");
        resolvesTo(auth, "alice");

        var body = controller.status(auth).getBody();

        assertThat(body).isNotNull();
        assertThat(body.authenticated()).isTrue();
        assertThat(body.username()).isEqualTo("alice");
    }

    @Test
    void theUsernameIsTheDomainHandleNotThePrincipalName() {
        // An email-shaped login: preferred_username is the address, the handle is its local part.
        // The page's me-username meta carries the handle, so reporting the address made every
        // poll from such an account announce an account switch that never happened.
        var auth = signedInAs("olav@example.com");
        resolvesTo(auth, "olav");

        var body = controller.status(auth).getBody();

        assertThat(body).isNotNull();
        assertThat(body.authenticated()).isTrue();
        assertThat(body.username()).isEqualTo("olav");
    }

    @Test
    void anUnresolvableHandleIsStillSignedInButNameless() {
        // Still a live session — the probe must not turn a resolution hiccup into a "signed out"
        // bar. A null username is "no news" to the client, which skips the identity check.
        var auth = signedInAs("carol");
        when(currentUser.resolve(auth)).thenThrow(new AccessDeniedException("Unsupported principal type"));

        var body = controller.status(auth).getBody();

        assertThat(body).isNotNull();
        assertThat(body.authenticated()).isTrue();
        assertThat(body.username()).isNull();
    }

    @Test
    void noAuthenticationAtAllIsSignedOut() {
        var body = controller.status(null).getBody();

        assertThat(body).isNotNull();
        assertThat(body.authenticated()).isFalse();
        assertThat(body.username()).isNull();
    }

    @Test
    void anAnonymousTokenIsSignedOutToo() {
        // Spring marks the anonymous token authenticated() == true, so the principal has to be
        // tested as well. Missing this is the bug that makes an expired session look fine.
        var anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        var body = controller.status(anonymous).getBody();

        assertThat(body).isNotNull();
        assertThat(body.authenticated()).isFalse();
        assertThat(body.username()).isNull();
        verify(currentUser, never()).resolve(any(Authentication.class));
    }

    @Test
    void theContextIsTheFallbackWhenTheArgumentIsNotResolved() {
        var auth = signedInAs("bob");
        resolvesTo(auth, "bob");
        SecurityContextHolder.getContext().setAuthentication(auth);

        var body = controller.status(null).getBody();

        assertThat(body).isNotNull();
        assertThat(body.authenticated()).isTrue();
        assertThat(body.username()).isEqualTo("bob");
    }

    @Test
    void theAnswerIsNeverCached() {
        // A cached "yes" would keep a dead tab convinced it is alive for the life of the entry.
        var response = controller.status(null);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
}
