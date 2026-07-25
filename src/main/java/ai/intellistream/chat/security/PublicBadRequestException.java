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

package ai.intellistream.chat.security;

/**
 * 400 Bad Request whose message is safe to render verbatim to the client. Use this
 * (instead of {@link IllegalArgumentException}) when the message is curated for the
 * end user — e.g. "Unknown user: alice", "Title required", "A group needs at least
 * one other member" — rather than an internal-state report that might leak row IDs
 * or other private context.
 *
 * <p>{@link ai.intellistream.chat.web.ApiExceptionHandler} surfaces this exception's
 * message in the response body; everything else funnels through the redacted
 * "Request rejected." envelope.
 */
public class PublicBadRequestException extends RuntimeException {
    public PublicBadRequestException(String publicMessage) {
        super(publicMessage);
    }
}
