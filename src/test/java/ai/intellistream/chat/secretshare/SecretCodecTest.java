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

package ai.intellistream.chat.secretshare;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCodecTest {

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] payloadOfPlaintextSize(int plaintextBytes) {
        var bytes = new byte[SecretCodec.PAYLOAD_OVERHEAD + plaintextBytes];
        bytes[0] = SecretCodec.PAYLOAD_VERSION;
        return bytes;
    }

    @Test
    void publicIdsAre22Base64UrlCharactersAndDoNotRepeat() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            var id = SecretCodec.newPublicId();
            assertThat(id).matches("[A-Za-z0-9_-]{22}");
            assertThat(SecretCodec.isPublicId(id)).isTrue();
            assertThat(seen.add(id)).isTrue();
        }
        assertThat(SecretCodec.isPublicId("short")).isFalse();
        assertThat(SecretCodec.isPublicId("AAAAAAAAAAAAAAAAAAAAA=")).isFalse();
        assertThat(SecretCodec.isPublicId(null)).isFalse();
    }

    @Test
    void aPayloadUpToTheCapIsAcceptedAndOneByteMoreIsNot() {
        assertThat(SecretCodec.decodePayload(b64(payloadOfPlaintextSize(16)), 16)).hasSize(SecretCodec.PAYLOAD_OVERHEAD + 16);
        assertThatThrownBy(() -> SecretCodec.decodePayload(b64(payloadOfPlaintextSize(17)), 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOversizedPayloadIsRefusedBeforeItIsDecoded() {
        // Not base64 at all — if this got as far as decoding, the message would be about the alphabet.
        var huge = "!".repeat(10_000);
        assertThatThrownBy(() -> SecretCodec.decodePayload(huge, 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void aPayloadWithNoCiphertextOrTheWrongVersionIsRefused() {
        assertThatThrownBy(() -> SecretCodec.decodePayload(b64(payloadOfPlaintextSize(0)), 16))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongVersion = payloadOfPlaintextSize(4);
        wrongVersion[0] = 2;
        assertThatThrownBy(() -> SecretCodec.decodePayload(b64(wrongVersion), 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paddingWhitespaceAndTheStandardAlphabetAreRefused() {
        var ok = b64(payloadOfPlaintextSize(4));
        assertThatThrownBy(() -> SecretCodec.decodePayload(ok + "=", 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCodec.decodePayload(ok + " ", 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCodec.decodePayload(ok.replace('A', '+'), 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCodec.decodePayload(null, 16)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theVerifierIsStoredAsItsSha256() throws Exception {
        var verifier = new byte[32];
        verifier[0] = 7;
        var expected = MessageDigest.getInstance("SHA-256").digest(verifier);

        assertThat(SecretCodec.verifierHash(b64(verifier))).isEqualTo(expected);
        assertThat(SecretCodec.verifierHash(b64(verifier))).isNotEqualTo(verifier);
    }

    @Test
    void aVerifierOfAnyOtherSizeIsRefused() {
        assertThatThrownBy(() -> SecretCodec.verifierHash(b64(new byte[31]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCodec.verifierHash(b64(new byte[33]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCodec.verifierHash("x".repeat(43).replace('x', '*'))).isInstanceOf(IllegalArgumentException.class);
        assertThat(SecretCodec.verifierHashOrNull("nope")).isNull();
        assertThat(SecretCodec.verifierHashOrNull(null)).isNull();
        assertThat(SecretCodec.verifierHashOrNull(b64("x".repeat(32).getBytes(StandardCharsets.US_ASCII)))).hasSize(32);
    }
}
