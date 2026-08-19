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
 * "Find user" — browse up to 100 accounts and act on one, for when you know roughly who you're
 * looking for but not their exact handle. Two callers, one dialog:
 *
 *   - Channel settings (opts.channelId): candidates not already in the channel, and the row
 *     action POSTs to the existing invite endpoint — a find-then-add and a type-the-handle
 *     invite are the same write underneath. Closing reloads the page if anything was added.
 *   - The new-conversation form (opts.onToggle): the whole directory, and the row action only
 *     toggles the person in and out of the caller's selection — nothing is written until the
 *     form itself submits, so browsing costs nothing and backing out costs nothing.
 *
 * Shaped like poll-modal.js / forward-dialog.js so the app's dialogs keep behaving the same way:
 * same backdrop, same close-on-Escape/click-outside, no inline handlers (strict CSP). Bigger than
 * either, on purpose — it's the one dialog in the app meant to hold a scrollable result list.
 *
 * Filtering and sorting happen server-side (invite-candidates / users/directory share the same
 * parameters) and are debounced client-side; nothing here re-implements the wildcard/domain
 * matching, it just builds the query string and renders what comes back.
 */

const SEARCH_DEBOUNCE_MS = 300;

let modalEl = null;
let debounceHandle = null;
let requestSeq = 0;
// Whether at least one add succeeded this time the dialog was open — reloading on every single
// add would fight the point of a browse-and-add-several dialog by closing it out from under you;
// reloading once when it closes, only if anything changed, gets the static member list and the
// header's member count caught up the same way a plain invite already does, without the churn.
let addedAny = false;

export function closeFindUserModal() {
  if (!modalEl) return;
  document.removeEventListener('keydown', onKeydown, true);
  clearTimeout(debounceHandle);
  modalEl.remove();
  modalEl = null;
  if (addedAny) window.location.reload();
}

// Capture phase + stopPropagation, so one Escape closes one layer: the page's other
// document-level Escape handlers (the sidebar popover's, the settings panel's) never see the
// press that dismissed this dialog. Without it, closing the dialog also swept away whatever it
// was opened from — which in pick mode holds the selection just made.
function onKeydown(e) {
  if (e.key !== 'Escape') return;
  e.stopPropagation();
  closeFindUserModal();
}

