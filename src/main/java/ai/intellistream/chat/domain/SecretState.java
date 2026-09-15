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

package ai.intellistream.chat.domain;

/**
 * Where a {@link SecretShare} is in its life. Derived, never stored: expiry is a matter of the
 * clock, so a stored state would be wrong from the moment {@code expires_at} passed until something
 * rewrote it.
 */
public enum SecretState {
    /** Holds ciphertext and can still be opened. */
    WAITING,
    /** Opened once; the ciphertext is gone. */
    OPENED,
    /** Its link outlived its lifetime unopened. */
    EXPIRED,
    /** Withdrawn by its creator before anyone opened it. */
    REVOKED
}
