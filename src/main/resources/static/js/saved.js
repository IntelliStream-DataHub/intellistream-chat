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
 * Saved items (saved.html): the signed-in account's private reading queue, newest first.
 *
 * A classic script rather than an ES module, matching files.js, and a static file rather than an
 * inline <script> so the page runs under the strict CSP.
 *
 * Everything except the message body is built with createElement + textContent. The body is the
 * server's rendered-and-sanitized bodyHtml — the same string the feed renders with innerHTML, from
 * MarkdownRenderer + jsoup — and nothing else on this page may use innerHTML, because every other
 * field (channel names, display names) is user-supplied.
 */
(function () {
  const list = document.getElementById('saved-list');
  if (!list) return;

  const countEl = document.getElementById('saved-count');
  const errorEl = document.getElementById('saved-error');
  const pagerEl = document.getElementById('saved-pager');
  const prevBtn = document.getElementById('saved-prev');
  const nextBtn = document.getElementById('saved-next');
  const pageLabel = document.getElementById('saved-page-label');

  const PAGE_SIZE = 25;
  let page = 0;
  // Seeded from the server-rendered figure, so an unsave on a full first page decrements the real
  // total rather than counting down from zero.
  let lastCount = Number(countEl?.dataset.count || 0) || 0;

  const meta = (name) => document.querySelector('meta[name="' + name + '"]')?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');
  const headers = () => {
    const h = { 'Content-Type': 'application/json' };
    if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
    return h;
  };

  const formatWhen = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
        + ' · ' + d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  };

  const showError = (msg) => {
    if (!errorEl) return;
    errorEl.textContent = msg;
    errorEl.hidden = !msg;
  };

  const iconEl = (name) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'icon icon-sm');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#icon-' + name);
    svg.append(use);
    return svg;
  };

  /* Where the message lives: "#general", "#general (archived)", or a DM's counterpart. */
  const renderWhere = (row) => {
    const where = document.createElement('span');
    where.className = 'saved-where';
    if (row.kind === 'channel') {
      where.append(iconEl(row.channelPrivate ? 'lock' : 'group'));
      const name = document.createElement('span');
      name.textContent = row.channelName || 'a channel';
      where.append(name);
      if (row.channelArchived) {
        const chip = document.createElement('span');
        chip.className = 'saved-chip';
        chip.textContent = 'archived';
        where.append(chip);
      }
    } else {
      where.append(iconEl('send'));
      const name = document.createElement('span');
      name.textContent = row.conversationTitle || 'a direct message';
      where.append(name);
    }
    return where;
  };

  const unsave = async (row, li) => {
    const path = row.kind === 'channel'
        ? '/api/saved/messages/' + encodeURIComponent(row.messageId)
        : '/api/saved/conversation-messages/' + encodeURIComponent(row.messageId);
    const res = await fetch(path, { method: 'DELETE', headers: headers() });
    if (!res.ok && res.status !== 204) {
      showError('Could not remove that item.');
      return;
    }
    // Remove locally rather than re-fetching: the page the user is looking at stays put, which
    // matters most when they are working down a queue and clearing as they go.
    li.remove();
    renderCount(Math.max(0, lastCount - 1));
    if (!list.children.length) load(page > 0 ? page - 1 : 0);
  };

  const renderCount = (n) => {
    lastCount = n;
    if (!countEl) return;
    countEl.dataset.count = String(n);
    countEl.textContent = n + (n === 1 ? ' saved message' : ' saved messages');
  };

  const renderRow = (row) => {
    const li = document.createElement('li');
    li.className = 'saved-item' + (row.readable ? '' : ' is-unreadable');

    const head = document.createElement('div');
    head.className = 'saved-meta';
    head.append(renderWhere(row));
    if (row.readable) {
      const author = document.createElement('span');
      author.className = 'saved-author';
      author.textContent = row.authorDisplayName || row.authorUsername || '';
      head.append(author);
    }
    const when = document.createElement('time');
    when.className = 'saved-when';
    when.textContent = formatWhen(row.createdAt || row.savedAt);
    head.append(when);
    li.append(head);

    const body = document.createElement('div');
    if (row.readable) {
      // Server-rendered, server-sanitized markdown — the identical string the feed renders.
      body.className = 'message-body saved-body';
      body.innerHTML = row.bodyHtml || '';
    } else {
      // The save outlived the access. Say so plainly and keep the row so it can be cleared —
      // a bookmark that silently vanishes reads as data loss, and one that 500s reads as a bug.
      body.className = 'saved-body saved-unavailable';
      body.textContent = row.kind === 'channel'
          ? 'This message is in a channel you no longer have access to.'
          : 'This message is in a conversation you are no longer part of.';
    }
    li.append(body);

    const actions = document.createElement('div');
    actions.className = 'saved-actions';
    if (row.readable && row.url) {
      const open = document.createElement('a');
      open.className = 'link-btn';
      open.href = row.url;
      open.textContent = 'Go to message';
      actions.append(open);
    }
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'link-btn';
    remove.textContent = 'Remove';
    remove.addEventListener('click', () => unsave(row, li));
    actions.append(remove);
    li.append(actions);
    return li;
  };

  const renderEmpty = () => {
    list.textContent = '';
    const li = document.createElement('li');
    li.className = 'files-empty';
    li.textContent = page > 0
        ? 'Nothing more here.'
        : 'Nothing saved yet. Use "Save for later" in a message’s actions to put it here.';
    list.append(li);
  };

  async function load(which) {
    page = Math.max(0, which);
    showError('');
    try {
      const res = await fetch('/api/saved?page=' + page + '&size=' + PAGE_SIZE,
          { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
      if (!res.ok) throw new Error('saved load failed: ' + res.status);
      const rows = await res.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        renderEmpty();
        if (page === 0) renderCount(0);
        if (pagerEl) pagerEl.hidden = page === 0;
      } else {
        list.textContent = '';
        for (const row of rows) list.append(renderRow(row));
        // A short first page is the whole list, so it is also the exact count. A full one only
        // proves there are at least this many, and the server's figure is still the better answer.
        if (page === 0 && rows.length < PAGE_SIZE) renderCount(rows.length);
        if (pagerEl) pagerEl.hidden = page === 0 && rows.length < PAGE_SIZE;
      }
      if (pageLabel) pageLabel.textContent = 'Page ' + (page + 1);
      if (prevBtn) prevBtn.disabled = page === 0;
      if (nextBtn) nextBtn.disabled = !Array.isArray(rows) || rows.length < PAGE_SIZE;
      window.ChatKit?.backfillAvatarColors?.();
    } catch (e) {
      showError('Could not load your saved messages.');
      list.textContent = '';
    }
  }

  prevBtn?.addEventListener('click', () => load(page - 1));
  nextBtn?.addEventListener('click', () => load(page + 1));

  load(0);
})();
