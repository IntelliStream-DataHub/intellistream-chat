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
 * The three states a call can be in. Named rather than inferred from which timestamps happen to be
 * null, because the interesting bugs in a calling feature are all "it thinks it is still ringing"
 * and a state you cannot print is a state you cannot debug.
 *
 * <p>Transitions are {@code RINGING → ACTIVE → ENDED} and {@code RINGING → ENDED}. There is no path
 * back out of {@code ENDED}: a call that ended and then resumed is a new call with a new id, which
 * keeps the archive line honest and stops a reconnecting client from reviving a session the other
 * side has already torn down.
 */
public enum CallState {

    /** Invited, not yet answered. The callee's devices are ringing. */
    RINGING,

    /** Answered. The peers are negotiating or connected; the server is no longer in the path. */
    ACTIVE,

    /** Over, for any of the reasons in {@link CallEndReason}. Terminal. */
    ENDED
}
