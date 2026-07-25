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

package ai.intellistream.chat.moderation;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thrown when an authenticated principal belongs to a suspended account.
 *
 * <p>Extends {@link AccessDeniedException} deliberately: every layer that already knows what to do
 * with a denial then handles this one too, without a single new branch. {@code ApiExceptionHandler}
 * turns it into the 403 envelope, Spring Security's {@code ExceptionTranslationFilter} turns it into
 * a 403 page, and the STOMP inbound interceptor already throws that type when it refuses a
 * SUBSCRIBE, so a refused frame behaves exactly like a refused subscription to a private channel.
 *
 * <p>The message names the account, not the reason. The suspension note is written by an
 * administrator for other administrators and may say things ("spamming from a compromised
 * account, reported by X") that should not be echoed back to the suspended user.
 */
public class AccountSuspendedException extends AccessDeniedException {

    public AccountSuspendedException(String username) {
        super("Account suspended: " + username);
    }
}
