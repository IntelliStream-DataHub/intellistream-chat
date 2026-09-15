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

// The page a one-time secret's link opens (/s/{id}). Reachable signed out, so nothing here may
// assume a session: no session-watch.js (it would announce "signed out" to a visitor who never
// signed in), no presence.js or idle-logout.js (both act on a signed-in user). time-format.js for
// the expiry and "opened at" times, secret-crypto.js for the one implementation of the format.
// SecretClientGuardTest pins this list.
//= require time-format.js
//= require secret-crypto.js
//= require secret-view.js
