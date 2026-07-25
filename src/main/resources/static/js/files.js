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
 * File manager (files.html): lists the signed-in account's own uploads, searches them by
 * filename, and deletes them.
 *
 * A classic script rather than an ES module, matching profile.js/admin.js, and a static file
 * rather than an inline <script> so the page runs under the strict CSP.
 *
 * Every row is built with createElement + textContent. Filenames are user-supplied and arrive
 * from another user's upload in no case at all here — they are all this account's own — but
 * "it's only my own data" is exactly the assumption that stops being true the day someone adds
 * a shared view, and innerHTML on a filename is stored XSS the moment it does.
 */
(function () {
  const tbody = document.getElementById('files-tbody');
  if (!tbody) return;

  const searchInput = document.getElementById('files-search');
  const countEl = document.getElementById('files-count');
  const errorEl = document.getElementById('files-error');
  const pagerEl = document.getElementById('files-pager');
  const prevBtn = document.getElementById('files-prev');
  const nextBtn = document.getElementById('files-next');
  const pageLabel = document.getElementById('files-page-label');
  const storageEl = document.getElementById('files-storage');
  const meterEl = document.getElementById('files-meter');
  const meterFill = document.getElementById('files-meter-fill');

  // Shared helper, so "1.5 MB" reads the same here as on a message's attachment chip.
  const formatBytes = (window.ChatKit && window.ChatKit.formatBytes)
      ? window.ChatKit.formatBytes
      : (n) => (n == null ? '' : n + ' B');

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

  function renderRow(file) {
    const tr = document.createElement('tr');
    if (!file.deletable) tr.classList.add('is-held');

    // File — the name links to the download, so the page is also a way to get a file back.
    const nameCell = document.createElement('td');
    nameCell.className = 'files-name';
    const link = document.createElement('a');
    link.href = file.downloadUrl;
    link.textContent = file.filename;
    link.title = file.filename;
    const clip = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    clip.setAttribute('class', 'icon-sm');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#icon-paperclip');
    clip.appendChild(use);
    nameCell.append(clip, link);

    const sizeCell = document.createElement('td');
    sizeCell.className = 'files-size';
    sizeCell.textContent = formatBytes(file.sizeBytes);

    const typeCell = document.createElement('td');
    typeCell.className = 'files-type';
    typeCell.textContent = shortType(file.contentType);
    typeCell.title = file.contentType || '';

    // Posted in — the link is the answer to "which message holds this?", which is the whole
    // point when the delete is refused.
    const whereCell = document.createElement('td');
    const whereLink = document.createElement('a');
    whereLink.href = file.locationUrl;
    whereLink.textContent = file.locationLabel;
    whereCell.appendChild(whereLink);
    if (file.locationKind !== 'channel') {
      const badge = document.createElement('span');
      badge.className = 'files-badge';
      badge.textContent = file.locationKind === 'group' ? 'group' : 'DM';
      whereCell.append(' ', badge);
    }

    const whenCell = document.createElement('td');
    whenCell.className = 'files-when';
    const when = new Date(file.createdAt);
    whenCell.textContent = when.toLocaleDateString(undefined,
        { year: 'numeric', month: 'short', day: 'numeric' });
    whenCell.title = when.toLocaleString();

    const actionCell = document.createElement('td');
    actionCell.className = 'files-actions';
    if (file.deletable) {
      const del = document.createElement('button');
      del.type = 'button';
      del.className = 'files-delete';
      del.title = 'Delete this file and the message that posted it';
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('class', 'icon');
      const u2 = document.createElementNS('http://www.w3.org/2000/svg', 'use');
      u2.setAttribute('href', '#icon-trash');
      svg.appendChild(u2);
      del.appendChild(svg);
      const label = document.createElement('span');
      label.className = 'visually-hidden';
      label.textContent = 'Delete ' + file.filename;
      del.appendChild(label);
      del.addEventListener('click', () => remove(file, del));
      actionCell.appendChild(del);
    } else {
      // The refusal is shown as text on the row, not hidden behind a disabled button whose
      // tooltip nobody finds. blockedReason is composed server-side by the same code the
      // endpoint refuses with, so the page can never promise something the API declines.
      const held = document.createElement('span');
      held.className = 'files-held';
      held.textContent = 'Kept';
      held.title = file.blockedReason || '';
      actionCell.appendChild(held);

      const why = document.createElement('div');
      why.className = 'files-held-why';
      why.textContent = file.blockedReason || '';
      nameCell.appendChild(why);
    }

    tr.append(nameCell, sizeCell, typeCell, whereCell, whenCell, actionCell);
    return tr;
  }

  /**
   * Redraw the "N used of M" line and its meter. Driven by user_storage — the ledger the upload
   * path actually enforces — not by summing the visible rows, which would go on claiming free
   * space that an upload would still be refused for.
   */
  function renderStorage(used, quota) {
    if (!storageEl || used == null) return;
    storageEl.dataset.used = String(used);
    if (quota != null) storageEl.dataset.quota = String(quota);
    const limit = Number(storageEl.dataset.quota);
    storageEl.textContent = '';
    const strong = document.createElement('strong');
    strong.textContent = formatBytes(used);
    storageEl.appendChild(strong);
    storageEl.append(limit > 0
        ? ' used of ' + formatBytes(limit) + '. Deleting a file here gives its bytes straight back.'
        : ' used. This account has no storage limit.');
    if (meterEl && meterFill && limit > 0) {
      const pct = Math.min(100, Math.round((used * 100) / limit));
      meterFill.style.width = pct + '%';
      meterEl.setAttribute('aria-valuenow', String(pct));
    }
  }

  function render(data) {
    if (!data.files.length) {
      placeholderRow(query
          ? 'No files match “' + query + '”.'
          : 'You have not uploaded any files yet.');
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
    renderStorage(data.quotaUsedBytes, data.quotaBytes);
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
    try {
      const res = await fetch('/api/files' + (params.toString() ? '?' + params : ''), {
        headers: headers(),
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      // Keystrokes race: only the newest request may paint, or a slow early response
      // overwrites the results for what the user has since typed.
      if (token !== inFlight) return;
      render(data);
    } catch (err) {
      if (token !== inFlight) return;
      placeholderRow('Could not load your files.');
      showError('Could not load your files: ' + (err && err.message ? err.message : err));
    }
  }

  async function remove(file, button) {
    const ok = window.confirm(
        'Delete “' + file.filename + '”?\n\n'
        + 'The message stays and will show that the file was deleted, by you, just now. '
        + 'The file itself cannot be recovered.');
    if (!ok) return;
    button.disabled = true;
    showError('');
    try {
      const res = await fetch('/api/files/' + encodeURIComponent(file.scope)
          + '/' + encodeURIComponent(file.id), {
        method: 'DELETE',
        headers: headers(),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        // 409 carries the delete policy's own explanation; show it verbatim rather than
        // paraphrasing it into something vaguer than the server's reason.
        showError(body.message || ('Could not delete the file (' + res.status + ').'));
        button.disabled = false;
        return;
      }
      // Reload rather than splice the row out: the totals, the page contents and whether a
      // pager is needed at all have all just changed.
      await load();
    } catch (err) {
      showError('Could not delete the file: ' + (err && err.message ? err.message : err));
      button.disabled = false;
    }
  }

  let debounce = null;
  if (searchInput) {
    searchInput.addEventListener('input', () => {
      clearTimeout(debounce);
      debounce = setTimeout(() => {
        query = searchInput.value.trim();
        page = 0; // a new search always starts at the first page
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

  // Re-scale the server-rendered figure immediately: the template can only manage fixed MB, so a
  // 15 KB account reads "0.0 MB" until this runs.
  if (storageEl) renderStorage(Number(storageEl.dataset.used), Number(storageEl.dataset.quota));
  load();
})();
