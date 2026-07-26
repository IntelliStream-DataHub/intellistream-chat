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

/*
 * The /search results page. Everything the shared top bar needs and nothing the message feed does
 * — no composer, no emoji picker, no STOMP. Listed rather than reused from chat.manifest.js because
 * a bundle is a promise about what a page downloads, and pointing this page at the channel bundle
 * would ship it the whole composer to draw a list of links.
 */
//= require theme-loader.js
//= require hovercard.js
//= require mention-inbox.js
//= require presence.js
//= require idle-logout.js
