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

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins which requests the bearer/API filter chain claims.
 *
 * <p>The bug this exists to prevent: the matcher tested {@code path.startsWith("/ws/")}, but the
 * STOMP endpoint is registered at exactly {@code /ws} with no trailing slash. A client presenting
 * a JWT to open a WebSocket therefore missed the API chain entirely, fell through to the browser
 * chain, and got a 302 to the login page. It looks like a rejected token and is actually a routing
 * mistake, and no browser ever hits it because browsers authenticate that handshake with the
 * session cookie. It stayed invisible until a bearer-authenticated load generator tried it.
 *
 * <p>Reached by reflection because the matcher is a private static helper. That is the right shape
 * for it, and the alternative — widening its visibility purely to test it — trades production
 * design for test convenience.
 */
class BearerChainMatcherTest {

    private static boolean matches(String path, String authorization) {
        try {
            Method m = SecurityConfig.class.getDeclaredMethod("isBearerApiOrWs", HttpServletRequest.class);
            m.setAccessible(true);
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getRequestURI()).thenReturn(path);
            when(req.getContextPath()).thenReturn("");
            when(req.getHeader("Authorization")).thenReturn(authorization);
            return (boolean) m.invoke(null, req);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("isBearerApiOrWs is gone or changed shape", e);
        }
    }

    private static final String BEARER = "Bearer eyJhbGciOiJSUzI1NiJ9.e30.sig";

    @Test
    void theWebSocketHandshakePathItselfIsClaimed() {
        // The regression. "/ws" is the endpoint; "/ws/" is not what anyone connects to.
        assertThat(matches("/ws", BEARER))
                .describedAs("a bearer WebSocket handshake to /ws must be handled by the API chain, "
                        + "not redirected to the login page by the browser chain")
                .isTrue();
    }

    @Test
    void webSocketSubPathsAreClaimedToo() {
        assertThat(matches("/ws/info", BEARER)).isTrue();
    }

    @Test
    void apiPathsAreClaimed() {
        assertThat(matches("/api/channels", BEARER)).isTrue();
        assertThat(matches("/api", BEARER)).isTrue();
    }

    @Test
    void withoutABearerTokenTheBrowserChainKeepsIt() {
        // Session-authenticated browser traffic must stay on the stateful, CSRF-protected chain.
        assertThat(matches("/ws", null)).isFalse();
        assertThat(matches("/api/channels", null)).isFalse();
        assertThat(matches("/api/channels", "Basic dXNlcjpwYXNz")).isFalse();
    }

    @Test
    void unrelatedPathsAreNotClaimed() {
        assertThat(matches("/channels", BEARER)).isFalse();
        assertThat(matches("/", BEARER)).isFalse();
        // Prefix lookalikes must not be swept in by a careless startsWith.
        assertThat(matches("/wsx", BEARER)).isFalse();
        assertThat(matches("/apixyz", BEARER)).isFalse();
    }

    @Test
    void theBearerSchemeIsMatchedCaseInsensitively() {
        assertThat(matches("/ws", "bearer " + BEARER.substring(7))).isTrue();
    }
}
