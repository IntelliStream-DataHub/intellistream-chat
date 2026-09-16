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

import ai.intellistream.chat.domain.SecretAudience;
import ai.intellistream.chat.domain.SecretShare;
import ai.intellistream.chat.domain.SecretState;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.paste.MarkdownEscape;
import ai.intellistream.chat.repository.SecretShareRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.service.AppSettingsService;
import ai.intellistream.chat.service.DirectNoticeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One-time secrets: create, look up, open once, revoke, list.
 *
 * <p><b>What the server can and cannot do.</b> It holds ciphertext it has no key for, and the SHA-256
 * of a verifier derived from that key. Every question about a specific secret — {@link #status} and
 * {@link #open} — must present the verifier, and an unknown id and a wrong verifier get the same
 * {@link Outcome.NotFound}. So the id alone (a log line, the link-preview fetcher, a link that lost
 * its #fragment) reveals nothing and burns nothing, and whoever does hold the key learns whether the
 * secret still waits before deciding to open it.
 *
 * <p><b>No {@code readOnly} on anything that looks a secret up.</b> With a read replica configured,
 * {@code readOnly = true} routes to it, and that is wrong three ways here: {@link #open} takes a row
 * lock, which a hot standby refuses; a recipient who clicks a link the moment it was made would be
 * told it does not exist, because the replica has not seen the insert yet; and a lagging replica
 * could show a secret as still waiting after it was opened. The same goes for {@link #list}, which
 * the creator reloads right after creating or revoking.
 *
 * <p>Rate limits and the choice between a signed-in and an anonymous {@link SecretShare.Opener} are
 * the controller's; this class decides what the rules allow.
 */
@Service
public class SecretShareService {

    public static final int MAX_LABEL_CHARS = 80;
    private static final int PAGE_SIZE = 50;

    private final SecretShareRepository repo;
    private final AppSettingsService settings;
    private final DirectNoticeService notices;
    private final SecretShareProperties props;

    public SecretShareService(SecretShareRepository repo, AppSettingsService settings,
                              DirectNoticeService notices, SecretShareProperties props) {
        this.repo = repo;
        this.settings = settings;
        this.notices = notices;
        this.props = props;
    }

    /** The answer to a question about one secret, asked by someone presenting its verifier. */
    public sealed interface Outcome<T> {
        /** Allowed; here is the answer. */
        record Ok<T>(T value) implements Outcome<T> {}
        /** No such secret, or the verifier does not match — deliberately indistinguishable. */
        record NotFound<T>() implements Outcome<T> {}
        /** It waits, and it may only be opened by someone signed in. Nothing was consumed. */
        record SignInRequired<T>() implements Outcome<T> {}
        /**
         * It has ended. The key holder is told how and when: "already opened ten minutes ago" is how
         * the intended recipient finds out the link reached somebody else first. Never who.
         */
        record Gone<T>(SecretState state, Instant endedAt) implements Outcome<T> {}
    }

    /** What a key holder sees before choosing to reveal. */
    public record Preview(String creatorName, Instant expiresAt, SecretAudience audience, int displaySeconds) {}

    /** A revealed secret: the ciphertext, and how long the page may show what it decrypts to. */
    public record Opened(byte[] payload, int displaySeconds) {}

    public record Created(String publicId, Instant expiresAt) {}

    /** A row of the creator's own list. */
    public record SecretView(String publicId, String label, SecretState state, SecretAudience audience,
                             Instant createdAt, Instant expiresAt, Instant openedAt, String openedByName,
                             boolean openedAnonymously, String openedIp, String openedClient,
                             Instant revokedAt) {

        static SecretView of(SecretShare s, Instant now) {
            return new SecretView(s.getPublicId(), s.getLabel(), s.state(now), s.getAudience(),
                    s.getCreatedAt(), s.getExpiresAt(), s.getOpenedAt(), s.getOpenedByName(),
                    s.openedAnonymously(), s.getOpenedIp(), s.getOpenedClient(), s.getRevokedAt());
        }
    }

    // ------------------------------------------------------------------ creator ----

    @Transactional
    public Created create(User creator, String payload, String verifier, String label,
                          Duration lifetime, SecretAudience audience, Instant now) {
        Objects.requireNonNull(creator);
        if (lifetime == null || !props.getLifetimes().contains(lifetime)) {
            throw new PublicBadRequestException("Choose one of the offered lifetimes.");
        }
        var chosenAudience = audience == null ? SecretAudience.SIGNED_IN : audience;
        if (chosenAudience == SecretAudience.ANYONE && !settings.publicSecretsAllowed()) {
            throw new PublicBadRequestException(
                    "Secrets for people without an account are switched off in this workspace.");
        }
        byte[] ciphertext;
        byte[] verifierHash;
        try {
            ciphertext = SecretCodec.decodePayload(payload, props.getMaxPlaintextBytes());
            verifierHash = SecretCodec.verifierHash(verifier);
        } catch (IllegalArgumentException e) {
            throw new PublicBadRequestException("The secret could not be accepted: " + e.getMessage() + ".");
        }
        if (repo.countWaiting(creator, now) >= props.getMaxWaitingPerUser()) {
            throw new PublicBadRequestException("You already have " + props.getMaxWaitingPerUser()
                    + " secrets waiting to be opened. Revoke one before sharing another.");
        }
        var share = repo.save(new SecretShare(SecretCodec.newPublicId(), creator, normaliseLabel(label),
                ciphertext, verifierHash, chosenAudience, now, now.plus(lifetime)));
        return new Created(share.getPublicId(), share.getExpiresAt());
    }

    /** See the class note on why this is not {@code readOnly}. */
    @Transactional
    public Page<SecretView> list(User creator, int page, Instant now) {
        return repo.findByCreatorOrderByCreatedAtDesc(creator, PageRequest.of(Math.max(page, 0), PAGE_SIZE))
                .map(s -> SecretView.of(s, now));
    }

    /**
     * Withdraws one of the creator's own waiting secrets. A secret that already ended — opened a
     * moment before the click landed, say — is returned as it is rather than refused, so the page can
     * show what actually happened.
     *
     * @throws ResourceNotFoundException for an unknown id or someone else's secret, alike
     */
    @Transactional
    public SecretView revoke(User creator, String publicId, Instant now) {
        var share = SecretCodec.isPublicId(publicId) ? repo.findByPublicIdForUpdate(publicId) : Optional.<SecretShare>empty();
        if (share.isEmpty() || !share.get().getCreator().getId().equals(creator.getId())) {
            throw new ResourceNotFoundException("Secret not found");
        }
        var s = share.get();
        if (s.state(now) == SecretState.WAITING) {
            s.revoke(now);
        }
        return SecretView.of(s, now);
    }

    // ------------------------------------------------------------------ key holder ----

    /** Whether the secret still waits and who shared it, for someone holding its key. Consumes nothing. */
    @Transactional
    public Outcome<Preview> status(String publicId, String verifier, boolean signedIn, Instant now) {
        var share = SecretCodec.isPublicId(publicId) ? repo.findByPublicId(publicId) : Optional.<SecretShare>empty();
        var refusal = guard(share, verifier, signedIn, now);
        if (refusal != null) return cast(refusal);
        var s = share.get();
        return new Outcome.Ok<>(new Preview(displayName(s.getCreator()), s.getExpiresAt(),
                s.effectiveAudience(settings.publicSecretsAllowed()), props.getDisplaySeconds()));
    }

    /**
     * Opens the secret exactly once. The row is locked for the transaction, so of two concurrent
     * opens one gets the payload and the other sees it gone. The creator's receipt is posted in the
     * same transaction: a secret is never consumed without one, and a failure to post it rolls the
     * open back so the recipient can simply try again.
     */
    @Transactional
    public Outcome<Opened> open(String publicId, String verifier, SecretShare.Opener opener, Instant now) {
        Objects.requireNonNull(opener);
        var share = SecretCodec.isPublicId(publicId) ? repo.findByPublicIdForUpdate(publicId) : Optional.<SecretShare>empty();
        var refusal = guard(share, verifier, opener.user() != null, now);
        if (refusal != null) return cast(refusal);
        var s = share.get();
        var payload = s.consume(opener, now);
        var creator = s.getCreator();
        notices.deliver(creator, creator, receiptBody(s));
        return new Outcome.Ok<>(new Opened(payload, props.getDisplaySeconds()));
    }

    /**
     * The checks {@link #status} and {@link #open} share, in the order that matters: the verifier
     * before anything else, so nothing — not even that the secret ended — is told to someone without
     * the key; the ending before the audience, so a key holder is not sent through sign-in only to
     * learn the secret is gone. Returns null when the caller may proceed.
     */
    private Outcome<?> guard(Optional<SecretShare> share, String verifier, boolean signedIn, Instant now) {
        var candidate = SecretCodec.verifierHashOrNull(verifier);
        if (share.isEmpty() || !share.get().verifierMatches(candidate)) {
            return new Outcome.NotFound<>();
        }
        var s = share.get();
        var state = s.state(now);
        if (state != SecretState.WAITING) {
            return new Outcome.Gone<>(state, endedAt(s, state));
        }
        if (!signedIn && s.effectiveAudience(settings.publicSecretsAllowed()) == SecretAudience.SIGNED_IN) {
            return new Outcome.SignInRequired<>();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Outcome<T> cast(Outcome<?> refusal) {
        // Every refusal carries no value, so it is safe at any T.
        return (Outcome<T>) refusal;
    }

    private static Instant endedAt(SecretShare s, SecretState state) {
        return switch (state) {
            case OPENED -> s.getOpenedAt();
            case REVOKED -> s.getRevokedAt();
            case EXPIRED -> s.getExpiresAt();
            case WAITING -> null;
        };
    }

    /**
     * The receipt in the creator's own conversation. The label is the creator's text and is escaped;
     * the only other variable is a handle, which cannot carry Markdown.
     *
     * <p>An anonymous opener's address and browser are deliberately <em>not</em> in it. A message is
     * permanent — it stays in {@code conversation_messages} and the search index for as long as the
     * conversation does — while those two details are promised to disappear with the secret's row
     * after {@code ichat.secrets.retention}. So the receipt says that someone without an account
     * opened it and points at the list, which shows the details for exactly as long as they exist.
     */
    static String receiptBody(SecretShare s) {
        var what = s.getLabel() == null
                ? "Your secret"
                : "Your secret “" + MarkdownEscape.inline(s.getLabel()) + "”";
        var who = s.openedAnonymously()
                ? "someone without an account — their IP address and browser are on Your secrets (/secrets)"
                : "@" + s.getOpenedByName();
        return "🔓 " + what + " was opened by " + who + ". The link no longer works.";
    }

    /** One line, trimmed, at most {@link #MAX_LABEL_CHARS}; blank means no label. */
    static String normaliseLabel(String label) {
        if (label == null) return null;
        var s = label.replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > MAX_LABEL_CHARS) {
            throw new PublicBadRequestException("A label can be at most " + MAX_LABEL_CHARS + " characters.");
        }
        return s;
    }

    private static String displayName(User u) {
        return u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName();
    }
}
