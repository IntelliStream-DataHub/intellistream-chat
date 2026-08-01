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

package ai.intellistream.chat.calls;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TURN credential has to be byte-for-byte what coturn will recompute, because a mismatch is
 * invisible: every candidate pair simply fails to authenticate and the call looks like a network
 * problem. So this asserts the scheme against an independently computed HMAC rather than against
 * whatever the implementation happens to produce.
 */
class TurnCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final String SECRET = "a-shared-secret";

    private TurnCredentialService service(Duration ttl) {
        var props = new CallProperties();
        props.setTurnSecret(SECRET);
        props.setCredentialTtl(ttl);
        props.setTurnUrls(List.of("turn:example.com:3478"));
        return new TurnCredentialService(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void usernameIsExpiryColonIdentity() {
        var credential = service(Duration.ofMinutes(10)).mint("alice");

        // coturn parses the username itself and reads the expiry out of it — the format is the
        // protocol, not a convention we are free to prettify.
        assertThat(credential.username())
                .isEqualTo(NOW.plus(Duration.ofMinutes(10)).getEpochSecond() + ":alice");
    }

    @Test
    void credentialIsTheBase64HmacSha1OfTheUsername() throws Exception {
        var credential = service(Duration.ofMinutes(10)).mint("alice");

        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        var expected = Base64.getEncoder().encodeToString(
                mac.doFinal(credential.username().getBytes(StandardCharsets.UTF_8)));

        assertThat(credential.credential()).isEqualTo(expected);
    }

    @Test
    void expiryFollowsTheConfiguredTtl() {
        var credential = service(Duration.ofMinutes(3)).mint("bob");

        assertThat(credential.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
    }

    @Test
    void differentUsersGetDifferentCredentials() {
        var svc = service(Duration.ofMinutes(10));

        // The identity is inside the signed string, which is what makes a leaked credential
        // traceable to an account rather than an anonymous claim on the relay's bandwidth.
        assertThat(svc.mint("alice").credential()).isNotEqualTo(svc.mint("bob").credential());
    }

    @Test
    void aDifferentSecretProducesADifferentCredential() {
        var other = new CallProperties();
        other.setTurnSecret("not-the-same-secret");
        other.setCredentialTtl(Duration.ofMinutes(10));
        var otherService = new TurnCredentialService(other, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(otherService.mint("alice").credential())
                .isNotEqualTo(service(Duration.ofMinutes(10)).mint("alice").credential());
    }
}
