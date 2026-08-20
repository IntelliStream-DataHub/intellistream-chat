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

// File manager page. chat-kit.js is here for window.ChatKit.formatBytes — reused rather than
// re-implemented so a size reads identically on this page and on a message's attachment chip.
// Its own initialisers key off .message elements, of which this page has none.
//= require time-format.js
//= require session-watch.js
//= require chat-kit.js
//= require presence.js
//= require idle-logout.js
//= require files.js
