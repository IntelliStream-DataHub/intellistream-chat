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
 * Sidebar channel search — two jobs in one box, and the split matters.
 *
 * The sidebar now lists <em>every channel you are in</em>, so filtering the rendered list is a real
 * answer again: what you're looking for is usually right there in the DOM, and narrowing it is
 * instant and offline. That is job one, and it happens on every keystroke.
 *
 * Job two is the one local filtering can never do: finding a channel you have <b>not</b> joined.
 * Those aren't on the page, so it takes a server query, and its results render into the main
 * content area where there is room to say what a channel is rather than into a 260px column.
 *
 * Both run at once, which is only usable if the user can tell which surface answered them — hence
 * the standing hint under the input, the "none of yours match" line in the sidebar, and the
 * explicit "includes channels you haven't joined" heading on the results panel. An unlabelled
 * split box reads as one search behaving inconsistently.
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

    const noMatch = document.getElementById('sidebar-no-match');

    const narrowShortlist = (q) => {
        let shown = 0;
        let rows = 0;
        document.querySelectorAll('.sidebar .channel-list > li').forEach((li) => {
            rows++;
            const keep = !q || fuzzyMatch(q, li.dataset.name || '');
            li.style.display = keep ? '' : 'none';
            if (keep) shown++;
        });
        if (noMatch) noMatch.hidden = !q || rows === 0 || shown > 0;
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
        const hint = document.createElement('p');
        hint.className = 'channel-search-hint';
        hint.textContent = results.length
            // Says which surface this is, because the sidebar filtered at the same time and the two
            // answers are different questions.
            ? 'Every channel you can see, including ones you have not joined. Your own channels are '
              + 'filtered in the sidebar.'
            : 'Private channels you are not a member of are not searchable.';
        panel.append(hint);
        if (!results.length) return;
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
            // "joined" rather than "not joined": now that the sidebar carries every channel you are
            // in, being in one is the useful thing to mark — it tells you the row is also sitting
            // in the list on the left.
            if (c.joined) {
                const tag = document.createElement('span');
                tag.className = 'channel-search-tag';
                tag.textContent = 'joined';
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
}

export function init() {
    initTutorial();
    initSidebarSearch();
}
