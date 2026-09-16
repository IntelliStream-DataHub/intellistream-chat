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

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;

/**
 * A secret shared through a one-time link: ciphertext the server cannot read, released exactly once
 * to whoever presents the verifier derived from the link's key. See {@code V16__secret_shares.sql}
 * for the lifecycle and {@code SecretShareService} for who may do what.
 *
 * <p>No setters. Every change is one of the two ways a waiting secret ends — {@link #consume} and
 * {@link #revoke} — and each nulls the ciphertext in the same step, so there is no sequence of calls
 * that leaves an ended secret still holding it. The ciphertext and the verifier hash have no getters
 * either: the only way to read the payload is to consume it, and the only question you can ask of
 * the hash is whether a candidate matches.
 */
@Entity
@Table(name = "secret_shares")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecretShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The unguessable id in the link. The numeric {@link #id} never leaves the server. */
    @Column(name = "public_id", nullable = false, length = 22, unique = true)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    /** Plain-text note for the creator only. */
    @Column(length = 80)
    private String label;

    @Getter(AccessLevel.NONE)
    @Column(name = "payload")
    private byte[] payload;

    @Getter(AccessLevel.NONE)
    @Column(name = "verifier_hash", nullable = false)
    private byte[] verifierHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SecretAudience audience;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opened_by_id")
    private User openedBy;

    /** The opener's handle as it was when they opened it; survives the account being deleted. */
    @Column(name = "opened_by_name", length = 100)
    private String openedByName;

    /** An opener without an account: the address the proxy reported, validated as an IP literal. */
    @Column(name = "opened_ip", length = 45)
    private String openedIp;

    /** An opener without an account: "Firefox on Windows", never the raw User-Agent. */
    @Column(name = "opened_client", length = 64)
    private String openedClient;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public SecretShare(String publicId, User creator, String label, byte[] payload, byte[] verifierHash,
                       SecretAudience audience, Instant createdAt, Instant expiresAt) {
        this.publicId = Objects.requireNonNull(publicId);
        this.creator = Objects.requireNonNull(creator);
        this.label = label;
        this.payload = Objects.requireNonNull(payload).clone();
        this.verifierHash = Objects.requireNonNull(verifierHash).clone();
        this.audience = Objects.requireNonNull(audience);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("A secret must expire after it is created");
        }
    }

    /**
     * Who opened a secret: a signed-in user, or someone without an account described by the address
     * and browser summary the request carried. Never both — the one who has an account is named, and
     * their address is not worth keeping.
     */
    public record Opener(User user, String ip, String client) {

        public static Opener signedIn(User user) {
            return new Opener(Objects.requireNonNull(user), null, null);
        }

        public static Opener anonymous(String ip, String client) {
            return new Opener(null, ip, client);
        }
    }

    /** Derived from the row and the clock; see {@link SecretState}. */
    public SecretState state(Instant now) {
        if (openedAt != null) return SecretState.OPENED;
        if (revokedAt != null) return SecretState.REVOKED;
        // A null payload with no ending recorded is a row the sweeper already cleared after expiry.
        if (payload == null || !now.isBefore(expiresAt)) return SecretState.EXPIRED;
        return SecretState.WAITING;
    }

    /**
     * The audience that applies today. An {@code ANYONE} secret created while the workspace allowed
     * them asks for sign-in once an admin has switched them off — the switch is a policy about what
     * may be opened, not only about what may be created.
     */
    public SecretAudience effectiveAudience(boolean publicSecretsAllowed) {
        return audience == SecretAudience.ANYONE && publicSecretsAllowed
                ? SecretAudience.ANYONE : SecretAudience.SIGNED_IN;
    }

    /** Constant-time comparison of a candidate verifier hash against the stored one. */
    public boolean verifierMatches(byte[] candidateHash) {
        return candidateHash != null && MessageDigest.isEqual(verifierHash, candidateHash);
    }

    /**
     * Opens the secret: returns the ciphertext and forgets it in the same step, recording who opened
     * it. Callers must hold the row lock — see {@code SecretShareRepository.findByPublicIdForUpdate}
     * — or two concurrent opens can both read the payload before either writes.
     *
     * @throws IllegalStateException unless the secret is {@link SecretState#WAITING}
     */
    public byte[] consume(Opener opener, Instant now) {
        Objects.requireNonNull(opener);
        var state = state(now);
        if (state != SecretState.WAITING) {
            throw new IllegalStateException("Secret cannot be opened: " + state);
        }
        var released = payload;
        payload = null;
        openedAt = now;
        if (opener.user() != null) {
            openedBy = opener.user();
            openedByName = opener.user().getUsername();
        } else {
            openedIp = opener.ip();
            openedClient = opener.client();
        }
        return released;
    }

    /**
     * Withdraws a waiting secret; its link stops working at once.
     *
     * @throws IllegalStateException unless the secret is {@link SecretState#WAITING}
     */
    public void revoke(Instant now) {
        var state = state(now);
        if (state != SecretState.WAITING) {
            throw new IllegalStateException("Secret cannot be revoked: " + state);
        }
        payload = null;
        revokedAt = now;
    }

    /** True when the opener had no account (as opposed to an account that has since been deleted). */
    public boolean openedAnonymously() {
        return openedAt != null && openedByName == null;
    }
}
