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

package ai.intellistream.chat.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The state machine of a one-time secret. Every way a waiting secret ends must take its ciphertext
 * with it, and nothing may end it twice — those are the two properties "opens exactly once" rests on
 * before any database or lock is involved.
 */
class SecretShareTest {

    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");

    private static User user(String name) {
        return new User("kc-" + name, name, name + "@example.com", name);
    }

    private static SecretShare waiting(SecretAudience audience) {
        return new SecretShare("AAAAAAAAAAAAAAAAAAAAAA", user("alice"), "label",
                new byte[]{1, 2, 3}, new byte[32], audience, T0, T0.plus(Duration.ofHours(1)));
    }

    @Test
    void aNewSecretWaitsUntilItExpires() {
        var s = waiting(SecretAudience.SIGNED_IN);
        assertThat(s.state(T0)).isEqualTo(SecretState.WAITING);
        assertThat(s.state(T0.plus(Duration.ofMinutes(59)))).isEqualTo(SecretState.WAITING);
        // Expiry is exclusive: at expires_at it is already over.
        assertThat(s.state(T0.plus(Duration.ofHours(1)))).isEqualTo(SecretState.EXPIRED);
    }

    @Test
    void consumingReleasesThePayloadOnceAndRecordsASignedInOpener() {
        var s = waiting(SecretAudience.SIGNED_IN);
        var bob = user("bob");

        var payload = s.consume(SecretShare.Opener.signedIn(bob), T0.plusSeconds(5));

        assertThat(payload).containsExactly(1, 2, 3);
        assertThat(s.state(T0.plusSeconds(6))).isEqualTo(SecretState.OPENED);
        assertThat(s.getOpenedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(s.getOpenedBy()).isSameAs(bob);
        assertThat(s.getOpenedByName()).isEqualTo("bob");
        assertThat(s.getOpenedIp()).isNull();
        assertThat(s.getOpenedClient()).isNull();
        assertThat(s.openedAnonymously()).isFalse();

        assertThatThrownBy(() -> s.consume(SecretShare.Opener.signedIn(bob), T0.plusSeconds(7)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAnonymousOpenerIsRecordedByAddressAndBrowserOnly() {
        var s = waiting(SecretAudience.ANYONE);

        s.consume(SecretShare.Opener.anonymous("203.0.113.7", "Firefox on Windows"), T0.plusSeconds(1));

        assertThat(s.getOpenedBy()).isNull();
        assertThat(s.getOpenedByName()).isNull();
        assertThat(s.getOpenedIp()).isEqualTo("203.0.113.7");
        assertThat(s.getOpenedClient()).isEqualTo("Firefox on Windows");
        assertThat(s.openedAnonymously()).isTrue();
    }

    @Test
    void anExpiredOrRevokedSecretCannotBeOpened() {
        var expired = waiting(SecretAudience.SIGNED_IN);
        assertThatThrownBy(() -> expired.consume(SecretShare.Opener.signedIn(user("bob")), T0.plus(Duration.ofHours(2))))
                .isInstanceOf(IllegalStateException.class);

        var revoked = waiting(SecretAudience.SIGNED_IN);
        revoked.revoke(T0.plusSeconds(1));
        assertThat(revoked.state(T0.plusSeconds(2))).isEqualTo(SecretState.REVOKED);
        assertThat(revoked.getRevokedAt()).isEqualTo(T0.plusSeconds(1));
        assertThatThrownBy(() -> revoked.consume(SecretShare.Opener.signedIn(user("bob")), T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOpenedSecretCannotBeRevokedAndARevokedOneCannotBeRevokedAgain() {
        var opened = waiting(SecretAudience.SIGNED_IN);
        opened.consume(SecretShare.Opener.signedIn(user("bob")), T0.plusSeconds(1));
        assertThatThrownBy(() -> opened.revoke(T0.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
        assertThat(opened.getRevokedAt()).isNull();

        var revoked = waiting(SecretAudience.SIGNED_IN);
        revoked.revoke(T0.plusSeconds(1));
        assertThatThrownBy(() -> revoked.revoke(T0.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAnyoneSecretNeedsSignInWhileTheWorkspaceDisallowsThem() {
        assertThat(waiting(SecretAudience.ANYONE).effectiveAudience(true)).isEqualTo(SecretAudience.ANYONE);
        assertThat(waiting(SecretAudience.ANYONE).effectiveAudience(false)).isEqualTo(SecretAudience.SIGNED_IN);
        // The switch never widens a signed-in secret.
        assertThat(waiting(SecretAudience.SIGNED_IN).effectiveAudience(true)).isEqualTo(SecretAudience.SIGNED_IN);
    }

    @Test
    void theVerifierComparisonAcceptsOnlyTheExactHash() {
        var hash = new byte[32];
        hash[31] = 9;
        var s = new SecretShare("AAAAAAAAAAAAAAAAAAAAAA", user("alice"), null, new byte[]{1},
                hash, SecretAudience.SIGNED_IN, T0, T0.plusSeconds(60));

        assertThat(s.verifierMatches(hash.clone())).isTrue();
        var other = hash.clone();
        other[0] = 1;
        assertThat(s.verifierMatches(other)).isFalse();
        assertThat(s.verifierMatches(new byte[31])).isFalse();
        assertThat(s.verifierMatches(null)).isFalse();
    }

    @Test
    void theEntityKeepsItsOwnCopiesOfTheBytesItWasGiven() {
        var payload = new byte[]{1, 2, 3};
        var s = new SecretShare("AAAAAAAAAAAAAAAAAAAAAA", user("alice"), null, payload,
                new byte[32], SecretAudience.SIGNED_IN, T0, T0.plusSeconds(60));
        payload[0] = 42;

        assertThat(s.consume(SecretShare.Opener.signedIn(user("bob")), T0)).containsExactly(1, 2, 3);
    }

    @Test
    void aSecretMustExpireAfterItIsCreated() {
        assertThatThrownBy(() -> new SecretShare("AAAAAAAAAAAAAAAAAAAAAA", user("alice"), null, new byte[]{1},
                new byte[32], SecretAudience.SIGNED_IN, T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
