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
 * tutorial overlay, the typeahead sidebar filter, the Browse channels directory,
 * the first-login suggestions' Join buttons, and the sidebar's favourite stars.
 * Imported once from {@link ./index.js} on boot; each piece is a no-op when its
 * anchor element isn't on the page (e.g. tutorial doesn't render once dismissed).
 *
 * <p><b>The sidebar is shared by two pages</b> — the channel page and the
 * conversation page render the same fragment — so anything that makes it work
 * belongs here rather than in index.js, which only the channel page loads.
 * `conversation-chrome-boot.js` is the second caller. The fragment's own comment
 * records what happens otherwise: a script that reaches into the sidebar from one
 * page's entry point works on that page and silently does nothing on the other.
 */
import { headers, fuzzyMatch } from './shared.js';

/**
 * A transient message at the bottom of the screen, for the small failures that have no
 * natural place on the page — a star that didn't save, a link that didn't copy. Here
 * rather than in index.js because the sidebar code below needs it and the conversation
 * page has no index.js.
 */
export function flashToast(text) {
    const el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text;
    document.body.appendChild(el);
    setTimeout(() => { el.classList.add('show'); });
    setTimeout(() => { el.classList.remove('show'); }, 2200);
    setTimeout(() => { el.remove(); }, 2700);
}

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
// ---------- The content overlay ----------
// One panel, shared by the channel search and the Browse channels directory. Both render a list of
// channels into the main content area, and both need the page's own content out of the way while
// they do; two panels would need to know about each other to avoid showing side by side.
//
// The panel HIDES the page's own content rather than replacing it. The previous version stashed
// content.innerHTML and assigned it back, which rebuilds every node: the message list came back as
// fresh elements with none of the listeners chat.js had attached, and on the conversation page it
// would also orphan the live STOMP-bound DOM. Toggling a class keeps node identity, so closing the
// panel returns a page that still works.
let overlayPanel = null;

function overlay() {
    if (overlayPanel) return overlayPanel;
    overlayPanel = document.createElement('div');
    overlayPanel.className = 'channel-search-results';
    overlayPanel.hidden = true;
    return overlayPanel;
}

function showOverlay() {
    const content = document.querySelector('main.content');
    if (!content) return null;
    const panel = overlay();
    if (panel.parentElement !== content) content.appendChild(panel);
    content.classList.add('searching');
    panel.hidden = false;
    return panel;
}

function hideOverlay() {
    document.querySelector('main.content')?.classList.remove('searching');
    if (overlayPanel) overlayPanel.hidden = true;
}

/** POST the join; on success open the channel, which is what anyone who just joined wants next. */
async function joinChannel(id, button) {
    if (button) button.disabled = true;
    try {
        const res = await fetch('/api/channels/' + id + '/join', { method: 'POST', headers: headers() });
        if (!res.ok) throw new Error(String(res.status));
        window.location.assign('/channels/' + id);
    } catch (_) {
        if (button) button.disabled = false;
        flashToast('Could not join the channel');
    }
}

/** The row used by both the search results and the directory: #name, tags, and optionally a Join. */
function channelRow(c, { withJoin }) {
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
    // Same rule as the sidebar (ChannelSidebarDto.UnreadCue): a number only for mentions,
    // ordinary unread carried by the name's weight. The two surfaces show the same channel
    // at the same time, so a count here and a bold name there would read as a bug.
    if (c.mentionCount > 0) {
        const badge = document.createElement('span');
        badge.className = 'unread-badge mention';
        badge.textContent = c.mentionCount > 99 ? '99+' : String(c.mentionCount);
        li.append(badge);
    } else if (c.unreadCount > 0) {
        li.classList.add('has-unread');
    }
    if (typeof c.memberCount === 'number') {
        const members = document.createElement('span');
        members.className = 'channel-search-members';
        members.textContent = c.memberCount === 1 ? '1 member' : c.memberCount + ' members';
        li.append(members);
    }
    if (c.description) {
        const desc = document.createElement('p');
        desc.className = 'channel-search-desc';
        desc.textContent = c.description;
        li.append(desc);
    }
    if (withJoin && !c.joined) {
        const join = document.createElement('button');
        join.type = 'button';
        join.className = 'channel-search-join';
        join.textContent = 'Join';
        join.setAttribute('aria-label', 'Join #' + c.name);
        join.addEventListener('click', () => joinChannel(c.id, join));
        li.append(join);
    }
    return li;
}

