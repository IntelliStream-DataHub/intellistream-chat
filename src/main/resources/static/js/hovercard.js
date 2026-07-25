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
 * Avatar hovercard: shows a popover with profile info + "Send DM" when the cursor
 * lingers over .avatar[data-author] (or any [data-author] element). Activates on
 * hover with a small delay to avoid flicker, dismisses on outside-click / Escape /
 * mouseleave (tracking both the trigger and the popover so moving the cursor INTO
 * the popover keeps it open).
 *
 * Lives in its own file so both chat.js and conversation.js can pull it in.
 */
(function () {
  const SHOW_DELAY = 220;
  const HIDE_DELAY = 180;
  const CACHE_TTL_MS = 60 * 1000;

  const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');
  const myUsername = meta('me-username');

  const cache = new Map(); // username -> { at: ms, dto: object }
  const inflight = new Map();

  let card = null;
  let currentUsername = null;
  let currentTrigger = null;
  let showTimer = null;
  let hideTimer = null;

  const cancelShow = () => { if (showTimer) { clearTimeout(showTimer); showTimer = null; } };
  const cancelHide = () => { if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; } };

  /**
   * Mount the popover as a *child* of its trigger so the trigger's mouseleave
   * doesn't fire while the cursor is inside the popover — moving from the name
   * into the dialog is just moving deeper into the same DOM subtree, which
   * mouseleave-doesn't-fire-on-descendants handles for free. Visual position
   * still uses {@code position: fixed} so the {@code overflow: auto} on the
   * messages list can't clip the popover.
   */
  const ensureCard = (host) => {
    if (card && card.parentNode === host) return card;
    if (card) card.remove();
    card = document.createElement('div');
    card.className = 'user-hovercard';
    card.setAttribute('role', 'tooltip');
    host.appendChild(card);
    return card;
  };

  const hide = () => {
    cancelShow();
    cancelHide();
    if (card) {
      card.remove();
      card = null;
    }
    currentUsername = null;
    currentTrigger = null;
  };

  const scheduleHide = () => {
    cancelShow();
    cancelHide();
    hideTimer = setTimeout(hide, HIDE_DELAY);
  };

  const fetchProfile = async (username) => {
    const cached = cache.get(username);
    if (cached && Date.now() - cached.at < CACHE_TTL_MS) return cached.dto;
    if (inflight.has(username)) return inflight.get(username);
    const p = fetch('/api/users/' + encodeURIComponent(username), {
      headers: { 'Accept': 'application/json' },
    }).then((r) => {
      if (!r.ok) throw new Error('lookup failed: ' + r.status);
      return r.json();
    }).then((dto) => {
      cache.set(username, { at: Date.now(), dto });
      inflight.delete(username);
      return dto;
    }).catch((e) => {
      inflight.delete(username);
      throw e;
    });
    inflight.set(username, p);
    return p;
  };

  const formatRelative = (iso) => {
    if (!iso) return null;
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return null;
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
  };

  const formatDate = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '—';
    return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  };

  const render = (dto, host) => {
    const el = ensureCard(host);
    const initial = (dto.displayName || dto.username || '?').slice(0, 1).toUpperCase();
    const av = dto.hasAvatar
      ? `<img class="avatar-image" alt="" src="/api/users/${encodeURIComponent(dto.username)}/avatar?v=${dto.avatarVersion}">`
      : '';
    const isMe = dto.username === myUsername;
    const presence = window.Presence ? window.Presence.stateFor(dto.username) : null;
    const presenceLabel = presence ? (presence.online ? 'Online' : 'Offline') : 'Offline';
    const customStatusRow = (presence && (presence.statusEmoji || presence.statusText))
      ? `<div class="user-hovercard-status">
           ${presence.statusEmoji ? `<span class="user-hovercard-status-emoji">${escapeHtml(presence.statusEmoji)}</span>` : ''}
           ${presence.statusText ? `<span class="user-hovercard-status-text">${escapeHtml(presence.statusText)}</span>` : ''}
         </div>`
      : '';
    el.innerHTML = `
      <div class="user-hovercard-head">
        <span class="avatar avatar-large" data-author="${escapeAttr(dto.username)}">
          ${av}
          <span class="avatar-letter">${escapeHtml(initial)}</span>
        </span>
        <div class="user-hovercard-name">
          <div class="user-hovercard-display">
            ${escapeHtml(dto.displayName || dto.username)}
            ${dto.admin ? '<span class="user-hovercard-badge" title="Workspace administrator">admin</span>' : ''}
          </div>
          <div class="user-hovercard-handle">@${escapeHtml(dto.username)}</div>
        </div>
      </div>
      ${customStatusRow}
      <dl class="user-hovercard-fields">
        <dt>Status</dt><dd>${escapeHtml(presenceLabel)}</dd>
        <dt>Joined</dt><dd>${escapeHtml(formatDate(dto.createdAt))}</dd>
        <dt>Active</dt><dd>${escapeHtml(formatRelative(dto.lastActiveAt) || '—')}</dd>
      </dl>
      <div class="user-hovercard-actions">
        ${isMe
          ? `<a class="user-hovercard-btn" href="/profile">Edit your profile</a>`
          : `<button type="button" class="user-hovercard-btn" data-action="dm">
               <svg class="icon"><use href="#icon-send"/></svg>
               <span>Send direct message</span>
             </button>`}
      </div>
    `;
    if (!isMe) {
      el.querySelector('[data-action="dm"]').addEventListener('click', () => startDirect(dto.username));
    }
  };

  const startDirect = async (username) => {
    try {
      const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
      if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
      const res = await fetch('/api/conversations/direct', {
        method: 'POST',
        headers,
        body: JSON.stringify({ username }),
      });
      if (!res.ok) throw new Error('start dm failed: ' + res.status);
      const dto = await res.json();
      window.location.href = '/conversations/' + dto.id;
    } catch (e) {
      hide();
      alert('Could not start direct message. Please try again.');
    }
  };

  const position = (anchor) => {
    const el = ensureCard(anchor);
    const rect = anchor.getBoundingClientRect();
    const cardRect = el.getBoundingClientRect();
    const margin = 8;
    let left = rect.left;
    if (left + cardRect.width + margin > window.innerWidth) {
      left = Math.max(margin, window.innerWidth - cardRect.width - margin);
    }
    // No vertical gap between the trigger and the popover — a gap would create
    // dead-pixel space the cursor crosses during the hover→popover move,
    // re-firing mouseleave on the trigger and snapping the popover shut.
    let top = rect.bottom;
    if (top + cardRect.height + margin > window.innerHeight) {
      top = Math.max(margin, rect.top - cardRect.height);
    }
    el.style.left = left + 'px';
    el.style.top = top + 'px';
  };

  const showFor = async (anchor, username) => {
    currentUsername = username;
    currentTrigger = anchor;
    try {
      const dto = await fetchProfile(username);
      // Hover may have moved on while fetching; bail if no longer relevant.
      if (currentUsername !== username) return;
      render(dto, anchor);
      position(anchor);
    } catch (e) {
      hide();
    }
  };

  // Only avatars and the author / @handle spans should pop the hovercard. The message
  // <li> also carries data-author (used by chat.js for message grouping) — without this
  // narrower selector the hovercard would fire on any hover inside the entire message
  // body, including the body text and attachments, which is wrong.
  const TRIGGER_SELECTOR =
      '.avatar[data-author], .author[data-author], .author-handle[data-author]';

  const onEnter = (e) => {
    if (!(e.target instanceof Element)) return; // synthesized events can target the Document
    const target = e.target.closest(TRIGGER_SELECTOR);
    if (!target) return;
    const username = target.dataset.author;
    if (!username) return;
    cancelHide();
    if (currentUsername === username) return; // already showing for this user
    cancelShow();
    showTimer = setTimeout(() => showFor(target, username), SHOW_DELAY);
  };

  const onLeave = (e) => {
    if (!(e.target instanceof Element)) return; // synthesized events can target the Document
    const trigger = e.target.closest(TRIGGER_SELECTOR);
    if (!trigger) return;
    // If the cursor is moving to another element still inside the same trigger
    // (e.g., from the popover back to the username text), don't dismiss.
    if (e.relatedTarget && trigger.contains(e.relatedTarget)) return;
    cancelShow();
    scheduleHide();
  };

  document.addEventListener('mouseenter', onEnter, true);
  document.addEventListener('mouseleave', onLeave, true);

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && card) hide();
  });
  document.addEventListener('mousedown', (e) => {
    if (!card) return;
    if (card.contains(e.target)) return;
    if (currentTrigger && currentTrigger.contains(e.target)) return;
    hide();
  });
  window.addEventListener('scroll', () => { if (card) hide(); }, true);
  window.addEventListener('resize', () => { if (card) hide(); });

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
  }
  function escapeAttr(s) { return escapeHtml(s); }
})();
