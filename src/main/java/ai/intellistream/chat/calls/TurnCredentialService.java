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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints short-lived TURN credentials using coturn's REST-API scheme (its {@code use-auth-secret} /
 * {@code static-auth-secret} mode).
 *
 * <p>The scheme is small enough to state in full: the username is
 * {@code <unix-expiry-seconds>:<identity>} and the password is the base64 of
 * {@code HMAC-SHA1(shared-secret, username)}. coturn recomputes the same HMAC from the username it
 * was handed and the secret it was configured with, so no credential store is involved on either
 * side and the app never has to tell the TURN server that a user exists. The expiry is inside the
 * signed string, which is why the credential cannot be extended by an attacker who holds one.
 *
 * <p><b>Why not a static username and password.</b> Anything the browser receives, the browser's
 * user has. A fixed TURN credential shipped to every client is a permanent key to an open relay:
 * whoever extracts it can push arbitrary traffic through the server for as long as it stands, and
 * because the relay does not care what it is relaying, the first sign of it is a bandwidth bill or a
 * complaint about the IP. A ten-minute credential bound to one account bounds that to a nuisance.
 *
 * <p>HMAC-SHA1 is not a free choice here — it is what coturn implements for this mode. It is a MAC
 * rather than a bare hash, and SHA-1's collision weaknesses do not carry over to HMAC-SHA1's
 * unforgeability, so it is sound for the job. The interoperability requirement settles it regardless.
 */
@Service
public class TurnCredentialService {

    private static final String HMAC_SHA1 = "HmacSHA1";

    private final CallProperties properties;
    private final Clock clock;

    // @Autowired is load-bearing: with two constructors and no no-arg one, Spring will not guess,
    // and the failure is at context refresh with "No default constructor found" rather than
    // anywhere near the second constructor that caused it.
    @Autowired
    public TurnCredentialService(CallProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Test seam — a fixed clock makes the minted credential assertable. */
    TurnCredentialService(CallProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A username/credential pair valid for {@link CallProperties#getCredentialTtl()}.
     *
     * @param identity who the credential is for. Carried into the username so a coturn log line
     *        names an account rather than only an address — the difference between "somebody is
     *        relaying 400 Mbps" and knowing who to ask about it.
     */
    public TurnCredential mint(String identity) {
        Instant expiry = clock.instant().plus(properties.getCredentialTtl());
        String username = expiry.getEpochSecond() + ":" + identity;
        return new TurnCredential(username, sign(username), expiry);
    }

    private String sign(String username) {
        try {
            var mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(
                    properties.getTurnSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA1 is required of every JRE, so NoSuchAlgorithm cannot happen; InvalidKey means
            // the configured secret is empty, which isConfigured() already refuses to call this on.
            throw new IllegalStateException("Cannot mint TURN credential", e);
        }
    }

    /** A TURN username/password pair and the instant it stops working. */
    public record TurnCredential(String username, String credential, Instant expiresAt) {}
}
