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

package ai.intellistream.chat.web;

import ai.intellistream.chat.domain.SecretShare;
import ai.intellistream.chat.secretshare.ClientAddress;
import ai.intellistream.chat.secretshare.ClientSummary;
import ai.intellistream.chat.secretshare.SecretCodec;
import ai.intellistream.chat.secretshare.SecretShareService;
import ai.intellistream.chat.secretshare.SecretShareService.Outcome;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.web.dto.CreateSecretRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One-time secrets over HTTP. Two audiences use this controller and they are kept apart:
 *
 * <ul>
 *   <li><b>The creator</b> — create, list, revoke. Signed in, CSRF-protected, rate-limited per user,
 *       like every other API route.</li>
 *   <li><b>Whoever holds a link</b> — {@code status} and {@code open}. These two are
 *       {@code permitAll} and exempt from CSRF in {@code SecurityConfig}, because the person holding
 *       the link may have no account, and a signed-in one arriving from an email has neither the
 *       session nor the CSRF cookie on that first navigation (both are {@code SameSite=Strict}). What
 *       stands in for authentication is the verifier: without it every answer is the same 404.</li>
 * </ul>
 *
 * <p>Every response carries {@code Cache-Control: no-store}. The open response is ciphertext a
 * browser has the key for, and no cache anywhere should keep a copy of it.
 */
@RestController
@RequestMapping("/api/secrets")
public class SecretShareRestController {

    /**
     * The whole body of a status/open request: {@code {"verifier":"<43 base64url chars>"}}. Read by
     * hand under a hard size cap rather than bound by the JSON converter, because these routes take
     * requests from anyone and the converter would read an arbitrarily large body before any check
     * in this class ran.
     */
    private static final Pattern VERIFIER_BODY =
            Pattern.compile("\\s*\\{\\s*\"verifier\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"\\s*}\\s*");
    private static final int MAX_VERIFIER_BODY_BYTES = 256;

    /** Every signed-out caller shares this budget; see {@link #requireAnonymousBudget}. */
    private static final String ANONYMOUS_KEY = "(anonymous)";

