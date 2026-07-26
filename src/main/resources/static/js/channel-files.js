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
 * Channel files (channel-files.html): every file shared in one channel, filterable by name.
 *
 * Sibling of files.js and deliberately shaped like it — same table, same pager, same debounce —
 * but with no delete: removing a file is the uploader's own business and lives on /files. What
 * this page adds is an uploader column, because "who shared it" is half of how anybody finds a
 * file they only half remember.
 *
 * A classic script rather than an ES module, matching files.js/saved.js, and a static file rather
 * than an inline <script> so the page runs under the strict CSP.
 *
 * Every row is built with createElement + textContent. Filenames here really do come from other
 * people's uploads — this is the first surface in the app where that is true — so innerHTML on one
 * would be stored XSS with a straight face.
 */
(function () {
  const tbody = document.getElementById('channel-files-tbody');
  const table = document.getElementById('channel-files-table');
  if (!tbody || !table) return;

  const channelId = table.dataset.channelId;
  if (!channelId) return;

  const searchInput = document.getElementById('channel-files-search');
  const countEl = document.getElementById('channel-files-count');
  const errorEl = document.getElementById('channel-files-error');
  const pagerEl = document.getElementById('channel-files-pager');
  const prevBtn = document.getElementById('channel-files-prev');
  const nextBtn = document.getElementById('channel-files-next');
  const pageLabel = document.getElementById('channel-files-page-label');

  const kit = window.ChatKit || {};
  // Shared helpers, so a size and an avatar read the same here as in the feed.
  const formatBytes = kit.formatBytes || ((n) => (n == null ? '' : n + ' B'));
  const buildAvatarEl = kit.buildAvatarEl || null;

  const meta = (name) => document.querySelector('meta[name="' + name + '"]')?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');

  const headers = () => {
    const h = { 'Content-Type': 'application/json' };
    if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
    return h;
  };

  let page = 0;
  let query = '';
  let inFlight = 0;

  function showError(text) {
    if (!errorEl) return;
    if (!text) {
      errorEl.hidden = true;
      errorEl.textContent = '';
      return;
    }
    errorEl.textContent = text;
    errorEl.hidden = false;
  }

  function placeholderRow(text) {
    tbody.textContent = '';
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 6;
    td.className = 'files-empty';
    td.textContent = text;
    tr.appendChild(td);
    tbody.appendChild(tr);
  }

  /** Short, human type: "image/png" -> "PNG", "application/pdf" -> "PDF". */
  function shortType(contentType) {
    if (!contentType) return '—';
    const sub = contentType.split(';')[0].split('/')[1] || contentType;
    const cleaned = sub.replace(/^x-/, '').replace(/^vnd\..*[.+-]/, '');
    return cleaned.length <= 12 ? cleaned.toUpperCase() : contentType;
  }

  function iconEl(symbol, cls) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', cls);
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#' + symbol);
    svg.appendChild(use);
    return svg;
  }

  function uploaderCell(file) {
    const cell = document.createElement('td');
    cell.className = 'files-uploader';
    const name = file.uploaderDisplayName || file.uploaderUsername || '';
    if (buildAvatarEl) {
      cell.appendChild(buildAvatarEl({
        username: file.uploaderUsername,
        hasAvatar: file.uploaderHasAvatar,
        avatarVersion: file.uploaderAvatarVersion,
        letter: (name.charAt(0) || '?').toUpperCase(),
      }));
    }
    const label = document.createElement('span');
    label.textContent = name;
    label.title = file.uploaderUsername || '';
    cell.appendChild(label);
    return cell;
  }

  function renderRow(file) {
    const tr = document.createElement('tr');

    // File — the name links to the download, which is what this page is for.
    const nameCell = document.createElement('td');
    nameCell.className = 'files-name';
    const link = document.createElement('a');
    link.href = file.downloadUrl;
    link.textContent = file.filename;
    link.title = file.filename;
    nameCell.append(iconEl('icon-paperclip', 'icon-sm'), link);

    const sizeCell = document.createElement('td');
    sizeCell.className = 'files-size';
    sizeCell.textContent = formatBytes(file.sizeBytes);

    const typeCell = document.createElement('td');
    typeCell.className = 'files-type';
    typeCell.textContent = shortType(file.contentType);
    typeCell.title = file.contentType || '';

    const whenCell = document.createElement('td');
    whenCell.className = 'files-when';
    const when = new Date(file.createdAt);
    whenCell.textContent = when.toLocaleDateString(undefined,
        { year: 'numeric', month: 'short', day: 'numeric' });
    whenCell.title = when.toLocaleString();

    // The link back to the message. A file on its own answers "what"; the message answers "why",
    // and that is usually the thing somebody is actually looking for.
    const gotoCell = document.createElement('td');
    gotoCell.className = 'files-actions';
    const gotoLink = document.createElement('a');
    gotoLink.className = 'files-goto';
    gotoLink.href = file.messageUrl;
    gotoLink.title = 'Open the message this file was posted with';
    gotoLink.appendChild(iconEl('icon-thread', 'icon'));
    const gotoLabel = document.createElement('span');
    gotoLabel.className = 'visually-hidden';
    gotoLabel.textContent = 'Open the message that posted ' + file.filename;
    gotoLink.appendChild(gotoLabel);
    gotoCell.appendChild(gotoLink);

    tr.append(nameCell, sizeCell, typeCell, uploaderCell(file), whenCell, gotoCell);
    return tr;
  }

  function render(data) {
    if (!data.files.length) {
      placeholderRow(query
          ? 'No files in this channel match “' + query + '”.'
          : 'No files have been shared in this channel yet.');
    } else {
      tbody.textContent = '';
      const frag = document.createDocumentFragment();
      data.files.forEach((f) => frag.appendChild(renderRow(f)));
      tbody.appendChild(frag);
    }

    if (countEl) {
      const n = data.total;
      countEl.textContent = n === 1 ? '1 file' : n + ' files';
    }
    if (pagerEl) {
      const multi = data.page > 0 || data.hasMore;
      pagerEl.hidden = !multi;
      if (multi) {
        prevBtn.disabled = data.page === 0;
        nextBtn.disabled = !data.hasMore;
        pageLabel.textContent = 'Page ' + (data.page + 1);
      }
    }
  }

  async function load() {
    const token = ++inFlight;
    showError('');
    const params = new URLSearchParams();
    if (query) params.set('q', query);
    if (page) params.set('page', String(page));
    const url = '/api/channels/' + encodeURIComponent(channelId) + '/files'
        + (params.toString() ? '?' + params : '');
    try {
      const res = await fetch(url, { headers: headers() });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      // Keystrokes race: only the newest request may paint, or a slow early response
      // overwrites the results for what the user has since typed.
      if (token !== inFlight) return;
      render(data);
    } catch (err) {
      if (token !== inFlight) return;
      placeholderRow('Could not load this channel’s files.');
      showError('Could not load this channel’s files: '
          + (err && err.message ? err.message : err));
    }
  }

  let debounce = null;
  if (searchInput) {
    searchInput.addEventListener('input', () => {
      clearTimeout(debounce);
      debounce = setTimeout(() => {
        query = searchInput.value.trim();
        page = 0; // a new filter always starts at the first page
        load();
      }, 200);
    });
  }
  if (prevBtn) {
    prevBtn.addEventListener('click', () => {
      if (page > 0) { page -= 1; load(); }
    });
  }
  if (nextBtn) {
    nextBtn.addEventListener('click', () => { page += 1; load(); });
  }

  load();
})();
