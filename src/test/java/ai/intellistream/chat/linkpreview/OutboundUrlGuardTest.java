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

package ai.intellistream.chat.linkpreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSRF guard, address by address. These are the addresses that turn "paste a link" into
 * "make the server GET my internal network", and each is refused by name so a change to the guard
 * that lets one through fails a test that says which.
 */
class OutboundUrlGuardTest {

    private final OutboundUrlGuard guard = new OutboundUrlGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.8.8.8",            // loopback
            "10.0.0.1", "172.16.5.5", "192.168.1.1", // RFC 1918
            "169.254.169.254",                   // link-local — the cloud metadata endpoint
            "0.0.0.0",                           // wildcard
            "100.64.0.1", "100.127.255.254",     // carrier-grade NAT
            "224.0.0.1",                         // multicast
            "240.0.0.1",                         // reserved
            "::1",                               // IPv6 loopback
            "fc00::1", "fd12:3456::1",           // unique-local
            "fe80::1",                           // IPv6 link-local
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", // IPv4-mapped smuggling
            "2002:0a00:0001::1",                 // 6to4 carrying 10.0.0.1
    })
    void forbiddenAddressesAreNamed(String literal) throws Exception {
        assertThat(guard.forbiddenReason(InetAddress.getByName(literal)))
                .as(literal).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:2800:220:1:248:1893:25c8:1946", "2001:4860:4860::8888"})
    void publicAddressesPass(String literal) throws Exception {
        assertThat(guard.forbiddenReason(InetAddress.getByName(literal))).as(literal).isNull();
    }

    @Test
    void onlyHttpAndHttpsAreFetched() {
        assertThatThrownBy(() -> guard.check(URI.create("ftp://example.com/x")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class).hasMessageContaining("scheme");
        assertThatThrownBy(() -> guard.check(URI.create("file:///etc/passwd")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class);
    }

    @Test
    void credentialsInTheUrlAreRefused() {
        assertThatThrownBy(() -> guard.check(URI.create("https://user:pw@example.com/")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class).hasMessageContaining("credentials");
    }

    @Test
    void localhostByNameIsRefusedEvenBeforeResolving() {
        assertThatThrownBy(() -> guard.check(URI.create("http://localhost:8080/admin")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class);
        assertThatThrownBy(() -> guard.check(URI.create("http://keycloak.internal/")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class);
    }

    @Test
    void literalPrivateHostsAreRefused() {
        assertThatThrownBy(() -> guard.check(URI.create("http://169.254.169.254/latest/meta-data/")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class).hasMessageContaining("link-local");
        assertThatThrownBy(() -> guard.check(URI.create("http://[::1]:5432/")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class);
    }

    @Test
    void theTestOnlyLoopbackSwitchIsExactlyThat() {
        var lenient = new OutboundUrlGuard(true);
        assertThatCode(() -> lenient.check(URI.create("http://127.0.0.1:1/"))).doesNotThrowAnyException();
        // Loopback only — a private range is still refused with the switch on.
        assertThatThrownBy(() -> lenient.check(URI.create("http://10.0.0.1/")))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class);
    }
}
