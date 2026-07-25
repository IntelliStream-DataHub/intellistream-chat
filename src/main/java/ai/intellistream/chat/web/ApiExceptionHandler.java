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

import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.security.StorageQuotaExceededException;
import ai.intellistream.chat.security.StorageUnavailableException;
import ai.intellistream.chat.security.UploadTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * Returns redacted error envelopes to clients while keeping the full detail in the server log.
 * Each response carries a stable {@code code} the UI can switch on, plus a short
 * human-readable {@code message}, plus a request-scoped {@code traceId} so we can correlate
 * a user-reported error with a log line. Internal exception messages — which sometimes
 * include row IDs or user-supplied input — are not echoed back.
 */
@RestControllerAdvice(basePackages = "ai.intellistream.chat.web")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return envelope(HttpStatus.BAD_REQUEST, "bad_request", ex);
    }

    /**
     * 400 with the actual exception message echoed back. Use {@link PublicBadRequestException}
     * (not raw {@link IllegalArgumentException}) when the message is curated for end users
     * and safe to surface — e.g. "Unknown user: alice", "Group needs at least one other member".
     */
    @ExceptionHandler(PublicBadRequestException.class)
    public ResponseEntity<Map<String, String>> publicBadRequest(PublicBadRequestException ex) {
        var traceId = newTraceId();
        log.warn("[trace={}] bad_request: {}", traceId, ex.getMessage());
        var msg = ex.getMessage() == null ? "Bad request." : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "bad_request",
                        "message", msg,
                        "traceId", traceId,
                        "error", msg));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return envelope(HttpStatus.CONFLICT, "conflict", ex);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException ex) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", ex);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException ex) {
        return envelope(HttpStatus.FORBIDDEN, "forbidden", ex);
    }

    /**
     * 413 Payload Too Large with the actual byte limit. Distinct from the redacted
     * {@code bad_request} envelope so the JS upload UX can render an informative
     * "max 50 MiB" message instead of the generic "Request rejected." default.
     */
    /** 413 body. {@code maxBytes} is a primitive {@code long} on purpose: the global
     *  {@code Long → ToStringSerializer} only catches the boxed type, so a bare {@code Map} rendered
     *  it as the string {@code "52428800"} and the clients' {@code typeof === 'number'} guard failed
     *  (N9). A primitive field serializes as a JSON number. */
    public record UploadTooLargeBody(String code, String message, long maxBytes,
                                     String traceId, String error) {}

    @ExceptionHandler(UploadTooLargeException.class)
    public ResponseEntity<UploadTooLargeBody> uploadTooLarge(UploadTooLargeException ex) {
        var traceId = newTraceId();
        log.warn("[trace={}] upload_too_large: maxBytes={}", traceId, ex.getMaxBytes());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new UploadTooLargeBody("upload_too_large", ex.getMessage(),
                        ex.getMaxBytes(), traceId, ex.getMessage()));
    }

    /** 413 body for a spent storage allowance. Primitive {@code long}s for the same reason as
     *  {@link UploadTooLargeBody} — the global {@code Long → ToStringSerializer} would otherwise
     *  render them as strings and the clients' numeric guards would fail. */
    public record StorageQuotaBody(String code, String message, long quotaBytes, long usedBytes,
                                   long remainingBytes, String traceId, String error) {}

    /**
     * 413 Payload Too Large, code {@code storage_quota_exceeded} — the account is out of room,
     * not the server.
     *
     * <p>507 Insufficient Storage is the tidier code on paper, and it is wrong here: it is a 5xx,
     * so proxies retry it, alerting counts it as a server fault, and the client cannot tell it
     * apart from an outage. Nothing is broken — this user has used their allowance, and only they
     * can fix it. Reusing 413 also means the existing upload UI, which already knows how to render
     * a 413, degrades gracefully until it learns the new {@code code}; the numbers in the body are
     * there so it can eventually say <em>1.9 of 2.0 GiB used</em> instead of a bare refusal.
     */
    @ExceptionHandler(StorageQuotaExceededException.class)
    public ResponseEntity<StorageQuotaBody> storageQuota(StorageQuotaExceededException ex) {
        var traceId = newTraceId();
        log.warn("[trace={}] storage_quota_exceeded: used={} quota={}",
                traceId, ex.getUsedBytes(), ex.getQuotaBytes());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new StorageQuotaBody("storage_quota_exceeded", ex.getMessage(),
                        ex.getQuotaBytes(), ex.getUsedBytes(), ex.getRemainingBytes(),
                        traceId, ex.getMessage()));
    }

    /**
     * 507 Insufficient Storage — the volume is full or refusing writes. This one <em>is</em> the
     * server's problem, hence 5xx, and the specific 507 rather than the 500 an unhandled
     * {@link java.io.IOException} would produce: "500 on every upload" reads as a code bug and
     * sends whoever is on call looking in the wrong place, while 507 names the fault. Logged at
     * ERROR because a full disk stays broken until a human frees space.
     *
     * <p>{@code Retry-After} is deliberately long. Nothing the client does changes anything until
     * an operator acts, and a UI that retries every few seconds turns one incident into a
     * self-inflicted load test.
     */
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable(StorageUnavailableException ex) {
        var traceId = newTraceId();
        log.error("[trace={}] storage_unavailable: {}", traceId, ex.getMessage(), ex);
        var message = "The server is out of storage space — an administrator has been notified.";
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                .header("Retry-After", "300")
                .body(Map.of("code", "storage_unavailable",
                        "message", message,
                        "traceId", traceId,
                        "error", message));
    }

    /**
     * 409 Conflict with the refusal echoed back verbatim — the file manager declining to delete a
     * file because doing so would destroy somebody else's replies, or because a moderator's
     * (reversible) removal already owns it.
     *
     * <p>Not routed through the redacted {@link #envelope} path: the whole value of this refusal is
     * the explanation, and "Conflicting state — refresh and retry." tells the user to do the one
     * thing that will not help. The message is composed in
     * {@code UserFileService.blockReasonFor} from the message's own state — no identifiers, no user
     * input, nothing to redact.
     */
    @ExceptionHandler(ai.intellistream.chat.service.UserFileService.FileDeleteRefusedException.class)
    public ResponseEntity<Map<String, String>> fileDeleteRefused(
            ai.intellistream.chat.service.UserFileService.FileDeleteRefusedException ex) {
        var traceId = newTraceId();
        log.info("[trace={}] file_delete_refused: {}", traceId, ex.getMessage());
        var msg = ex.getMessage() == null ? "That file cannot be deleted here." : ex.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "file_delete_refused",
                        "message", msg,
                        "traceId", traceId,
                        "error", msg));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> rateLimited(RateLimitExceededException ex) {
        var traceId = newTraceId();
        log.warn("[trace={}] rate_limited: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "30")
                .body(Map.of("code", "rate_limited",
                        "message", "Too many requests — slow down.",
                        "traceId", traceId,
                        "error", "Too many requests — slow down."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException ex) {
        var msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("validation failed");
        var traceId = newTraceId();
        log.warn("[trace={}] validation: {}", traceId, msg);
        // Validation messages come from our own constraint annotations and are safe to surface.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "validation", "message", msg, "traceId", traceId));
    }

    private static ResponseEntity<Map<String, String>> envelope(HttpStatus status, String code, Throwable ex) {
        var traceId = newTraceId();
        log.warn("[trace={}] {}: {}", traceId, code, ex.toString());
        // Generic, non-leaky message; the UI can localise on `code` if it cares.
        var publicMessage = switch (code) {
            case "forbidden" -> "Not allowed.";
            case "conflict"  -> "Conflicting state — refresh and retry.";
            case "not_found" -> "Not found.";
            default          -> "Request rejected.";
        };
        return ResponseEntity.status(status)
                .body(Map.of("code", code, "message", publicMessage, "traceId", traceId, "error", publicMessage));
    }

    private static String newTraceId() {
        // Short ID — enough entropy to correlate inside a single deploy log without being a UUID burden.
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
