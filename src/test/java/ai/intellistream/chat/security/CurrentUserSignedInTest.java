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

package ai.intellistream.chat.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CurrentUser#signedInAuthentication} decides, for the routes that serve both, whether a
 * request is somebody's. The session probe and the one-time secret calls both rest on it, so the two
 * shapes of "signed out" are pinned here once.
 */
class CurrentUserSignedInTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthenticationAnywhereIsSignedOut() {
        assertThat(CurrentUser.signedInAuthentication(null)).isNull();
    }

    @Test
    void theAnonymousTokenIsSignedOutEvenThoughSpringCallsItAuthenticated() {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(anonymous.isAuthenticated()).isTrue();
        assertThat(CurrentUser.signedInAuthentication(anonymous)).isNull();

        SecurityContextHolder.getContext().setAuthentication(anonymous);
        assertThat(CurrentUser.signedInAuthentication(null)).isNull();
    }

    @Test
    void anUnauthenticatedTokenIsSignedOut() {
        var pending = new UsernamePasswordAuthenticationToken("alice", "pw");
        assertThat(CurrentUser.signedInAuthentication(pending)).isNull();
    }

    @Test
    void aRealAuthenticationIsReturnedAndTheContextIsTheFallback() {
        var alice = new UsernamePasswordAuthenticationToken("alice", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        assertThat(CurrentUser.signedInAuthentication(alice)).isSameAs(alice);

        SecurityContextHolder.getContext().setAuthentication(alice);
        assertThat(CurrentUser.signedInAuthentication(null)).isSameAs(alice);
    }
}