export function initSidebarSearch() {
    const input = document.getElementById('sidebar-filter');
    const content = document.querySelector('main.content');
    if (!input || !content) return;

    const MIN_QUERY = 2;
    const DEBOUNCE_MS = 180;
    let timer = null;
    let sequence = 0;          // guards against a slow response overwriting a newer one

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
        // Hide the Favourites group when every row in it filtered out, so its heading doesn't sit
        // above nothing. Only .channel-group sections — the Channels section keeps its heading
        // whatever happens, because that is where the create-channel button and the "none of yours
        // match" line live. Only while filtering, too: with an empty query the groups are whatever
        // the server rendered.
        document.querySelectorAll('.sidebar .channel-group').forEach((group) => {
            const visible = [...group.querySelectorAll('.channel-list > li')]
                .some((li) => li.style.display !== 'none');
            group.hidden = !!q && !visible;
        });
        if (noMatch) noMatch.hidden = !q || rows === 0 || shown > 0;
    };

    const render = (q, results) => {
        const panel = showOverlay();
        if (!panel) return;
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
        for (const c of results) list.append(channelRow(c, { withJoin: false }));
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
            hideOverlay();
            return;
        }
        timer = setTimeout(() => search(q), DEBOUNCE_MS);
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            input.value = '';
            sequence++;
            narrowShortlist('');
            hideOverlay();
            input.blur();
        }
    });
}

/**
 * Browse channels — the directory. Every live public channel, largest first, with a Join button
 * on the ones you are not in. The search box above it needs a name; this needs nothing, which is
 * the case a newcomer is in. Renders into the shared overlay, so typing in the filter takes the
 * panel over and clearing it closes whichever was showing.
 */
export function initBrowseChannels() {
    const btn = document.getElementById('sidebar-browse-btn');
    if (!btn) return;

    const render = (rows) => {
        const panel = showOverlay();
        if (!panel) return;
        panel.replaceChildren();
        const head = document.createElement('div');
        head.className = 'channel-search-head';
        const heading = document.createElement('h2');
        heading.textContent = 'Browse channels';
        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'icon-btn channel-search-close';
        close.title = 'Close';
        close.setAttribute('aria-label', 'Close');
        close.innerHTML = '<svg class="icon" aria-hidden="true"><use href="#icon-close"/></svg>';
        close.addEventListener('click', hideOverlay);
        head.append(heading, close);
        panel.append(head);
        const hint = document.createElement('p');
        hint.className = 'channel-search-hint';
        hint.textContent = rows.length
            ? 'Every public channel, most members first. Private channels are by invitation and '
              + 'are not listed.'
            : 'There are no public channels yet — use + in the sidebar to create the first one.';
        panel.append(hint);
        if (!rows.length) return;
        const list = document.createElement('ul');
        list.className = 'channel-search-list channel-browse-list';
        for (const c of rows) list.append(channelRow(c, { withJoin: true }));
        panel.append(list);
    };

    btn.addEventListener('click', async () => {
        btn.disabled = true;
        try {
            const res = await fetch('/api/channels/browse', { headers: headers() });
            if (!res.ok) throw new Error(String(res.status));
            render(await res.json());
        } catch (_) {
            flashToast('Could not load the channel directory');
        } finally {
            btn.disabled = false;
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && overlayPanel && !overlayPanel.hidden) hideOverlay();
    });
}

/**
 * The Join buttons on the first-login "Suggested for you" rows. Delegated from the sidebar, like
 * the stars, and bound here rather than inline because of the CSP.
 */
export function initSuggestionJoins() {
    document.getElementById('app-sidebar')?.addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-join-channel]');
        if (!btn) return;
        e.preventDefault();
        joinChannel(btn.dataset.joinChannel, btn);
    });
}

/**
 * The sidebar's favourite stars.
 *
 * The star means favourite, the way it does in Slack and Mattermost. (It used to mean "you are an
 * admin of this channel", which is not what a star means anywhere and is not information worth
 * carrying in a list you scan fifty times a day.)
 *
 * The row moves between the two groups immediately, without a reload: a star that only takes effect
 * on the next page load reads as a control that didn't work. Delegated from the sidebar element so it
 * survives rows being re-rendered, and bound from JS rather than inline because of the CSP.
 */
