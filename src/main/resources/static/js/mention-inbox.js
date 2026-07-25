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
 * Topbar bell + inbox dropdown for unread @-mentions. Reads the inbox from
 * /api/mentions on page load and after a mention WS event arrives; clicking an
 * item navigates to the channel + message anchor (which marks the channel read,
 * naturally clearing that mention from the list on the next refresh).
 *
 * Public surface:
 *   window.MentionInbox = { refresh(), notifyMention(), markAllRead(), open(), close() };
 */
(function () {
  if (window.MentionInbox) return;

  const FETCH_LIMIT = 20;
  /** Coalesce a burst of WS mentions into one /api/mentions fetch. */
  const REFRESH_DEBOUNCE_MS = 200;

  const btn = document.getElementById('topbar-bell-btn');
  const panel = document.getElementById('topbar-bell-panel');
  const list = document.getElementById('topbar-bell-list');
  const empty = document.getElementById('topbar-bell-empty');
  const countBadge = document.getElementById('topbar-bell-count');
  const markAllBtn = document.getElementById('topbar-bell-mark-all');

  // Bail out gracefully if the bell isn't on this page (e.g. landing).
  if (!btn || !panel || !list) return;

  let lastFetched = [];
  let refreshPending = null;

  function csrfHeaders(extra) {
    const headers = Object.assign({ 'Accept': 'application/json' }, extra || {});
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    if (token && header) headers[header] = token;
    return headers;
  }

  function setCount(n) {
    if (n > 0) {
      countBadge.hidden = false;
      countBadge.textContent = n > 99 ? '99+' : String(n);
      btn.classList.add('has-mentions');
    } else {
      countBadge.hidden = true;
      countBadge.textContent = '0';
      btn.classList.remove('has-mentions');
    }
  }

  function formatRelative(iso) {
    if (!iso) return '';
    const ts = new Date(iso).getTime();
    const diff = Date.now() - ts;
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return Math.floor(diff / 60_000) + 'm ago';
    if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + 'h ago';
    return Math.floor(diff / 86_400_000) + 'd ago';
  }

  function renderList(items) {
    list.replaceChildren();
    if (!items.length) {
      empty.hidden = false;
      return;
    }
    empty.hidden = true;
    for (const it of items) {
      const li = document.createElement('li');
      li.className = 'topbar-bell-item';

      const link = document.createElement('a');
      // ?m= renders server-side context around an older message; #m= is what the permalink
      // scroller matches. The old '#m-' matched neither and lost the highlight.
      link.href = '/channels/' + encodeURIComponent(it.channelId)
          + '?m=' + encodeURIComponent(it.messageId) + '#m=' + encodeURIComponent(it.messageId);

      const head = document.createElement('div');
      head.className = 'topbar-bell-item-head';
      const author = document.createElement('span');
      author.className = 'topbar-bell-item-author';
      author.textContent = it.authorDisplayName || it.authorUsername || 'someone';
      const channel = document.createElement('span');
      channel.className = 'topbar-bell-item-channel';
      channel.textContent = '#' + (it.channelName || '');
      const when = document.createElement('span');
      when.className = 'topbar-bell-item-time';
      when.textContent = formatRelative(it.createdAt);
      head.append(author, channel, when);

      const snippet = document.createElement('p');
      snippet.className = 'topbar-bell-item-snippet';
      snippet.textContent = it.snippet || '';

      link.append(head, snippet);
      li.append(link);
      list.append(li);
    }
  }

  async function refresh() {
    try {
      const [inboxRes, countRes] = await Promise.all([
        fetch('/api/mentions?limit=' + FETCH_LIMIT, { headers: csrfHeaders(), credentials: 'same-origin' }),
        fetch('/api/mentions/count', { headers: csrfHeaders(), credentials: 'same-origin' }),
      ]);
      if (inboxRes.ok) {
        lastFetched = await inboxRes.json();
        renderList(lastFetched);
      }
      if (countRes.ok) {
        const body = await countRes.json();
        setCount(body.unread || 0);
      }
    } catch (e) {
      // Network error — leave the existing badge in place.
    }
  }

  /**
   * Called by chat.js when an @mention WS event arrives. Bumps the badge optimistically
   * (so the count is responsive even before the network round-trip), then debounces a
   * /api/mentions refresh so the dropdown reflects the new row whether or not the user
   * has the panel open. The debounce coalesces simultaneous mentions across multiple
   * channel topics into a single fetch.
   */
  function notifyMention() {
    const next = (parseInt(countBadge.textContent.replace('+', ''), 10) || 0) + 1;
    setCount(next);
    if (refreshPending) clearTimeout(refreshPending);
    refreshPending = setTimeout(() => {
      refreshPending = null;
      refresh();
    }, REFRESH_DEBOUNCE_MS);
  }

  // Old name preserved for compatibility with chat.js callsites.
  const bumpCount = notifyMention;

  async function markAllRead() {
    if (!markAllBtn) return;
    const wasDisabled = markAllBtn.disabled;
    markAllBtn.disabled = true;
    try {
      const res = await fetch('/api/mentions/read-all', {
        method: 'POST',
        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        credentials: 'same-origin',
      });
      if (!res.ok) return;
      // Server has advanced last_read_at for every affected channel. Clear local state
      // and pull fresh server truth so any other tabs / sidebar badges agree.
      setCount(0);
      lastFetched = [];
      renderList([]);
      refresh();
    } catch (e) {
      // Leave the inbox alone if the call failed.
    } finally {
      markAllBtn.disabled = wasDisabled;
    }
  }

  function open() {
    panel.hidden = false;
    btn.setAttribute('aria-expanded', 'true');
    refresh();
  }

  function close() {
    panel.hidden = true;
    btn.setAttribute('aria-expanded', 'false');
  }

  function toggle() {
    if (panel.hidden) open(); else close();
  }

  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    toggle();
  });

  if (markAllBtn) {
    markAllBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      markAllRead();
    });
  }

  // Click-outside dismisses; Escape too.
  document.addEventListener('click', (e) => {
    if (panel.hidden) return;
    if (!panel.contains(e.target) && e.target !== btn && !btn.contains(e.target)) {
      close();
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !panel.hidden) close();
  });

  window.MentionInbox = { refresh, notifyMention, bumpCount, markAllRead, open, close };

  // Prime the badge on page load so the bell is correct before any WS event.
  refresh();
})();
