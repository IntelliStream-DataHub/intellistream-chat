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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint exists to be able to answer <em>no</em>, so that is what these are mostly about.
 *
 * <p>The two ways a request arrives unauthenticated are genuinely different objects — a null
 * {@code Authentication} when nothing populated the context at all, and a populated but anonymous
 * token when Spring substituted one — and a check that only handles the first reports a dead
 * session as a live one, which is exactly the silence this whole thing replaces.
 */
class SessionRestControllerTest {

    private final SessionRestController controller = new SessionRestController();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSignedInSessionReportsItselfAndItsUser() {
        var auth = new UsernamePasswordAuthenticationToken(
                "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));

        var body = controller.status(auth).getBody();

        assertThat(body).isNotNull();
        assertThat(body.authenticated()).isTrue();
        assertThat(body.username()).isEqualTo("alice");
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
    }

    @Test
    void theContextIsTheFallbackWhenTheArgumentIsNotResolved() {
        var auth = new UsernamePasswordAuthenticationToken(
                "bob", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));
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
