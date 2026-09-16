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

// One-time secrets (/secrets). No chat-kit.js: the page renders no messages and no avatars of its
// own, and the less a page that handles plaintext secrets loads, the less there is to trust.
// secret-crypto.js is the one implementation of the format, shared with the secret-view bundle.
//= require time-format.js
//= require session-watch.js
//= require presence.js
//= require idle-logout.js
//= require secret-crypto.js
//= require secrets.js