function formatRelative(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const diff = Date.now() - d.getTime();
  const sec = Math.round(diff / 1000);
  if (sec < 60) return 'just now';
  const min = Math.round(sec / 60);
  if (min < 60) return min + ' min ago';
  const hr = Math.round(min / 60);
  if (hr < 24) return hr + ' h ago';
  const day = Math.round(hr / 24);
  if (day < 30) return day + ' d ago';
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/**
 * Channel-invite mode:
 * @param opts.channelId  the channel to add people to
 * @param opts.headers    () => fetch headers, including CSRF
 *
 * Pick mode (new-conversation form):
 * @param opts.onToggle   (user) => boolean — flip the user in/out of the caller's selection,
 *                        returning whether they are selected now
 * @param opts.isSelected (username) => boolean — how rows render their initial state
 * @param opts.title      dialog heading (defaults to "Find user")
 */
export function openFindUserModal(opts) {
  closeFindUserModal();
  addedAny = false;
  const pickMode = !opts.channelId;

  modalEl = document.createElement('div');
  modalEl.className = 'poll-modal-backdrop find-user-modal-backdrop';
  modalEl.innerHTML =
      '<div class="poll-modal find-user-modal" role="dialog" aria-modal="true"' +
           ' aria-labelledby="find-user-modal-title">' +
        '<header class="poll-modal-head">' +
          '<h2 id="find-user-modal-title"></h2>' +
          '<button type="button" class="icon-btn find-user-modal-close" aria-label="Close">' +
            '<svg class="icon"><use href="#icon-close"/></svg>' +
          '</button>' +
        '</header>' +
        '<div class="find-user-modal-body">' +
          '<div class="find-user-filters">' +
            '<label class="poll-field">Username' +
              '<input type="search" class="find-user-username" autocomplete="off" maxlength="100"' +
                    ' placeholder="e.g. ali* or ali?e"/>' +
            '</label>' +
            '<label class="poll-field">Email domain' +
              '<input type="search" class="find-user-domain" autocomplete="off" maxlength="255"' +
                    ' placeholder="e.g. example.com"/>' +
            '</label>' +
            '<label class="poll-field find-user-sort-field">Sort' +
              '<select class="find-user-sort">' +
                '<option value="recent" selected>Recently created</option>' +
                '<option value="username">Username A–Z</option>' +
              '</select>' +
            '</label>' +
          '</div>' +
          '<p class="find-user-status"></p>' +
          '<ul class="find-user-results" aria-label="Users"></ul>' +
        '</div>' +
      '</div>';
  document.body.appendChild(modalEl);
  modalEl.querySelector('#find-user-modal-title').textContent = opts.title || 'Find user';

  const usernameInput = modalEl.querySelector('.find-user-username');
  const domainInput = modalEl.querySelector('.find-user-domain');
  const sortSelect = modalEl.querySelector('.find-user-sort');
  const statusEl = modalEl.querySelector('.find-user-status');
  const listEl = modalEl.querySelector('.find-user-results');

  const buildAvatar = (window.ChatKit && window.ChatKit.buildAvatarEl) || ((row) => {
    const span = document.createElement('span');
    span.className = 'avatar';
    const letter = document.createElement('span');
    letter.className = 'avatar-letter';
    letter.textContent = (row.letter || '?');
    span.appendChild(letter);
    return span;
  });

  const renderRow = (u) => {
    const li = document.createElement('li');
    li.className = 'find-user-row';
    const name = u.displayName || u.username;
    li.appendChild(buildAvatar({
      username: u.username,
      letter: (name || '?').slice(0, 1).toUpperCase(),
      hasAvatar: u.hasAvatar,
      avatarVersion: u.avatarVersion,
    }));
    const label = document.createElement('span');
    label.className = 'member-name find-user-name';
    label.textContent = name;
    const handle = document.createElement('small');
    handle.className = 'member-handle find-user-handle';
    handle.textContent = '@' + u.username;
    const created = document.createElement('small');
    created.className = 'find-user-created';
    const rel = formatRelative(u.createdAt);
    created.textContent = rel ? 'Joined ' + rel : '';
    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'find-user-add';
    const setPicked = (picked) => {
      li.classList.toggle('is-picked', picked);
      addBtn.title = picked ? 'Remove ' + name : 'Add ' + name;
      addBtn.innerHTML = '<svg class="icon icon-sm" aria-hidden="true"><use href="#icon-'
          + (picked ? 'check' : 'user-plus') + '"/></svg>';
    };
    if (pickMode) {
      setPicked(!!(opts.isSelected && opts.isSelected(u.username)));
      addBtn.addEventListener('click', () => setPicked(opts.onToggle(u)));
    } else {
      addBtn.title = 'Add ' + name + ' to this channel';
      addBtn.innerHTML = '<svg class="icon icon-sm" aria-hidden="true"><use href="#icon-user-plus"/></svg>';
      addBtn.addEventListener('click', async () => {
        addBtn.disabled = true;
        try {
          const res = await fetch(`/api/channels/${opts.channelId}/invite`, {
            method: 'POST',
            headers: opts.headers(),
            body: JSON.stringify({ username: u.username }),
          });
          if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.message || err.error || res.statusText);
          }
          li.classList.add('is-added');
          addBtn.replaceWith(iconCheck());
          addedAny = true;
        } catch (e) {
          addBtn.disabled = false;
          statusEl.textContent = 'Could not add ' + name + ': ' + e.message;
        }
      });
    }
    li.append(label, handle, created, addBtn);
    return li;
  };

  const iconCheck = () => {
    const span = document.createElement('span');
    span.className = 'find-user-added-mark';
    span.title = 'Added';
    span.innerHTML = '<svg class="icon icon-sm" aria-hidden="true"><use href="#icon-check"/></svg>';
    return span;
  };

  const search = async () => {
    const mySeq = ++requestSeq;
    statusEl.textContent = 'Loading…';
    const params = new URLSearchParams({
      username: usernameInput.value.trim(),
      emailDomain: domainInput.value.trim(),
      recent: sortSelect.value === 'recent' ? 'true' : 'false',
    });
    const url = pickMode
        ? `/api/users/directory?${params}`
        : `/api/channels/${opts.channelId}/invite-candidates?${params}`;
    try {
      const res = await fetch(url,
          { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
      if (mySeq !== requestSeq) return;   // superseded by a newer keystroke
      if (!res.ok) throw new Error('search failed: ' + res.status);
      const rows = await res.json();
      listEl.textContent = '';
      if (!rows.length) {
        statusEl.textContent = 'No one matches those filters.';
        return;
      }
      statusEl.textContent = rows.length >= 100
          ? 'Showing the first 100 matches — narrow the filters to find someone further down.'
          : rows.length + ' ' + (rows.length === 1 ? 'person' : 'people');
      for (const u of rows) listEl.appendChild(renderRow(u));
    } catch (e) {
      if (mySeq !== requestSeq) return;
      statusEl.textContent = 'Could not load users.';
    }
  };

  const scheduleSearch = () => {
    clearTimeout(debounceHandle);
    debounceHandle = setTimeout(search, SEARCH_DEBOUNCE_MS);
  };

  usernameInput.addEventListener('input', scheduleSearch);
  domainInput.addEventListener('input', scheduleSearch);
  sortSelect.addEventListener('change', search);

  modalEl.querySelector('.find-user-modal-close').addEventListener('click', closeFindUserModal);
  // stopPropagation for the same layering reason as Escape: the document-level outside-click
  // handlers that close the popover / settings panel this dialog was opened from must not treat
  // a click inside the dialog (or on its backdrop) as "outside".
  modalEl.addEventListener('click', (e) => {
    e.stopPropagation();
    if (e.target === modalEl) closeFindUserModal();
  });
  document.addEventListener('keydown', onKeydown, true);

  search();
  usernameInput.focus();
}
