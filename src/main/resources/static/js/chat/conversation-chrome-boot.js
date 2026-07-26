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
 * Boots the two search boxes on the direct-message page.
 *
 * The channel page gets these from chat/index.js, which the DM page does not load — which is
 * precisely why the DM page had neither a channel search nor a message search. Both behaviours
 * now live in their own modules (chat/search-box.js, chat/chrome.js), and this is the second
 * caller rather than a second copy.
 *
 * Loaded as a plain module <script>, like presence-menu-boot.js: the js/chat/ graph is
 * deliberately outside the Closure bundle (see ASSETS.md).
 */
import { initCreateChannel, initFavouriteStars, initSidebarSearch } from './chrome.js';
import { initSearchBox } from './search-box.js';

// Global message search. No channel scope — on this page there is no channel to scope to, and
// the results span channels and conversations alike.
initSearchBox('global-search-input');

// Sidebar channel search. Renders its results over the conversation and puts it back when the
// box is cleared; see the note in chrome.js about why it hides rather than re-renders.
initSidebarSearch();

// The favourite stars on the sidebar's channel rows. The same sidebar fragment renders here, so
// without this the stars would be visible and inert on the DM page — the exact drift the fragment
// was consolidated to prevent.
initFavouriteStars();
initCreateChannel();
