/*
 * Copyright 2026 Olav Gjerde
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

/**
 * Page-chrome bits that stand outside the message feed: the first-time-user
 * tutorial overlay and the typeahead sidebar filter. Imported once from
 * {@link ./index.js} on boot; each piece is a no-op when its anchor element
 * isn't on the page (e.g. tutorial doesn't render once dismissed).
 */
import { headers, fuzzyMatch } from './shared.js';

/** First-time tutorial overlay — three buttons all dismiss the same way. */
function initTutorial() {
    const tutorialOverlay = document.getElementById('tutorial-overlay');
    if (!tutorialOverlay) return;
    const dismiss = async () => {
        tutorialOverlay.remove();
        try {
            await fetch('/profile/tutorial/dismiss', { method: 'POST', headers: headers() });
        } catch (_) {
            // Best-effort: the overlay is gone for this session even if the call fails.
        }
    };
    document.getElementById('tutorial-skip')?.addEventListener('click', dismiss);
    document.getElementById('tutorial-done')?.addEventListener('click', dismiss);
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && document.body.contains(tutorialOverlay)) dismiss();
    });
}

/**
 * Sidebar filter — fuzzy match against the channel name. Substring or
 * subsequence are exact-feeling matches; otherwise Levenshtein similarity
 * ≥ 50% lets small typos still surface the channel you meant.
 */
function initSidebarFilter() {
    const sidebarFilter = document.getElementById('sidebar-filter');
    if (!sidebarFilter) return;
    sidebarFilter.addEventListener('input', () => {
        const q = sidebarFilter.value.trim().toLowerCase();
        document.querySelectorAll('#sidebar-channel-list > li').forEach((li) => {
            const name = li.dataset.name || '';
            li.style.display = fuzzyMatch(q, name) ? '' : 'none';
        });
    });
}

export function init() {
    initTutorial();
    initSidebarFilter();
}
