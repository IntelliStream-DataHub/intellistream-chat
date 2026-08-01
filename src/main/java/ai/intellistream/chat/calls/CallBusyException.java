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

/**
 * Someone in a proposed call is already in one.
 *
 * <p>Not an error in the sense the exception handler means — it is an ordinary outcome of pressing
 * call, and the caller gets "they're on another call" rather than a failure. It is an exception
 * because the alternative is threading a rejection through every return type between the registry
 * and the controller, and this is the one branch where the invite does not happen.
 */
public class CallBusyException extends RuntimeException {

    private final String username;

    public CallBusyException(String username) {
        super(username + " is already in a call");
        this.username = username;
    }

    /** Who was busy — the caller (a stale tab) or the callee (an actual second call). */
    public String getUsername() {
        return username;
    }
}
