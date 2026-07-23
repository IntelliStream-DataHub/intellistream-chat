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

package ai.intellistream.threadorbit.security;

/**
 * 404 Not Found for resource lookups that miss (channel, message, conversation, user, …).
 * Previously these surfaced as {@link IllegalArgumentException}, which the global handler
 * mapped to 400 — that's wrong: 400 means the client sent malformed input, while a missing
 * resource is a 404. The exception's message carries the row ID for server-side logs only;
 * {@link ai.intellistream.threadorbit.web.ApiExceptionHandler} replaces it with a generic
 * envelope on the wire so we don't confirm/deny existence to unauthenticated peers.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
