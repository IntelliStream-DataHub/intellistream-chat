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

import ai.intellistream.chat.security.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.security.Principal;
import java.util.Map;

/**
 * Global exception handler for STOMP {@code @MessageMapping} methods (applies to every WS
 * controller via {@code @ControllerAdvice}).
 *
 * <p>Without this, an exception thrown from a message handler — a {@link RateLimitExceededException}
 * when a user trips the 30/min send cap, or a bean-validation failure on an over-long body —
 * propagates and Spring emits a STOMP {@code ERROR} frame, which per the protocol closes the
 * <b>entire</b> WebSocket connection: every subscription is lost and the sender flips offline.
 * Here we convert the failure into a private notice on {@code /user/queue/notices} (the same
 * channel slash-command errors already use) and let the connection live on.
 */
@ControllerAdvice
public class WebSocketExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(WebSocketExceptionAdvice.class);

    private final SimpMessagingTemplate broker;

    public WebSocketExceptionAdvice(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    /** Matches every exception from a {@code @MessageMapping} handler; keeps the session open. */
    @MessageExceptionHandler
    public void handle(Throwable ex, Principal principal) {
        String text;
        if (ex instanceof RateLimitExceededException) {
            text = "You're doing that a bit too fast — give it a moment and try again.";
        } else if (ex instanceof IllegalArgumentException || isValidation(ex)) {
            // Validation / bad-input: safe to surface a generic reason, don't echo internals.
            text = "That message couldn't be sent — it may be empty or too long.";
        } else {
            // Unexpected: log server-side, tell the user something generic.
            log.warn("Unhandled error in a WebSocket message handler", ex);
            text = "Something went wrong sending that. Please try again.";
        }
        if (principal != null) {
            broker.convertAndSendToUser(principal.getName(), "/queue/notices",
                    Map.of("level", "error", "text", text));
        }
    }

    private static boolean isValidation(Throwable ex) {
        // Messaging's @Valid failure type, matched by name so we don't hard-depend on the exact class.
        String n = ex.getClass().getName();
        return n.contains("MethodArgumentNotValid") || n.contains("ConstraintViolation")
                || n.contains("MessageConversionException");
    }
}
