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
 * Sidebar channel search.
 *
 * The sidebar shows a shortlist — the user's largest channels and their most active ones — so
 * filtering the rendered list is no longer a way to find anything: the channel you're looking for
 * usually isn't in the DOM. This queries the server instead, and renders matches into the main
 * content area rather than the sidebar, where there is room to show the description, the member
 * count and a Join button for channels you aren't in yet.
 *
 * Local matches still get filtered as you type, so the shortlist narrows instantly while the
 * server request is in flight.
 */
export function initSidebarSearch() {
    const input = document.getElementById('sidebar-filter');
    const content = document.querySelector('main.content');
    if (!input || !content) return;

    const MIN_QUERY = 2;
    const DEBOUNCE_MS = 180;
    let timer = null;
    let sequence = 0;          // guards against a slow response overwriting a newer one

    const panel = document.createElement('div');
    panel.className = 'channel-search-results';
    panel.hidden = true;

    const narrowShortlist = (q) => {
        document.querySelectorAll('.sidebar .channel-list > li').forEach((li) => {
            li.style.display = !q || fuzzyMatch(q, li.dataset.name || '') ? '' : 'none';
        });
    };

    // The results panel HIDES the page's own content rather than replacing it. The previous
    // version stashed content.innerHTML and assigned it back, which rebuilds every node: the
    // message list came back as fresh elements with none of the listeners chat.js had attached,
    // and on the conversation page it would also orphan the live STOMP-bound DOM. Toggling a
    // class keeps node identity, so clearing the box returns a page that still works.
    const restore = () => {
        content.classList.remove('searching');
        panel.hidden = true;
    };

    const showPanel = () => {
        if (panel.parentElement !== content) content.appendChild(panel);
        content.classList.add('searching');
        panel.hidden = false;
    };

    const render = (q, results) => {
        panel.replaceChildren();
        const heading = document.createElement('h2');
        heading.textContent = results.length
            ? `Channels matching “${q}”`
            : `No channels match “${q}”`;
        panel.append(heading);
        if (!results.length) {
            const hint = document.createElement('p');
            hint.className = 'channel-search-hint';
            hint.textContent = 'Private channels you are not a member of are not searchable.';
            panel.append(hint);
            return;
        }
        const list = document.createElement('ul');
        list.className = 'channel-search-list';
        for (const c of results) {
            const li = document.createElement('li');
            const link = document.createElement('a');
            link.href = '/channels/' + c.id;
            link.className = 'channel-search-name';
            link.textContent = '#' + c.name;
            li.append(link);
            if (c.type === 'PRIVATE') {
                const lock = document.createElement('span');
                lock.className = 'channel-search-tag';
                lock.textContent = 'private';
                li.append(lock);
            }
            if (!c.joined) {
                const tag = document.createElement('span');
                tag.className = 'channel-search-tag';
                tag.textContent = 'not joined';
                li.append(tag);
            }
            if (c.unreadCount > 0) {
                const badge = document.createElement('span');
                badge.className = 'unread-badge' + (c.mentionCount > 0 ? ' mention' : '');
                badge.textContent = c.unreadCount > 99 ? '99+' : String(c.unreadCount);
                li.append(badge);
            }
            list.append(li);
        }
        panel.append(list);
    };

    const search = async (q) => {
        const mine = ++sequence;
        try {
            const res = await fetch('/api/channels/search?q=' + encodeURIComponent(q) + '&limit=25',
                { headers: headers() });
            if (mine !== sequence) return;   // a newer keystroke already won
            if (!res.ok) return;
            const results = await res.json();
            if (mine !== sequence) return;
            showPanel();
            render(q, results);
        } catch (_) {
            // Offline or a dropped request: leave the shortlist filtering as the fallback.
        }
    };

    input.addEventListener('input', () => {
        const q = input.value.trim();
        narrowShortlist(q.toLowerCase());
        clearTimeout(timer);
        if (q.length < MIN_QUERY) {
            sequence++;      // cancel any in-flight response
            restore();
            return;
        }
        timer = setTimeout(() => search(q), DEBOUNCE_MS);
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            input.value = '';
            sequence++;
            narrowShortlist('');
            restore();
            input.blur();
        }
    });

    document.getElementById('sidebar-browse-all')?.addEventListener('click', () => {
        input.focus();
    });
}

export function init() {
    initTutorial();
    initSidebarSearch();
}
