/*
 * Copyright 2026 Olav Gjerde
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

package ai.intellistream.threadorbit.web;

import ai.intellistream.threadorbit.security.PublicBadRequestException;
import ai.intellistream.threadorbit.security.RateLimitExceededException;
import ai.intellistream.threadorbit.security.ResourceNotFoundException;
import ai.intellistream.threadorbit.security.UploadTooLargeException;
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
@RestControllerAdvice(basePackages = "ai.intellistream.threadorbit.web")
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
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new UploadTooLargeBody("upload_too_large", ex.getMessage(),
                        ex.getMaxBytes(), traceId, ex.getMessage()));
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
