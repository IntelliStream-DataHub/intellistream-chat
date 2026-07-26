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
 * Boots the @-mention typeahead on both composer-bearing pages.
 *
 * Its own <script type="module"> tag, like presence-menu-boot.js: the js/chat/ graph is
 * deliberately outside the Closure bundle (see ASSETS.md), and the channel page and the DM page
 * load different bundles but the same modules. The channel page has two composers (the main one
 * and the thread reply box); the DM page has one, under the same id. Missing ids are skipped, so
 * this one line is correct on both.
 */
import { initMentionAutocomplete } from './mention-autocomplete.js';

initMentionAutocomplete();