    private final SecretShareService secrets;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public SecretShareRestController(SecretShareService secrets, CurrentUser currentUser,
                                     RateLimiter rateLimiter) {
        this.secrets = secrets;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    // ------------------------------------------------------------------ creator ----

    @PostMapping
    public ResponseEntity<SecretShareService.Created> create(@RequestBody @Valid CreateSecretRequest body,
                                                             Principal principal) {
        var me = currentUser.resolve(principal);
        budget(me.getUsername(), "secret-create", 20);
        var created = secrets.create(me, body.payload(), body.verifier(), body.label(),
                Duration.ofSeconds(body.lifetimeSeconds()), body.audience(), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
    }

    public record SecretPage(List<SecretShareService.SecretView> items, int page, boolean hasMore) {}

    @GetMapping
    public ResponseEntity<SecretPage> list(@RequestParam(defaultValue = "0") int page, Principal principal) {
        var me = currentUser.resolve(principal);
        var result = secrets.list(me, page, Instant.now());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SecretPage(result.getContent(), result.getNumber(), result.hasNext()));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<SecretShareService.SecretView> revoke(@PathVariable String publicId, Principal principal) {
        var me = currentUser.resolve(principal);
        budget(me.getUsername(), "secret-revoke", 30);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(secrets.revoke(me, publicId, Instant.now()));
    }

    // ------------------------------------------------------------------ key holder ----

    @PostMapping("/{publicId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String publicId, HttpServletRequest request,
                                                      Authentication authentication) {
        var viewer = currentUser.signedIn(authentication);
        if (viewer.isPresent()) {
            budget(viewer.get().getUsername(), "secret-status", 60);
        } else {
            requireAnonymousBudget(request);
        }
        var outcome = secrets.status(publicId, readVerifier(request), viewer.isPresent(), Instant.now());
        if (outcome instanceof Outcome.Ok<SecretShareService.Preview>(var preview)) {
            var body = new LinkedHashMap<String, Object>();
            body.put("creatorName", preview.creatorName());
            body.put("expiresAt", preview.expiresAt());
            body.put("audience", preview.audience());
            body.put("signedIn", viewer.isPresent());
            body.put("displaySeconds", preview.displaySeconds());
            return noStore(HttpStatus.OK, body);
        }
        return refusal(outcome);
    }

    @PostMapping("/{publicId}/open")
    public ResponseEntity<Map<String, Object>> open(@PathVariable String publicId, HttpServletRequest request,
                                                    Authentication authentication) {
        var viewer = currentUser.signedIn(authentication);
        SecretShare.Opener opener;
        if (viewer.isPresent()) {
            budget(viewer.get().getUsername(), "secret-open", 30);
            opener = SecretShare.Opener.signedIn(viewer.get());
        } else {
            requireAnonymousBudget(request);
            opener = SecretShare.Opener.anonymous(ClientAddress.literalOrNull(request.getRemoteAddr()),
                    ClientSummary.of(request.getHeader("User-Agent")));
        }
        var outcome = secrets.open(publicId, readVerifier(request), opener, Instant.now());
        if (outcome instanceof Outcome.Ok<SecretShareService.Opened>(var opened)) {
            var body = new LinkedHashMap<String, Object>();
            body.put("payload", SecretCodec.encode(opened.payload()));
            body.put("displaySeconds", opened.displaySeconds());
            return noStore(HttpStatus.OK, body);
        }
        return refusal(outcome);
    }

    private static ResponseEntity<Map<String, Object>> refusal(Outcome<?> outcome) {
        return switch (outcome) {
            case Outcome.NotFound<?> n -> noStore(HttpStatus.NOT_FOUND, Map.of(
                    "code", "not_found",
                    "message", "This secret doesn't exist, or the key doesn't match it."));
            case Outcome.SignInRequired<?> s -> noStore(HttpStatus.UNAUTHORIZED, Map.of(
                    "code", "sign_in_required",
                    "message", "Sign in to open this secret."));
            case Outcome.Gone<?>(var state, var endedAt) -> {
                var body = new LinkedHashMap<String, Object>();
                body.put("code", "gone");
                body.put("state", state);
                body.put("endedAt", endedAt);
                yield noStore(HttpStatus.GONE, body);
            }
            case Outcome.Ok<?> ok -> throw new IllegalStateException("Not a refusal");
        };
    }

    private static ResponseEntity<Map<String, Object>> noStore(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    /** The verifier from the request body, or null for anything that is not exactly the expected shape. */
    static String readVerifier(HttpServletRequest request) {
        byte[] body;
        try (var in = request.getInputStream()) {
            body = in.readNBytes(MAX_VERIFIER_BODY_BYTES + 1);
        } catch (IOException e) {
            return null;
        }
        if (body.length > MAX_VERIFIER_BODY_BYTES) {
            return null;
        }
        var m = VERIFIER_BODY.matcher(new String(body, StandardCharsets.UTF_8));
        return m.matches() ? m.group(1) : null;
    }

    private void budget(String key, String action, int perMinute) {
        if (!rateLimiter.tryAcquire(key, action, perMinute, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException(action + " rate exceeded");
        }
    }

    /**
     * Two budgets for a caller with no account, checked in this order.
     *
     * <p>The shared one comes first and is the real guard. The per-address one is keyed on
     * {@code getRemoteAddr()}, which is the leftmost {@code X-Forwarded-For} entry — the client's own
     * choice unless the proxy overwrites the header — so on its own a client could mint a fresh
     * "address" per request, and every one would also be a new key in the limiter's map. Behind the
     * shared ceiling, both the request rate and the number of new keys a minute are bounded.
     */
    private void requireAnonymousBudget(HttpServletRequest request) {
        budget(ANONYMOUS_KEY, "secret-anonymous", 300);
        var address = ClientAddress.literalOrNull(request.getRemoteAddr());
        budget(address == null ? "(unknown address)" : address, "secret-anonymous-address", 30);
    }
}