export function initFavouriteStars() {
    const sidebarEl = document.getElementById('app-sidebar');
    if (!sidebarEl) return;
    const channelList = document.getElementById('sidebar-channel-list');

    /** Insert li into list keeping the alphabetical order the server rendered. */
    const insertAlphabetically = (list, li) => {
        const name = li.dataset.name || '';
        const before = [...list.children].find((other) => (other.dataset.name || '') > name);
        list.insertBefore(li, before || null);
    };

    const ensureFavouriteGroup = () => {
        const existing = document.getElementById('sidebar-favourite-list');
        if (existing) return existing;
        if (!channelList) return null;
        const section = document.createElement('div');
        section.className = 'sidebar-section channel-group';
        const heading = document.createElement('h2');
        heading.textContent = 'Favourites';
        const list = document.createElement('ul');
        list.className = 'channel-list';
        list.id = 'sidebar-favourite-list';
        section.append(heading, list);
        channelList.closest('.sidebar-section').before(section);
        return list;
    };

    /**
     * Move a row into the Favourites group or back out of it.
     *
     * The Favourites heading only exists once something is starred, and the server does not render
     * it when the group is empty — so the first star has to create the group and the last unstar has
     * to remove it, or the row would have nowhere to go.
     */
    const regroup = (li, favourite) => {
        const target = favourite ? ensureFavouriteGroup() : channelList;
        if (!target) return;
        insertAlphabetically(target, li);
        const favourites = document.getElementById('sidebar-favourite-list');
        if (!favourite && favourites && favourites.children.length === 0) {
            favourites.closest('.sidebar-section')?.remove();
        }
    };

    const paintStar = (btn, favourite, name) => {
        btn.classList.toggle('is-favourite', favourite);
        btn.setAttribute('aria-pressed', favourite ? 'true' : 'false');
        btn.title = favourite ? 'Remove from favourites' : 'Add to favourites';
        btn.setAttribute('aria-label', (favourite ? 'Remove #' : 'Add #') + name
            + (favourite ? ' from favourites' : ' to favourites'));
        btn.querySelector('use')?.setAttribute('href',
            favourite ? '#icon-star' : '#icon-star-outline');
    };

    sidebarEl.addEventListener('click', async (e) => {
        const btn = e.target.closest('.channel-star');
        if (!btn) return;
        e.preventDefault();
        const li = btn.closest('li[data-channel-id]');
        if (!li || btn.disabled) return;
        const next = btn.getAttribute('aria-pressed') !== 'true';
        const name = li.querySelector('.channel-name')?.textContent || '';
        btn.disabled = true;
        // Paint first: this is a one-bit toggle on the user's own row, so the optimistic version is
        // right in the overwhelming majority of cases and the failure path below puts it back.
        paintStar(btn, next, name);
        regroup(li, next);
        try {
            const res = await fetch('/api/channels/' + li.dataset.channelId + '/favourite', {
                method: 'PUT', headers: headers(), body: JSON.stringify({ favourite: next }),
            });
            if (!res.ok) throw new Error(res.statusText);
            // Repaint from the server's answer, not from what we assumed, so two tabs racing each
            // other converge on what was stored.
            const stored = !!(await res.json().catch(() => ({ favourite: next }))).favourite;
            if (stored !== next) {
                paintStar(btn, stored, name);
                regroup(li, stored);
            }
        } catch (_) {
            paintStar(btn, !next, name);
            regroup(li, !next);
            flashToast('Could not save that favourite');
        } finally {
            btn.disabled = false;
        }
    });
}

/**
 * Create-channel: the sidebar "+" popover and the forms inside it.
 *
 * Here rather than in `chat/index.js` because the sidebar fragment renders on the channel page
 * *and* the conversation page, while only the channel page loads index.js — so wiring it there
 * left the "+" beside CHANNELS visibly present and completely dead whenever you were reading a
 * direct message. That is the drift the sidebar fragment's own comment warns about: one copy of
 * the markup, two copies of the behaviour, and only one of them kept up to date.
 *
 * The empty-state form (`create-channel-form`) only exists on the channel page; getElementById
 * returning null for it here is the normal case on the conversation page, not a failure.
 */
export function initCreateChannel() {
    const wireForm = (formId) => {
        const form = document.getElementById(formId);
        if (!form) return;
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const data = Object.fromEntries(new FormData(form).entries());
            const res = await fetch('/api/channels', {
                method: 'POST',
                headers: headers(),
                body: JSON.stringify(data),
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({ error: res.statusText }));
                alert('Could not create channel: ' + err.error);
                return;
            }
            const channel = await res.json();
            window.location.href = '/channels/' + channel.id;
        });
    };
    wireForm('create-channel-form');
    wireForm('create-channel-form-sidebar');

    // Bound here rather than inline because the CSP forbids inline handlers (script-src 'self').
    // wirePopover lives in chat-kit.js, which both pages load. The "New message" popover beside
    // the Direct messages heading wires itself there too, for the same reason.
    window.ChatKit?.wirePopover('sidebar-create-add-btn', 'sidebar-create-popover',
        'input[name="name"]');
}

export function init() {
    initTutorial();
    initSidebarSearch();
    initBrowseChannels();
    initSuggestionJoins();
    initFavouriteStars();
    initCreateChannel();
}
