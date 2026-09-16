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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The wire format of a one-time secret, from the server's side. The browser does all the
 * cryptography ({@code static/js/secret-crypto.js}); this class only checks that what arrives has
 * the right shape and size, and hashes the verifier.
 *
 * <ul>
 *   <li><b>Payload</b>: {@code 0x01 ‖ IV(12) ‖ AES-256-GCM ciphertext ‖ tag(16)}, base64url without
 *       padding. The server cannot decrypt it and does not try; it checks the version byte and the
 *       length, so a client cannot park arbitrary bytes here.</li>
 *   <li><b>Verifier</b>: 32 bytes HKDF-derived from the link's key, base64url. Stored only as its
 *       SHA-256. A 256-bit value derived from a 256-bit random key needs no salt or stretching —
 *       there is nothing to brute-force — and hashing it means a read-only copy of the table is not
 *       enough to open, or burn, anything.</li>
 *   <li><b>Public id</b>: 16 bytes from {@link SecureRandom}, base64url — 22 characters.</li>
 * </ul>
 *
 * <p>Pure functions; the tests are {@code SecretCodecTest}.
 */
public final class SecretCodec {

    private SecretCodec() {}

    public static final byte PAYLOAD_VERSION = 0x01;
    public static final int IV_BYTES = 12;
    public static final int TAG_BYTES = 16;
    /** Version byte, IV and GCM tag: everything in a payload that is not ciphertext. */
    public static final int PAYLOAD_OVERHEAD = 1 + IV_BYTES + TAG_BYTES;
    public static final int VERIFIER_BYTES = 32;
    public static final int PUBLIC_ID_BYTES = 16;

    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String newPublicId() {
        var bytes = new byte[PUBLIC_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    public static boolean isPublicId(String s) {
        return s != null && PUBLIC_ID.matcher(s).matches();
    }

    public static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Decodes and checks a payload.
     *
     * @throws IllegalArgumentException when it is not base64url, is empty, too large for
     *         {@code maxPlaintextBytes}, or does not carry the expected version byte
     */
    public static byte[] decodePayload(String encoded, int maxPlaintextBytes) {
        int maxBytes = maxPlaintextBytes + PAYLOAD_OVERHEAD;
        // Refuse on length before decoding, so an oversized body costs a string length check.
        if (encoded == null || encoded.length() > encodedLength(maxBytes)) {
            throw new IllegalArgumentException("Secret is too large");
        }
        var bytes = decode(encoded);
        if (bytes.length <= PAYLOAD_OVERHEAD || bytes.length > maxBytes) {
            throw new IllegalArgumentException("Secret payload has the wrong size");
        }
        if (bytes[0] != PAYLOAD_VERSION) {
            throw new IllegalArgumentException("Unknown secret payload version");
        }
        return bytes;
    }

    /**
     * Decodes a verifier and returns its SHA-256, the only form the server keeps or compares.
     *
     * @throws IllegalArgumentException when it is not base64url for exactly 32 bytes
     */
    public static byte[] verifierHash(String encoded) {
        if (encoded == null || encoded.length() != encodedLength(VERIFIER_BYTES)) {
            throw new IllegalArgumentException("Verifier has the wrong size");
        }
        var bytes = decode(encoded);
        if (bytes.length != VERIFIER_BYTES) {
            throw new IllegalArgumentException("Verifier has the wrong size");
        }
        return sha256(bytes);
    }

    /** Like {@link #verifierHash} but null for anything malformed, for the paths that answer 404. */
    public static byte[] verifierHashOrNull(String encoded) {
        try {
            return verifierHash(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Characters of unpadded base64 for {@code bytes} bytes. */
    static int encodedLength(int bytes) {
        return (bytes * 4 + 2) / 3;
    }

    private static byte[] decode(String encoded) {
        if (!BASE64URL.matcher(encoded).matches()) {
            throw new IllegalArgumentException("Not base64url");
        }
        return Base64.getUrlDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JRE", e);
        }
    }
}
