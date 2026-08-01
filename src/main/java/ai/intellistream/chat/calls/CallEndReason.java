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
 * Why a call ended.
 *
 * <p>The distinctions here are for the <em>live</em> UI, where they are useful and momentary: the
 * caller wants to know whether they were declined or simply not heard, and that is worth a
 * different sentence in the moment.
 *
 * <p><b>The archive collapses them deliberately.</b> Every call that was never answered writes the
 * same "Missed call" line, whether it was declined, timed out, or cancelled. Recording that someone
 * pressed decline puts a social judgement in permanent writing on their behalf — you did not answer
 * and now the room says you refused — and a chat archive should not be in that business. The fact
 * of the call is worth keeping; the verdict on it is not.
 */
public enum CallEndReason {

    /** Somebody pressed hang up. The ordinary end of a call that happened. */
    HANGUP,

    /** The callee pressed decline. A deliberate no. */
    DECLINED,

    /** The caller gave up before the callee answered. */
    CANCELLED,

    /** Rang until {@code ichat.calls.ring-timeout} passed with nobody picking up. */
    TIMEOUT,

    /**
     * A participant's last session went away — closed the tab, lost the network, put the laptop to
     * sleep. Distinct from {@link #HANGUP} because nobody chose it, and it is the reason the other
     * side stops ringing rather than waiting out the full timeout on a call that cannot be answered.
     */
    DISCONNECTED
}
