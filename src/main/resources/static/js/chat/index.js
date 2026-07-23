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

/*
 * Entry point for the channels page. Loaded as an ES module via
 * `<script type="module" src="/js/chat/index.js" defer>` from channels.html.
 *
 * Currently a near-verbatim port of the previous IIFE-wrapped chat.js — the only
 * structural change is that the boot-time utilities (meta, headers, csrfToken,
 * activeChannelId) live in ./shared.js so future splits can pick them up via import.
 * Subsequent commits will carve this file into chat/realtime.js, chat/interactions.js,
 * chat/browse.js, chat/chrome.js per the modularization plan.
 */
import { meta, csrfToken, csrfHeader, activeChannelId, headers } from './shared.js';
import * as chrome from './chrome.js';
import * as presenceMenu from './presence-menu.js';

chrome.init();
presenceMenu.init();

// ---------- Channel CRUD ----------
  const wireCreateChannel = (formId) => {
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
  wireCreateChannel('create-channel-form');
  wireCreateChannel('create-channel-form-sidebar');

  // Sidebar "+" toggles the create-channel <details>; bound here so we can drop the
  // inline onclick (CSP forbids inline event handlers under script-src 'self').
  document.getElementById('sidebar-create-add-btn')?.addEventListener('click', () => {
    document.getElementById('sidebar-create-toggle')?.click();
  });

  // ---------- Group conversation create ----------
  // Tiny <details>-driven form. The "+" beside the "Direct messages" header opens the
  // form; submit POSTs to /api/conversations/group and navigates to the new room.
  document.getElementById('sidebar-create-group-btn')?.addEventListener('click', () => {
    document.getElementById('sidebar-create-group-toggle')?.click();
  });
  document.getElementById('create-group-form-sidebar')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const title = (fd.get('title') || '').toString().trim();
    const members = (fd.get('members') || '').toString()
        .split(/[,\s]+/)        // accept commas and whitespace as separators
        .map((s) => s.trim())
        .filter((s) => s.length > 0);
    if (!title || members.length === 0) return;
    const res = await fetch('/api/conversations/group', {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ title, members }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ message: res.statusText }));
      alert('Could not create group: ' + (err.message || err.error || res.statusText));
      return;
    }
    const dto = await res.json();
    window.location.href = '/conversations/' + dto.id;
  });

  // ---------- Enter-to-send (Slack/Mattermost-style) ----------
  // Wired at document level so it survives any failure in the larger composer-setup
  // block below. Plain Enter submits the *closest* form, Shift+Enter (or any modifier)
  // falls through to the textarea's default newline. IME-composition is honored so
  // CJK input doesn't accidentally fire send.
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.isComposing || e.keyCode === 229) return;
    const ta = e.target;
    if (!(ta instanceof HTMLTextAreaElement)) return;
    if (ta.id !== 'composer-input' && ta.id !== 'thread-input') return;
    const form = ta.closest('form');
    if (!form) return;
    e.preventDefault();
    form.requestSubmit();
  });

  // ---------- Mobile sidebar toggle ----------
  // Hamburger flips body.sidebar-open; CSS handles the slide-in + backdrop visibility.
  // Auto-closes on channel pick, Escape, backdrop tap, or resize past the breakpoint.
  (function () {
    const toggle = document.getElementById('sidebar-toggle');
    const backdrop = document.getElementById('sidebar-backdrop');
    if (!toggle) return;
    const setOpen = (open) => {
      document.body.classList.toggle('sidebar-open', open);
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (backdrop) backdrop.hidden = !open;
    };
    toggle.addEventListener('click', () => {
      setOpen(!document.body.classList.contains('sidebar-open'));
    });
    backdrop?.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && document.body.classList.contains('sidebar-open')) setOpen(false);
    });
    document.getElementById('sidebar-channel-list')?.addEventListener('click', (e) => {
      if (e.target.closest('a')) setOpen(false);
    });
    window.addEventListener('resize', () => {
      if (window.innerWidth > 768 && document.body.classList.contains('sidebar-open')) setOpen(false);
    });
  })();

  // ---------- Join public channel ----------
  const joinBtn = document.getElementById('join-channel-btn');
  if (joinBtn) {
    joinBtn.addEventListener('click', async () => {
      const id = joinBtn.dataset.channelId;
      const res = await fetch(`/api/channels/${id}/join`, { method: 'POST', headers: headers() });
      if (res.ok) window.location.reload();
      else alert('Could not join channel');
    });
  }

  // ---------- Channel admin dropdown ----------
  const adminCog = document.getElementById('channel-admin-cog');
  const adminDropdown = document.getElementById('channel-admin-dropdown');
  const adminClose = document.getElementById('channel-admin-close');
  if (adminCog && adminDropdown) {
    const isOpen = () => !adminDropdown.hidden;
    const open = () => {
      adminDropdown.hidden = false;
      adminCog.setAttribute('aria-expanded', 'true');
      adminDropdown.querySelector('input[name="username"]')?.focus();
    };
    const close = () => {
      adminDropdown.hidden = true;
      adminCog.setAttribute('aria-expanded', 'false');
    };
    adminCog.addEventListener('click', (e) => {
      e.stopPropagation();
      isOpen() ? close() : open();
    });
    adminClose?.addEventListener('click', close);
    document.addEventListener('click', (e) => {
      if (!isOpen()) return;
      if (adminDropdown.contains(e.target) || adminCog.contains(e.target)) return;
      close();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && isOpen()) close();
    });
  }

  // ---------- Channel members panel ----------
  // Visible to anyone who can read the channel (PUBLIC: any auth'd user, PRIVATE: members
  // only — the server enforces). Lazily fetches on first open and caches the count badge.
  const membersToggle = document.getElementById('channel-members-toggle');
  const membersPanel = document.getElementById('channel-members-panel');
  const membersClose = document.getElementById('channel-members-close');
  const membersList = document.getElementById('channel-members-list');
  const membersCountEl = document.getElementById('channel-members-count');

  if (membersToggle && membersPanel && activeChannelId) {
    let membersLoaded = false;
    // chat.js's main destructure of ChatKit happens far below (line ~841) — pull
    // buildAvatarEl in locally so this early panel doesn't ReferenceError on first paint.
    const buildMemberAvatar = (window.ChatKit && window.ChatKit.buildAvatarEl)
        || ((opts) => {
          // Bare-minimum fallback so we still render *something* if ChatKit isn't loaded
          // yet (shouldn't happen — chat-kit.js is in the script chain ahead of chat.js).
          const span = document.createElement('span');
          span.className = 'avatar';
          if (opts.username) span.dataset.author = opts.username;
          const letter = document.createElement('span');
          letter.className = 'avatar-letter';
          letter.textContent = opts.letter || '?';
          span.appendChild(letter);
          return span;
        });

    const myUsername = meta('me-username');

    const renderMembers = (members) => {
      membersCountEl.textContent = String(members.length);
      membersList.innerHTML = '';
      if (!members.length) {
        const empty = document.createElement('li');
        empty.className = 'dm-empty';
        empty.textContent = 'No members yet.';
        membersList.appendChild(empty);
        return;
      }
      // The viewer's promote/demote affordance only shows when they're an admin of THIS
      // channel. Server still gates the actual mutation, but rendering the buttons
      // unconditionally would clutter the panel for plain members.
      const viewerIsAdmin = members.some(
          (m) => m.username === myUsername && m.role === 'ADMIN');
      for (const m of members) {
        const li = document.createElement('li');
        const name = m.displayName || m.username;
        const av = buildMemberAvatar({
          username: m.username,
          letter: (name || '?').slice(0, 1).toUpperCase(),
          hasAvatar: m.hasAvatar,
          avatarVersion: m.avatarVersion,
        });
        const label = document.createElement('span');
        label.textContent = name;
        const handle = document.createElement('small');
        handle.textContent = '@' + m.username;
        li.append(av, label, handle);
        if (m.role === 'ADMIN') {
          const role = document.createElement('small');
          role.className = 'channel-role-tag';
          role.title = 'Channel administrator';
          role.textContent = 'channel admin';
          li.appendChild(role);
        }
        if (m.admin) {
          const ws = document.createElement('small');
          ws.className = 'dm-admin-tag';
          ws.title = 'Workspace administrator';
          ws.textContent = 'admin';
          li.appendChild(ws);
        }
        // Promote/demote toggle. Only the channel-admin viewer sees it, never on their
        // own row (no self-demote — also blocks the "last admin" footgun before it can
        // even reach the server, which itself refuses the demote).
        if (viewerIsAdmin && m.username !== myUsername) {
          const toggle = document.createElement('button');
          toggle.type = 'button';
          toggle.className = 'channel-role-toggle';
          const targetRole = m.role === 'ADMIN' ? 'MEMBER' : 'ADMIN';
          toggle.textContent = targetRole === 'ADMIN' ? 'Make admin' : 'Demote';
          toggle.title = targetRole === 'ADMIN'
              ? 'Promote to channel admin'
              : 'Demote to plain member';
          toggle.addEventListener('click', async (ev) => {
            ev.stopPropagation();
            toggle.disabled = true;
            try {
              const res = await fetch('/api/channels/' + activeChannelId
                  + '/members/' + encodeURIComponent(m.username) + '/role', {
                method: 'PUT',
                headers: headers(),
                body: JSON.stringify({ role: targetRole }),
              });
              if (!res.ok && res.status !== 204) {
                const err = await res.json().catch(() => ({}));
                alert('Role change failed: ' + (err.message || res.statusText));
                toggle.disabled = false;
                return;
              }
              // Reload to reflect the new state — also re-derives viewerIsAdmin.
              await loadMembers();
            } catch (e) {
              alert('Role change failed: ' + e.message);
              toggle.disabled = false;
            }
          });
          li.appendChild(toggle);
        }
        membersList.appendChild(li);
      }
    };

    const loadMembers = async () => {
      try {
        const res = await fetch('/api/channels/' + activeChannelId + '/members',
            { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
        if (!res.ok) throw new Error('members load failed: ' + res.status);
        renderMembers(await res.json());
        membersLoaded = true;
      } catch (e) {
        membersList.innerHTML = '<li class="dm-empty">Could not load members.</li>';
      }
    };

    const isMembersOpen = () => !membersPanel.hidden;
    const setMembersOpen = (open) => {
      if (open && !membersLoaded) loadMembers();
      membersPanel.hidden = !open;
      membersToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    };
    membersToggle.addEventListener('click', (e) => {
      e.stopPropagation();
      setMembersOpen(!isMembersOpen());
    });
    membersClose?.addEventListener('click', () => setMembersOpen(false));
    document.addEventListener('click', (e) => {
      if (!isMembersOpen()) return;
      if (membersPanel.contains(e.target) || membersToggle.contains(e.target)) return;
      setMembersOpen(false);
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && isMembersOpen()) setMembersOpen(false);
    });

    // Eagerly populate the count badge so the button shows "👥 N" before opening.
    loadMembers();
  }

  // ---------- Invite (admin) ----------
  const inviteForm = document.getElementById('invite-form');
  if (inviteForm) {
    inviteForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      const id = inviteForm.dataset.channelId;
      const username = new FormData(inviteForm).get('username');
      const res = await fetch(`/api/channels/${id}/invite`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ username }),
      });
      if (res.ok) {
        inviteForm.reset();
        window.location.reload();
      } else {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert('Invite failed: ' + err.error);
      }
    });
  }

  // ---------- Search (live dropdown) ----------
  // Map channelId → channel name, sourced from the sidebar so we can label rows
  // with "#general" without a second API round-trip.
  const channelNames = new Map();
  document.querySelectorAll('#sidebar-channel-list li').forEach((li) => {
    const a = li.querySelector('a');
    if (!a) return;
    const href = a.getAttribute('href') || '';
    const id = href.split('/').pop();
    const spans = a.querySelectorAll('span');
    // First span is .hash ("#"), second is the channel name.
    const name = spans[1]?.textContent.trim();
    if (id && name) channelNames.set(id, name);
  });

  const snippet = (s, max) => {
    if (!s) return '';
    const collapsed = s.replace(/\s+/g, ' ').trim();
    return collapsed.length > max ? collapsed.slice(0, max - 1) + '…' : collapsed;
  };

  const wireSearchDropdown = (input, scopeChannelIdFn) => {
    if (!input) return;
    let dropdown = null;
    let debounce = null;
    let activeIndex = -1;
    let inflight = 0; // monotonic request id — drop stale responses

    const close = () => {
      dropdown?.remove();
      dropdown = null;
      activeIndex = -1;
    };

    const position = () => {
      if (!dropdown) return;
      const r = input.getBoundingClientRect();
      dropdown.style.top = (r.bottom + 4) + 'px';
      dropdown.style.left = r.left + 'px';
      dropdown.style.minWidth = Math.max(r.width, 320) + 'px';
    };

    const highlight = () => {
      if (!dropdown) return;
      const rows = dropdown.querySelectorAll('.search-dropdown-row');
      rows.forEach((row, i) => row.classList.toggle('active', i === activeIndex));
      if (activeIndex >= 0) {
        rows[activeIndex].scrollIntoView({ block: 'nearest' });
      }
    };

    const navigate = (url) => {
      close();
      window.location.href = url;
    };

    const render = (items) => {
      close();
      dropdown = document.createElement('div');
      dropdown.className = 'search-dropdown';
      if (items.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'search-dropdown-empty';
        empty.textContent = 'No matches.';
        dropdown.appendChild(empty);
      } else {
        items.forEach((m, i) => {
          const row = document.createElement('button');
          row.type = 'button';
          row.className = 'search-dropdown-row';
          // Query param tells the server to render context-around (25 before + anchor + 25
          // after) instead of latest 50; fragment is for the existing scroll-to-anchor JS.
          row.dataset.url = '/channels/' + m.channelId + '?m=' + encodeURIComponent(m.id) + '#m=' + m.id;
          row.dataset.index = String(i);
          const channelName = channelNames.get(m.channelId);
          row.innerHTML =
              '<div class="search-dropdown-meta">' +
                '<span class="search-dropdown-author"></span>' +
                '<span class="search-dropdown-channel"></span>' +
                '<time class="search-dropdown-time"></time>' +
              '</div>' +
              '<div class="search-dropdown-snippet"></div>';
          row.querySelector('.search-dropdown-author').textContent = m.authorDisplayName || m.authorUsername;
          row.querySelector('.search-dropdown-channel').textContent = channelName ? '#' + channelName : '';
          row.querySelector('.search-dropdown-time').textContent = new Date(m.createdAt).toLocaleString();
          // bodySnippet is the Lucene-highlighted excerpt with <mark>-wrapped match terms
          // (HTML-escaped before highlighting, so innerHTML is safe). Falls back to bodyHtml
          // — the server-rendered + jsoup-sanitized full body — when no snippet is available.
          row.querySelector('.search-dropdown-snippet').innerHTML = m.bodySnippet || m.bodyHtml || '';
          // mousedown so the input doesn't blur (and close us) before the click fires.
          row.addEventListener('mousedown', (ev) => {
            ev.preventDefault();
            navigate(row.dataset.url);
          });
          row.addEventListener('mouseenter', () => {
            activeIndex = i;
            highlight();
          });
          dropdown.appendChild(row);
        });
      }
      document.body.appendChild(dropdown);
      position();
      activeIndex = -1;
    };

    async function fetchResults(q) {
      const params = new URLSearchParams({ q });
      const cid = scopeChannelIdFn ? scopeChannelIdFn() : null;
      if (cid) params.set('channelId', cid);
      params.set('limit', '10');
      const res = await fetch('/api/search?' + params.toString());
      if (!res.ok) return [];
      return await res.json();
    }

    input.addEventListener('input', () => {
      clearTimeout(debounce);
      const q = input.value.trim();
      if (q.length < 2) { close(); return; }
      const myReq = ++inflight;
      debounce = setTimeout(async () => {
        const items = await fetchResults(q);
        // If a newer request started while we were waiting, drop this response.
        if (myReq !== inflight) return;
        render(items);
      }, 220);
    });

    input.addEventListener('focus', () => {
      const q = input.value.trim();
      if (q.length >= 2 && !dropdown) input.dispatchEvent(new Event('input'));
    });

    input.addEventListener('keydown', (e) => {
      if (!dropdown) return;
      const rows = dropdown.querySelectorAll('.search-dropdown-row');
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        activeIndex = rows.length === 0 ? -1 : Math.min(activeIndex + 1, rows.length - 1);
        highlight();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        activeIndex = Math.max(activeIndex - 1, 0);
        highlight();
      } else if (e.key === 'Enter') {
        if (activeIndex >= 0 && rows[activeIndex]) {
          e.preventDefault();
          navigate(rows[activeIndex].dataset.url);
        } else if (rows.length > 0) {
          e.preventDefault();
          navigate(rows[0].dataset.url);
        }
      } else if (e.key === 'Escape') {
        close();
      }
    });

    document.addEventListener('mousedown', (e) => {
      if (!dropdown) return;
      if (dropdown.contains(e.target) || input.contains(e.target)) return;
      close();
    });
    window.addEventListener('resize', position);
    window.addEventListener('scroll', position, true);
  };

  // Submit handlers — we suppress the default form submit so Enter is handled by the
  // dropdown's keydown logic (or simply does nothing when the dropdown is empty).
  document.getElementById('global-search-form')?.addEventListener('submit', (e) => e.preventDefault());
  document.getElementById('channel-search-form')?.addEventListener('submit', (e) => e.preventDefault());

  wireSearchDropdown(document.getElementById('global-search-input'), null);
  wireSearchDropdown(document.getElementById('channel-search-input'), () => activeChannelId);
  document.getElementById('channel-search-clear')?.addEventListener('click', () => {
    const inp = document.getElementById('channel-search-input');
    if (inp) inp.value = '';
  });

  // Composer/textarea helpers (auto-resize, caret insert, format toolbar, emoji picker)
  // come from window.ChatKit (chat-kit.js). Pull them into local scope for terseness.
  const { wireAutoResize, insertAtCursor, openEmojiPicker } = ChatKit;

  // ---------- Sidebar unread tracking ----------
  // Index every joined-channel <li> by id so STOMP listeners can bump the badge live.
  const sidebarChannels = new Map(); // channelId -> { li, a }
  document.querySelectorAll('#sidebar-channel-list li.joined').forEach((li) => {
    const a = li.querySelector('a');
    if (!a) return;
    const id = (a.getAttribute('href') || '').split('/').pop();
    if (id) sidebarChannels.set(id, { li, a });
  });
  const bumpSidebarUnread = (channelId, isMention) => {
    const entry = sidebarChannels.get(channelId);
    if (!entry) return;
    let badge = entry.a.querySelector('.unread-badge');
    if (!badge) {
      badge = document.createElement('span');
      badge.className = 'unread-badge';
      badge.textContent = '0';
      entry.a.appendChild(badge);
    }
    const cur = parseInt(badge.textContent.replace('+', ''), 10) || 0;
    const next = cur + 1;
    badge.textContent = next > 99 ? '99+' : String(next);
    entry.li.classList.add('has-unread');
    if (isMention) badge.classList.add('mention');
  };

  /**
   * Surface an @mention via the shared notifications module. Skipped when the user is
   * actively reading the channel (active + tab focused) — they can already see it.
   */
  const maybeNotifyMention = (message, channelId, isActiveChannel) => {
    if (!message) return;
    // Bump the topbar bell AND refresh the dropdown inbox so a new mention shows up live
    // without the user reloading. notifyMention debounces simultaneous calls so a burst
    // (multi-channel mention storm) coalesces into one /api/mentions fetch.
    if (window.MentionInbox) window.MentionInbox.notifyMention();
    if (!window.MentionNotifications) return;
    if (isActiveChannel && document.visibilityState === 'visible' && document.hasFocus()) return;
    const entry = sidebarChannels.get(channelId);
    const channelName = entry?.a.querySelector('.channel-name')?.textContent || 'channel';
    const author = message.authorDisplayName || message.authorUsername || 'someone';
    const snippet = (message.bodyMarkdown || '').replace(/\s+/g, ' ').slice(0, 200);
    window.MentionNotifications.show({
      author,
      channel: channelName,
      snippet,
      // Match permalinkFor: ?m= makes the server render context around an older message
      // (it may be outside the latest page), #m= is what scrollToPermalinkTarget matches.
      // The old '#m-<id>' matched neither, landing the user at the tail with no highlight.
      url: '/channels/' + channelId + '?m=' + encodeURIComponent(message.id) + '#m=' + message.id,
    });
  };

  // ---------- WebSocket / STOMP ----------
  const messagesEl = document.getElementById('messages');
  const composer = document.getElementById('composer');

  if (activeChannelId && messagesEl) {
    // Use native WebSocket. SockJS's iframe / htmlfile / jsonp-polling fallback transports
    // inject inline <script> tags and break our strict CSP (script-src 'self'). Modern browsers
    // all support WebSocket directly.
    const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
    const stomp = new StompJs.Client({
      brokerURL: wsUrl,
      reconnectDelay: 4000,
    });

    const myUsername = meta('me-username');
    stomp.onConnect = () => {
      stomp.subscribe('/topic/channels/' + activeChannelId, (frame) => {
        const event = JSON.parse(frame.body);
        handleMessageEvent(event);
      });
      stomp.subscribe('/topic/channels/' + activeChannelId + '/typing', (frame) => {
        const t = JSON.parse(frame.body);
        if (t.username && t.username !== myUsername) {
          noteTyping(t.username, t.displayName || t.username);
        }
      });
      // Bump sidebar badges when messages arrive in joined-but-not-active channels.
      sidebarChannels.forEach((_, id) => {
        if (id === activeChannelId) return;
        stomp.subscribe('/topic/channels/' + id, (frame) => {
          const ev = JSON.parse(frame.body);
          if (ev.type !== 'created' || ev.parentId) return;
          if (ev.message?.authorUsername === myUsername) return;
          const mentioned = !!(ev.message?.mentions || []).includes(myUsername);
          bumpSidebarUnread(id, mentioned);
          if (mentioned) maybeNotifyMention(ev.message, id, /* isActiveChannel */ false);
        });
      });
      stomp.subscribe('/topic/users', (frame) => {
        const ev = JSON.parse(frame.body);
        if (!ev || !ev.username) return;
        if (ev.type === 'avatar-updated') refreshAvatarsFor(ev.username, ev.avatarVersion);
        else if (ev.type === 'avatar-removed') refreshAvatarsFor(ev.username, 0);
      });
      // Per-user notices: slash-command usage errors and similar private feedback. The
      // server publishes via convertAndSendToUser → /user/queue/notices.
      stomp.subscribe('/user/queue/notices', (frame) => {
        try {
          const n = JSON.parse(frame.body);
          showSlashNotice(n.text || '', n.level || 'info');
        } catch (e) { /* ignore malformed */ }
      });
      if (window.Presence) window.Presence.attachStomp(stomp);
    };

    /**
     * Drop a transient banner above the composer for a few seconds. Reuses the
     * #composer-notice element if it's there, otherwise injects one. Errors get a
     * red border; info uses neutral styling.
     */
    const showSlashNotice = (text, level) => {
      const composerEl = document.getElementById('composer');
      if (!composerEl || !text) return;
      let notice = document.getElementById('composer-notice');
      if (!notice) {
        notice = document.createElement('div');
        notice.id = 'composer-notice';
        notice.className = 'composer-notice';
        composerEl.parentNode.insertBefore(notice, composerEl);
      }
      notice.classList.toggle('error', level === 'error');
      notice.textContent = text;
      notice.hidden = false;
      clearTimeout(notice._hideTimer);
      notice._hideTimer = setTimeout(() => {
        notice.hidden = true;
      }, 6000);
    };

    /**
     * Swap every on-screen avatar for {@code username} to the latest version. {@code version === 0}
     * means the user cleared their picture — drop the {@code <img>} so the fallback initial shows.
     */
    const refreshAvatarsFor = (username, version) => {
      const sel = '.avatar[data-author="' + (window.CSS && CSS.escape ? CSS.escape(username) : username) + '"]';
      document.querySelectorAll(sel).forEach((el) => {
        const existing = el.querySelector('img.avatar-image');
        if (!version) {
          if (existing) existing.remove();
          return;
        }
        const url = '/api/users/' + encodeURIComponent(username) + '/avatar?v=' + version;
        if (existing) {
          existing.src = url;
        } else {
          const img = document.createElement('img');
          img.className = 'avatar-image';
          img.alt = '';
          img.src = url;
          img.addEventListener('error', () => img.remove());
          el.insertBefore(img, el.firstChild);
        }
      });
    };

    const handleMessageEvent = (event) => {
      if (!event || !event.type) return;
      if (event.type === 'created') {
        if (event.parentId) {
          appendThreadReply(event.message);
          bumpThreadIndicator(event.parentId, +1);
        } else {
          // If the viewer is reading context-around an old anchor and hasn't paged forward
          // to the live tail, skipping the live-append keeps the loaded batch chronologically
          // contiguous. The Jump-to-latest banner is the user's path back to current traffic;
          // once they reach it (or click it), infiniteScrollDownDone flips and live appends
          // resume normally.
          if (!infiniteScrollDownDone) return;
          appendMessage(event.message);
          // Active channel is being read live — advance the read marker so navigating away
          // doesn't leave these messages counted as unread on next page load.
          if (event.message?.authorUsername !== myUsername) {
            fetch('/api/channels/' + activeChannelId + '/read', {
              method: 'POST', headers: headers(),
            })
              .then(() => { if (window.MentionInbox) window.MentionInbox.refresh(); })
              .catch(() => {});
            const mentioned = !!(event.message?.mentions || []).includes(myUsername);
            if (mentioned) maybeNotifyMention(event.message, activeChannelId, /* isActiveChannel */ true);
          }
        }
      } else if (event.type === 'updated') {
        replaceMessageDom(event.message);
      } else if (event.type === 'deleted') {
        removeMessageDom(event.id);
        if (event.parentId) bumpThreadIndicator(event.parentId, -1);
      } else if (event.type === 'poll-vote') {
        applyPollUpdate(event.id, event.poll);
      }
    };

    stomp.activate();

    // Typing indicator state: username -> { displayName, expiresAt }.
    const typingUsers = new Map();
    const typingEl = document.getElementById('typing-indicator');
    let typingSweep = null;

    const noteTyping = (username, displayName) => {
      typingUsers.set(username, { displayName, expiresAt: Date.now() + 4000 });
      renderTyping();
      if (!typingSweep) typingSweep = setInterval(sweepTyping, 1000);
    };
    const sweepTyping = () => {
      const now = Date.now();
      let changed = false;
      for (const [u, v] of typingUsers) {
        if (v.expiresAt <= now) { typingUsers.delete(u); changed = true; }
      }
      if (changed) renderTyping();
      if (typingUsers.size === 0 && typingSweep) {
        clearInterval(typingSweep); typingSweep = null;
      }
    };
    const renderTyping = () => {
      if (!typingEl) return;
      const names = [...typingUsers.values()].map(v => v.displayName);
      if (names.length === 0) {
        typingEl.hidden = true;
        typingEl.textContent = '';
        return;
      }
      let text;
      if (names.length === 1) text = names[0] + ' is typing…';
      else if (names.length === 2) text = names[0] + ' and ' + names[1] + ' are typing…';
      else text = names.length + ' people are typing…';
      typingEl.textContent = text;
      typingEl.hidden = false;
    };

    // Throttled typing publisher: at most once per 2s while user keeps typing.
    let lastTypingSentAt = 0;
    const publishTyping = () => {
      if (!stomp.connected) return;
      const now = Date.now();
      if (now - lastTypingSentAt < 2000) return;
      lastTypingSentAt = now;
      stomp.publish({ destination: '/app/channels/' + activeChannelId + '/typing', body: '{}' });
    };

    if (composer) {
      const fileInput = document.getElementById('composer-file');
      const attachBtn = document.getElementById('composer-attach');
      const pending = new Map(); // localId -> { file, chip }

      if (attachBtn && fileInput) {
        attachBtn.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', () => {
          for (const f of fileInput.files) addPendingAttachment(f);
          fileInput.value = '';
        });
      }

      const emojiBtn = document.getElementById('composer-emoji');
      if (emojiBtn) {
        emojiBtn.addEventListener('click', () => {
          const ta = document.getElementById('composer-input');
          openEmojiPicker(emojiBtn, (e) => insertAtCursor(ta, e));
        });
      }

      composer.addEventListener('submit', async (e) => {
        e.preventDefault();
        const input = document.getElementById('composer-input');
        const body = input.value.trim();
        const hasFiles = pending.size > 0;
        if (!body && !hasFiles) return;

        if (hasFiles) {
          // Upload each file as its own message (caption = body, only on the first one).
          let caption = body;
          for (const [localId, item] of Array.from(pending.entries())) {
            try {
              await uploadAttachment(item.file, caption);
              caption = '';
              removePendingAttachment(localId);
            } catch (err) {
              alert('Upload failed for ' + item.file.name + ': ' + err.message);
              return;
            }
          }
          input.value = '';
          input._autoResize?.();
        } else {
          stomp.publish({
            destination: '/app/channels/' + activeChannelId + '/send',
            body: JSON.stringify({ body }),
          });
          input.value = '';
          input._autoResize?.();
        }
      });
      const composerInput = document.getElementById('composer-input');
      wireAutoResize(composerInput);
      // Broadcast a "typing" ping while the user is editing the composer.
      composerInput?.addEventListener('input', () => {
        if (composerInput.value.trim().length > 0) publishTyping();
      });

      // Live markdown preview — server-rendered so the preview matches the posted message
      // exactly (mentions, code highlighting, sanitisation, all identical).
      const previewPane = document.getElementById('composer-preview');
      const previewBody = document.getElementById('composer-preview-body');
      let previewDebounce = null;
      let previewReq = 0;
      async function refreshPreview() {
        if (!previewPane || !previewBody || !composerInput) return;
        const body = composerInput.value;
        if (!body.trim()) {
          previewPane.hidden = true;
          previewBody.innerHTML = '';
          return;
        }
        const myReq = ++previewReq;
        try {
          const res = await fetch('/api/preview', {
            method: 'POST',
            headers: headers(),
            body: JSON.stringify({ body })
          });
          if (!res.ok) return;
          const data = await res.json();
          if (myReq !== previewReq) return; // stale response, dropped
          previewBody.innerHTML = data.html || '';
          highlightCode(previewBody);
          previewPane.hidden = !data.html;
        } catch (_) {
          // Network blip — leave the prior preview in place rather than blanking it.
        }
      }
      composerInput?.addEventListener('input', () => {
        clearTimeout(previewDebounce);
        previewDebounce = setTimeout(refreshPreview, 220);
      });
      // Hide preview after sending so an empty composer doesn't show a stale render.
      composer.addEventListener('submit', () => {
        clearTimeout(previewDebounce);
        if (previewPane) previewPane.hidden = true;
        if (previewBody) previewBody.innerHTML = '';
      });

      const addPendingAttachment = (file) => {
        const tray = document.getElementById('composer-attachments');
        if (!tray) return;
        const localId = 'p' + Math.random().toString(36).slice(2);
        const chip = document.createElement('div');
        chip.className = 'composer-chip';
        chip.innerHTML = `<span class="composer-chip-name"></span>
          <span class="composer-chip-size"></span>
          <button type="button" class="composer-chip-remove" title="Remove" aria-label="Remove">
            <svg class="icon icon-sm"><use href="#icon-close"/></svg>
          </button>`;
        chip.querySelector('.composer-chip-name').textContent = file.name;
        chip.querySelector('.composer-chip-size').textContent = formatBytes(file.size);
        chip.querySelector('.composer-chip-remove')
            .addEventListener('click', () => removePendingAttachment(localId));
        tray.append(chip);
        tray.hidden = false;
        pending.set(localId, { file, chip });
      };
      const removePendingAttachment = (localId) => {
        const item = pending.get(localId);
        if (!item) return;
        item.chip.remove();
        pending.delete(localId);
        const tray = document.getElementById('composer-attachments');
        if (tray && pending.size === 0) tray.hidden = true;
      };
      async function uploadAttachment(file, caption) {
        const fd = new FormData();
        fd.append('file', file);
        if (caption) fd.append('caption', caption);
        const h = headers();
        delete h['Content-Type']; // let the browser set the multipart boundary
        const res = await fetch('/api/channels/' + activeChannelId + '/attachments', {
          method: 'POST',
          headers: h,
          body: fd,
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({ error: res.statusText }));
          // 413 carries the actual cap as `maxBytes` so we can render a precise message.
          if (res.status === 413 && typeof err.maxBytes === 'number') {
            const mib = (err.maxBytes / (1024 * 1024)).toFixed(0);
            throw new Error('File too large — your account is capped at ' + mib + ' MiB per upload.');
          }
          throw new Error(err.message || err.error || res.statusText);
        }
      }
    }
  }

  // formatBytes / hashCode / avatarColor / buildAvatarEl / dayKey / formatTime /
  // appendAuthorHandle all come from window.ChatKit (see chat-kit.js). Locals below
  // are page-specific (fuzzyMatch, levenshtein, formatDay) and stay here.
  const { formatBytes, avatarColor, buildAvatarEl, dayKey, formatTime, appendAuthorHandle } = ChatKit;

  // formatDay is page-local (channel-feed day-divider label); other date helpers come from ChatKit.
  // fuzzyMatch / levenshtein moved to ./shared.js so chat/chrome.js (sidebar filter) can use them.
  const formatDay = (d) =>
      d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' });

  const lastMessageEl = () => {
    const items = messagesEl.querySelectorAll('li.message');
    return items.length ? items[items.length - 1] : null;
  };

  /**
   * Build the bare {@code <li.message>} for {@code msg}. Day-of/grouped class flags are NOT
   * set here — callers either compute them inline ({@code appendMessage}) or rely on the
   * post-mutation {@code refreshDayDividers()} walker ({@code prependOlderMessages}) to fill
   * them in by walking the DOM. Keeps the per-message DOM construction in one place.
   */
  const buildMessageLi = (msg) => {
    const created = new Date(msg.createdAt);
    const curDay = dayKey(created);
    const li = document.createElement('li');
    li.className = 'message';
    li.dataset.id = msg.id;
    li.dataset.createdAt = msg.createdAt;
    li.dataset.author = msg.authorUsername;
    li.dataset.day = curDay;
    // Server-rendered LIs carry this from Thymeleaf; live-appended/paged ones must too, or
    // attachActions (Edit button), startEdit (edit seed), and the reaction-vs-edit detection
    // in replaceMessageDom all misfire. (conversation.js already sets this.)
    li.dataset.bodyMarkdown = msg.bodyMarkdown || '';

    const name = msg.authorDisplayName || msg.authorUsername;
    const avatar = buildAvatarEl({
      username: msg.authorUsername,
      letter: (name || '?').slice(0, 1).toUpperCase(),
      hasAvatar: msg.authorHasAvatar,
      avatarVersion: msg.authorAvatarVersion,
    });

    const right = document.createElement('div');
    const meta = document.createElement('div');
    meta.className = 'message-meta';
    const author = document.createElement('span');
    author.className = 'author';
    author.dataset.author = msg.authorUsername;
    author.textContent = name;
    const time = document.createElement('time');
    time.textContent = formatTime(created);
    meta.append(author);
    appendAuthorHandle(meta, msg.authorDisplayName, msg.authorUsername);
    meta.append(time);

    right.append(meta);

    if (msg.bodyMarkdown && msg.bodyMarkdown.length > 0) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml;
      highlightCode(body);
      right.append(body);
    }

    if (msg.poll) {
      right.append(renderPollWidget(msg.poll));
    }

    if (msg.reactions && msg.reactions.length > 0) {
      right.append(renderReactionTray(msg.reactions));
    }

    if (msg.attachments && msg.attachments.length > 0) {
      right.append(renderAttachmentTray(msg.attachments));
    }

    // Thread indicator anchors the bottom of the message, like Slack's "N replies" widget.
    const indicator = renderThreadIndicator(msg.replyCount);
    if (indicator) right.append(indicator);

    li.append(avatar, right);
    return li;
  };

  const appendMessage = (msg) => {
    // De-dupe: a live broadcast can race the final infinite-scroll page (which flips
    // infiniteScrollDownDone) and arrive for a message already rendered — without this
    // guard it would append a duplicate <li> and a duplicate day-divider anchor.
    if (messagesEl.querySelector('li.message[data-id="' + CSS.escape(String(msg.id)) + '"]')) return;
    const created = new Date(msg.createdAt);
    const curDay = dayKey(created);
    const prev = lastMessageEl();
    const prevDay = prev ? prev.dataset.day : null;
    const prevAuthor = prev ? prev.dataset.author : null;

    const isFirstOfDay = curDay !== prevDay;
    if (isFirstOfDay) {
      addDayDividerForNewMessage(msg.id, created);
    }

    const sameAuthor = prevAuthor === msg.authorUsername && curDay === prevDay;
    const li = buildMessageLi(msg);
    if (sameAuthor) li.classList.add('grouped');
    if (isFirstOfDay) li.classList.add('first-of-day');

    // Only follow the tail if the reader is already near the bottom; otherwise a live
    // message would yank someone reading history straight down. (The prepend/history path
    // preserves the viewport separately.) Measure BEFORE appending.
    const nearBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 120;
    messagesEl.append(li);
    attachActions(li);
    flagAsAppearing(li);
    if (nearBottom || msg.authorUsername === myUsernameMeta) {
      messagesEl.scrollTop = messagesEl.scrollHeight;
    }
    positionDayDividers();
  };

  /**
   * Walk the current message list top-to-bottom and reset the {@code first-of-day} +
   * {@code grouped} classes plus the day-divider layer to match. Called after a prepend
   * so the day-of-week labels and run-grouping for the boundary message and its prior
   * neighbour stay correct. O(N) over the visible message count, which is bounded by
   * however many pages the user has scrolled through — fine in practice.
   */
  const refreshDayDividers = () => {
    if (!messagesEl) return;
    if (dividerLayer) {
      dividerLayer.querySelectorAll('.day-divider').forEach((d) => d.remove());
    }
    let prevDay = null;
    let prevAuthor = null;
    messagesEl.querySelectorAll('li.message').forEach((li) => {
      const curDay = li.dataset.day;
      const curAuthor = li.dataset.author;
      const isFirstOfDay = curDay !== prevDay;
      const isGrouped = !isFirstOfDay && curAuthor === prevAuthor;
      li.classList.toggle('first-of-day', isFirstOfDay);
      li.classList.toggle('grouped', isGrouped);
      if (isFirstOfDay) {
        addDayDividerForNewMessage(li.dataset.id, new Date(li.dataset.createdAt));
      }
      prevDay = curDay;
      prevAuthor = curAuthor;
    });
    positionDayDividers();
  };

  // ---------- Infinite scroll for older messages ----------
  // The initial Thymeleaf-rendered batch is the latest 50 (DEFAULT_PAGE_SIZE in MessageService).
  // When the user scrolls up to that batch's top, we fetch the prior 50 via the existing
  // /api/channels/{id}/messages?before=<instant>&limit=50 endpoint and prepend them. Repeats
  // until the server returns fewer than the limit, then stops watching.
  let oldestLoadedAt = (() => {
    const first = messagesEl?.querySelector('li.message[data-created-at]');
    return first ? first.dataset.createdAt : null;
  })();
  let infiniteScrollDone = !oldestLoadedAt; // empty channel → nothing to load
  let loadingOlder = false;
  let olderSentinel = null;
  let olderObserver = null;

  const prependOlderMessages = (rows) => {
    // Server returns oldest-first inside the batch (MessageService re-sorts ascending after
    // the descending DB fetch). Inserting each row before the current first.message LI
    // preserves that order: row[0] ends up at the new top, row[N-1] right above the prior top.
    const firstExisting = messagesEl.querySelector('li.message');
    let inserted = 0;
    for (const msg of rows) {
      // De-dupe in case of overlap with the existing batch (shouldn't happen with the
      // before=<instant> contract, but handle it defensively).
      if (messagesEl.querySelector('li.message[data-id="' + CSS.escape(msg.id) + '"]')) continue;
      const li = buildMessageLi(msg);
      if (firstExisting) {
        messagesEl.insertBefore(li, firstExisting);
      } else {
        messagesEl.append(li);
      }
      attachActions(li);
      // Don't flagAsAppearing — these are old messages, no slide-in animation.
      inserted++;
    }
    return inserted;
  };

  const loadOlder = async () => {
    if (loadingOlder || infiniteScrollDone || !activeChannelId || !oldestLoadedAt) return;
    loadingOlder = true;
    try {
      const url = '/api/channels/' + encodeURIComponent(activeChannelId) +
          '/messages?before=' + encodeURIComponent(oldestLoadedAt) + '&limit=50';
      const res = await fetch(url, { headers: headers(), credentials: 'same-origin' });
      if (!res.ok) return;
      const rows = await res.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        infiniteScrollDone = true;
        olderSentinel?.remove();
        olderObserver?.disconnect();
        return;
      }
      // Preserve the user's viewport: we're growing the list above their current scrollTop,
      // so push scrollTop down by the height delta to keep the visible content steady.
      const prevScrollHeight = messagesEl.scrollHeight;
      const prevScrollTop = messagesEl.scrollTop;

      const insertedCount = prependOlderMessages(rows);
      // Update the high-water mark to the new oldest visible message.
      oldestLoadedAt = rows[0].createdAt;
      refreshDayDividers();

      const newScrollHeight = messagesEl.scrollHeight;
      messagesEl.scrollTop = prevScrollTop + (newScrollHeight - prevScrollHeight);

      // Server returned a partial batch → nothing older exists.
      if (rows.length < 50 || insertedCount === 0) {
        infiniteScrollDone = true;
        olderSentinel?.remove();
        olderObserver?.disconnect();
      }
    } catch (e) {
      // Network blip — leave state alone; observer will fire again on next scroll-to-top.
    } finally {
      loadingOlder = false;
    }
  };

  const setupInfiniteScroll = () => {
    if (!messagesEl || infiniteScrollDone || !activeChannelId) return;
    olderSentinel = document.createElement('li');
    olderSentinel.className = 'load-older-sentinel';
    olderSentinel.setAttribute('aria-hidden', 'true');
    messagesEl.insertBefore(olderSentinel, messagesEl.firstChild);
    olderObserver = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) loadOlder();
      }
    }, { root: messagesEl, threshold: 0.1, rootMargin: '120px 0px 0px 0px' });
    olderObserver.observe(olderSentinel);
  };
  setupInfiniteScroll();

  // ---------- Symmetric infinite scroll for newer messages ----------
  // Only relevant when the page was opened on an old anchor (?m=<id>) — the user is reading
  // context-around something old, and there's still unloaded history between the visible
  // bottom and "now". A sentinel at the bottom of the list triggers a forward-paging fetch
  // when intersected. Regular page loads start at "latest 50" so there's nothing newer to
  // load forward; we leave the down-observer disabled for them.
  const centeredOnAnchor = (meta('centered-on-anchor') || '') === 'true';
  let latestLoadedAt = (() => {
    const items = messagesEl?.querySelectorAll('li.message[data-created-at]');
    if (!items || !items.length) return null;
    return items[items.length - 1].dataset.createdAt;
  })();
  // For non-anchor loads we know we're already at the tail — skip the down-observer.
  let infiniteScrollDownDone = !centeredOnAnchor;
  let loadingNewer = false;
  let newerSentinel = null;
  let newerObserver = null;

  const appendNewerMessages = (rows) => {
    let inserted = 0;
    for (const msg of rows) {
      if (messagesEl.querySelector('li.message[data-id="' + CSS.escape(msg.id) + '"]')) continue;
      const li = buildMessageLi(msg);
      // Insert before the bottom sentinel so it stays the last child.
      if (newerSentinel && newerSentinel.parentNode === messagesEl) {
        messagesEl.insertBefore(li, newerSentinel);
      } else {
        messagesEl.append(li);
      }
      attachActions(li);
      inserted++;
    }
    return inserted;
  };

  const loadNewer = async () => {
    if (loadingNewer || infiniteScrollDownDone || !activeChannelId || !latestLoadedAt) return;
    loadingNewer = true;
    try {
      const url = '/api/channels/' + encodeURIComponent(activeChannelId) +
          '/messages?after=' + encodeURIComponent(latestLoadedAt) + '&limit=50';
      const res = await fetch(url, { headers: headers(), credentials: 'same-origin' });
      if (!res.ok) return;
      const rows = await res.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        infiniteScrollDownDone = true;
        newerSentinel?.remove();
        newerObserver?.disconnect();
        // We've now caught up to the live feed — drop the "showing context" banner since the
        // viewer has paged forward to the present.
        document.getElementById('jump-to-latest-banner')?.remove();
        return;
      }
      // No scroll-position adjustment needed: we're appending below the viewport, so the
      // user's current scrollTop position stays anchored to the same content.
      appendNewerMessages(rows);
      latestLoadedAt = rows[rows.length - 1].createdAt;
      refreshDayDividers();

      if (rows.length < 50) {
        // Reached the tail of history — close out the down-observer and the banner.
        infiniteScrollDownDone = true;
        newerSentinel?.remove();
        newerObserver?.disconnect();
        document.getElementById('jump-to-latest-banner')?.remove();
      }
    } catch (e) {
      // Network blip — leave state alone; observer will retry on the next scroll-down.
    } finally {
      loadingNewer = false;
    }
  };

  const setupInfiniteScrollDown = () => {
    if (!messagesEl || infiniteScrollDownDone || !activeChannelId) return;
    newerSentinel = document.createElement('li');
    newerSentinel.className = 'load-newer-sentinel';
    newerSentinel.setAttribute('aria-hidden', 'true');
    messagesEl.appendChild(newerSentinel);
    newerObserver = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) loadNewer();
      }
    }, { root: messagesEl, threshold: 0.1, rootMargin: '0px 0px 120px 0px' });
    newerObserver.observe(newerSentinel);
  };
  setupInfiniteScrollDown();

  const renderThreadIndicator = (count) => {
    if (!count || count <= 0) return null;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'thread-indicator';
    btn.title = 'Open thread (' + count + ' ' + (count === 1 ? 'reply' : 'replies') + ')';
    btn.innerHTML = '<span class="thread-indicator-icon">💬</span><span class="thread-indicator-count"></span>';
    btn.querySelector('.thread-indicator-count').textContent = count + ' ' + (count === 1 ? 'reply' : 'replies');
    btn.dataset.count = String(count);
    return btn;
  };

  // Bump (or create) the thread indicator on a parent message after a reply arrives.
  const bumpThreadIndicator = (parentId, delta) => {
    const li = findMessageEl(parentId);
    if (!li) return;
    const right = li.querySelector(':scope > div');
    if (!right) return;
    let btn = right.querySelector('.thread-indicator');
    let count = btn ? (parseInt(btn.dataset.count, 10) || 0) : 0;
    count = Math.max(0, count + (delta || 0));
    if (count <= 0) {
      btn?.remove();
      return;
    }
    if (!btn) {
      btn = renderThreadIndicator(count);
      // Insert before attachments / reactions / edit-form, after message-body.
      const body = right.querySelector('.message-body');
      if (body && body.nextSibling) right.insertBefore(btn, body.nextSibling);
      else right.appendChild(btn);
    } else {
      btn.querySelector('.thread-indicator-count').textContent =
          count + ' ' + (count === 1 ? 'reply' : 'replies');
      btn.title = 'Open thread (' + count + ' ' + (count === 1 ? 'reply' : 'replies') + ')';
      btn.dataset.count = String(count);
    }
  };

  // ---------- Day-divider layer ----------
  // Day-dividers live as siblings of .messages (inside .messages-stack) so the mask on
  // .messages doesn't fade them. Each divider is anchored to the first message of its day;
  // we keep them aligned to that message by setting `top` from anchor.offsetTop - scrollTop.
  const dividerLayer = document.getElementById('day-divider-layer');

  const addDayDividerForNewMessage = (messageId, created) => {
    if (!dividerLayer) return;
    const div = document.createElement('div');
    div.className = 'day-divider';
    div.dataset.anchorId = messageId;
    div.dataset.day = dayKey(created);
    const span = document.createElement('span');
    span.textContent = formatDay(created);
    div.append(span);
    dividerLayer.appendChild(div);
  };

  let dividerRaf = 0;
  const positionDayDividers = () => {
    if (!dividerLayer || !messagesEl) return;
    if (dividerRaf) return;
    dividerRaf = requestAnimationFrame(() => {
      dividerRaf = 0;
      const scrollTop = messagesEl.scrollTop;
      dividerLayer.querySelectorAll('.day-divider').forEach((div) => {
        const anchorId = div.dataset.anchorId;
        const anchor = anchorId
            ? messagesEl.querySelector('li.message[data-id="' + CSS.escape(anchorId) + '"]')
            : null;
        if (!anchor) {
          div.style.visibility = 'hidden';
          return;
        }
        // Center the divider in the gap created by .first-of-day's margin-top (≈2.5rem ≈ 40px).
        const top = anchor.offsetTop - scrollTop - 32;
        div.style.top = top + 'px';
        div.style.visibility = 'visible';
      });
    });
  };
  messagesEl?.addEventListener('scroll', positionDayDividers, { passive: true });
  window.addEventListener('resize', positionDayDividers);
  // Re-measure after the page settles (fonts/images may shift offsets).
  setTimeout(positionDayDividers, 50);
  setTimeout(positionDayDividers, 300);
  positionDayDividers();

  // Add the .appearing class so CSS plays the slide-in + accent stripe, then strip it after the
  // longest animation completes so subsequent re-renders (edits, reactions) don't replay it.
  const flagAsAppearing = (li) => {
    if (!li) return;
    li.classList.add('appearing');
    setTimeout(() => li.classList.remove('appearing'), 1700);
  };

  // Open the channel pinned to the most recent message. Re-runs after a tick and once
  // images settle, since avatars / inline images / code highlighting can grow the content
  // height *after* the synchronous scroll, leaving the viewport a few hundred pixels short.
  // Skipped when the URL carries a #m=… permalink — that flow scrolls to a specific message.
  const scrollToBottom = () => {
    if (!messagesEl) return;
    messagesEl.scrollTop = messagesEl.scrollHeight;
  };
  const hasPermalink = /^#m=/.test(window.location.hash || '');
  if (messagesEl && !hasPermalink) {
    scrollToBottom();
    requestAnimationFrame(scrollToBottom);
    setTimeout(scrollToBottom, 50);
    setTimeout(scrollToBottom, 300);
    messagesEl.querySelectorAll('img').forEach((img) => {
      if (!img.complete) img.addEventListener('load', scrollToBottom, { once: true });
    });
  }

  // ---------- Syntax highlighting ----------
  const highlightCode = (root) => {
    if (!root) return;
    if (!window.hljs) {
      if (!highlightCode._warned) {
        highlightCode._warned = true;
        console.warn('[hljs] highlight.js not loaded — code blocks will render unhighlighted');
      }
      return;
    }
    root.querySelectorAll('pre code').forEach((block) => {
      // hljs v11 marks processed blocks with data-highlighted="yes"; re-running just spams a warning.
      if (block.dataset.highlighted === 'yes') return;
      try {
        window.hljs.highlightElement(block);
      } catch (err) {
        console.warn('[hljs] failed to highlight a block:', err);
      }
    });
  };
  // Highlight everything currently on the page (server-rendered messages, search results, etc.).
  highlightCode(document);

  // ---------- Color server-rendered avatars (delegated to ChatKit) ----------
  ChatKit.backfillAvatarColors();

  // ---------- Message actions / edit / delete / threads ----------
  const myUsernameMeta = meta('me-username');
  const isAdmin = meta('me-is-admin') === 'true';

  // Match in the main feed first, then fall back to the thread panel — thread replies
  // that aren't currently in the channel viewport (e.g. older replies) only live in the
  // thread <ol>, so reaction/edit broadcasts must update them there.
  const findMessageEl = (id) => {
    const sel = 'li.message[data-id="' + CSS.escape(id) + '"]';
    return (messagesEl && messagesEl.querySelector(sel))
        || document.querySelector('#thread-replies ' + sel)
        || document.querySelector('#thread-parent ' + sel);
  };

  const buildActions = (authorUsername, hasBody) => {
    const isMine = authorUsername === myUsernameMeta;
    const canDelete = isMine || isAdmin;
    const actions = document.createElement('div');
    actions.className = 'message-actions';
    // Authors can't react to their own messages — server enforces it; hide the button so users
    // don't get a 403 toast on click.
    let html = '';
    if (!isMine) {
      html += '<button type="button" class="msg-action" data-action="react" title="Add reaction">😊</button>';
    }
    html += '<button type="button" class="msg-action" data-action="reply" title="Reply in thread">↩</button>';
    html += '<button type="button" class="msg-action" data-action="permalink" title="Copy link to message">🔗</button>';
    if (isMine && hasBody) {
      html += '<button type="button" class="msg-action" data-action="edit" title="Edit">✏️</button>';
    }
    if (canDelete) {
      html += '<button type="button" class="msg-action" data-action="delete" title="Delete">🗑</button>';
    }
    actions.innerHTML = html;
    return actions;
  };

  // ---------- Reactions ----------
  const renderReactionTray = (groups) => {
    const tray = document.createElement('div');
    tray.className = 'message-reactions';
    for (const g of groups) tray.appendChild(buildReactionBubble(g));
    return tray;
  };
  const buildReactionBubble = (g) => {
    const mineDerived = g.usernames && g.usernames.includes(myUsernameMeta);
    const mine = mineDerived || g.mine;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'reaction' + (mine ? ' mine' : '');
    btn.dataset.emoji = g.emoji;
    btn.dataset.mine = String(mine);
    btn.title = (g.usernames || []).join(', ');
    btn.innerHTML = '<span class="reaction-emoji"></span><span class="reaction-count"></span>';
    btn.querySelector('.reaction-emoji').textContent = g.emoji;
    btn.querySelector('.reaction-count').textContent = g.count;
    return btn;
  };

  async function toggleReaction(messageId, emoji, currentlyMine) {
    const url = '/api/messages/' + messageId + '/reactions' + (currentlyMine ? '/' + encodeURIComponent(emoji) : '');
    const res = await fetch(url, {
      method: currentlyMine ? 'DELETE' : 'POST',
      headers: headers(),
      body: currentlyMine ? undefined : JSON.stringify({ emoji })
    });
    if (!res.ok && res.status !== 204) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Reaction failed: ' + (err.error || res.statusText));
    }
    // WS broadcast triggers replaceMessageDom to refresh the bubble row.
  }
  // Lets the mobile long-press action sheet's emoji strip toggle reactions.
  ChatKit.setQuickReaction(toggleReaction);

  // Markdown toolbar / caret-insert helpers come from window.ChatKit (see chat-kit.js).
  // Wire all toolbars on the page (main composer + thread reply composer share the
  // same data-format-target attribute scheme).
  ChatKit.wireAllFormatToolbars();

  const attachActions = (li) => {
    if (!li || li.querySelector('.message-actions')) return;
    const author = li.dataset.author;
    const hasBody = !!(li.dataset.bodyMarkdown && li.dataset.bodyMarkdown.length > 0);
    li.appendChild(buildActions(author, hasBody));
  };

  const buildAttachmentLink = (a) => {
    const isImage = (a.contentType || '').startsWith('image/');
    const link = document.createElement('a');
    link.href = a.downloadUrl;
    link.title = a.filename;
    if (isImage) {
      link.className = 'attachment-image';
      // Keep href + target so middle-click and "Open in new tab" still work; left-click
      // is intercepted by the document-level delegate that opens the lightbox.
      link.target = '_blank';
      link.rel = 'noopener';
      const img = document.createElement('img');
      img.src = a.downloadUrl;
      img.alt = a.filename;
      img.loading = 'lazy';
      link.append(img);
    } else {
      link.className = 'attachment';
      link.dataset.contentType = a.contentType;
      link.innerHTML = '<svg class="icon attachment-icon"><use href="#icon-paperclip"/></svg>' +
          '<span class="attachment-info"><span class="attachment-name"></span>' +
          '<span class="attachment-meta"></span></span>' +
          '<svg class="icon attachment-download"><use href="#icon-download"/></svg>';
      link.querySelector('.attachment-name').textContent = a.filename;
      link.querySelector('.attachment-meta').textContent =
          (a.contentType || '') + ' · ' + formatBytes(a.sizeBytes);
    }
    return link;
  };

  const renderAttachmentTray = (attachments) => {
    const tray = document.createElement('div');
    tray.className = 'message-attachments';
    for (const a of attachments) tray.append(buildAttachmentLink(a));
    return tray;
  };

  // ---------- Poll widget ----------
  // Click-to-vote with bar visualisation. Reactions on the host message stay independent —
  // they're emoji reactions, not votes. Mobile: each option is a full-width ≥44px button so
  // it's a comfortable tap target on phones; the bar fills the button's background instead
  // of sitting beside it.
  const renderPollWidget = (poll) => {
    const root = document.createElement('div');
    root.className = 'poll-widget';
    root.dataset.pollId = poll.id;

    const q = document.createElement('div');
    q.className = 'poll-question';
    q.textContent = poll.question;
    root.append(q);

    const list = document.createElement('ul');
    list.className = 'poll-options';
    for (const opt of poll.options) list.append(renderPollOption(poll, opt));
    root.append(list);

    const footer = document.createElement('div');
    footer.className = 'poll-footer';

    const tally = document.createElement('span');
    tally.className = 'poll-tally';
    tally.textContent = poll.totalVoters + ' vote' + (poll.totalVoters === 1 ? '' : 's');
    footer.append(tally);

    if (poll.myVoteOptionId) {
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'poll-remove-vote';
      remove.textContent = 'Remove vote';
      remove.addEventListener('click', () => removePollVote(poll.id, root));
      footer.append(remove);
    }
    root.append(footer);
    return root;
  };

  const renderPollOption = (poll, opt) => {
    const li = document.createElement('li');
    li.className = 'poll-option';
    li.dataset.optionId = opt.id;

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'poll-option-btn';
    if (opt.id === poll.myVoteOptionId) btn.classList.add('voted');
    btn.setAttribute('aria-pressed', opt.id === poll.myVoteOptionId ? 'true' : 'false');

    const denominator = Math.max(poll.totalVoters || 0, 1);
    const pct = (opt.voteCount / denominator) * 100;
    const bar = document.createElement('span');
    bar.className = 'poll-option-bar';
    bar.style.width = pct.toFixed(1) + '%';

    const label = document.createElement('span');
    label.className = 'poll-option-label';
    label.textContent = opt.label;

    const count = document.createElement('span');
    count.className = 'poll-option-count';
    count.textContent = opt.voteCount;

    btn.append(bar, label, count);
    btn.addEventListener('click', () => castPollVote(poll.id, opt.id, btn.closest('.poll-widget')));

    li.append(btn);
    return li;
  };

  const castPollVote = async (pollId, optionId, widgetEl) => {
    if (!widgetEl) return;
    try {
      const res = await fetch('/api/polls/' + encodeURIComponent(pollId) + '/vote', {
        method: 'POST', headers: headers(),
        body: JSON.stringify({ optionId }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        alert('Vote failed: ' + (err.message || err.error || res.statusText));
        return;
      }
      const dto = await res.json();
      widgetEl.replaceWith(renderPollWidget(dto));
    } catch (e) {
      // Network blip — leave the existing widget; live broadcast will reconcile if it lands.
    }
  };

  const removePollVote = async (pollId, widgetEl) => {
    if (!widgetEl) return;
    try {
      const res = await fetch('/api/polls/' + encodeURIComponent(pollId) + '/vote', {
        method: 'DELETE', headers: headers(),
      });
      if (!res.ok) return;
      const dto = await res.json();
      widgetEl.replaceWith(renderPollWidget(dto));
    } catch (e) { /* ignore */ }
  };

  /**
   * Apply a {@code poll-vote} broadcast to a rendered widget without clobbering the local
   * "I voted for X" indicator — that comes from this user's own POST/DELETE round-trip and
   * isn't carried correctly in a topic-level broadcast (the broadcast contains the *actor's*
   * myVoteOptionId, not the recipient's).
   */
  const applyPollUpdate = (messageId, dto) => {
    if (!messageId || !dto) return;
    const li = findMessageEl(messageId);
    if (!li) return;
    const widget = li.querySelector('.poll-widget');
    if (!widget) return;
    const myVotedEl = widget.querySelector('.poll-option-btn.voted');
    const myOptionId = myVotedEl ? myVotedEl.closest('.poll-option').dataset.optionId : null;
    const merged = Object.assign({}, dto, { myVoteOptionId: myOptionId });
    widget.replaceWith(renderPollWidget(merged));
  };

  /**
   * Replace every server-rendered <div class="poll-placeholder" data-poll-id="..."> with the
   * live widget. Page-load only — runs once after the initial Thymeleaf message list is in
   * the DOM. New messages arriving via WS already include the poll directly in the dto and
   * are rendered through {@code renderPollWidget} in {@code appendMessage}.
   */
  const hydratePollPlaceholders = async () => {
    const placeholders = document.querySelectorAll('.poll-placeholder[data-poll-id]');
    if (!placeholders.length) return;
    await Promise.all([...placeholders].map(async (el) => {
      const id = el.dataset.pollId;
      try {
        const res = await fetch('/api/polls/' + encodeURIComponent(id), {
          headers: headers(), credentials: 'same-origin',
        });
        if (!res.ok) {
          el.remove();
          return;
        }
        const dto = await res.json();
        el.replaceWith(renderPollWidget(dto));
      } catch (e) {
        el.remove();
      }
    }));
  };
  hydratePollPlaceholders();

  // ---------- Image lightbox ----------
  // One delegate covers both server-rendered messages (Thymeleaf in channels.html) and
  // JS-rendered ones; otherwise the historical-message links would just download via href.
  document.addEventListener('click', (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const link = e.target.closest('a.attachment-image');
    if (!link) return;
    e.preventDefault();
    const img = link.querySelector('img');
    openLightbox(link.getAttribute('href'), img?.alt || link.title || '');
  });

  let lightboxEl = null;
  const ensureLightbox = () => {
    if (lightboxEl) return lightboxEl;
    lightboxEl = document.createElement('div');
    lightboxEl.className = 'lightbox';
    lightboxEl.hidden = true;
    lightboxEl.innerHTML =
        '<div class="lightbox-toolbar">' +
          '<a class="lightbox-btn" data-action="download" title="Download" aria-label="Download">' +
            '<svg class="icon"><use href="#icon-download"/></svg>' +
          '</a>' +
          '<a class="lightbox-btn" data-action="open" target="_blank" rel="noopener" title="Open in new tab" aria-label="Open in new tab">↗</a>' +
          '<button type="button" class="lightbox-btn" data-action="close" title="Close (Esc)" aria-label="Close">' +
            '<svg class="icon"><use href="#icon-close"/></svg>' +
          '</button>' +
        '</div>' +
        '<img class="lightbox-img" alt=""/>';
    document.body.appendChild(lightboxEl);
    lightboxEl.addEventListener('click', (e) => {
      if (e.target === lightboxEl) closeLightbox();
    });
    lightboxEl.querySelector('[data-action="close"]').addEventListener('click', closeLightbox);
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && lightboxEl && !lightboxEl.hidden) closeLightbox();
    });
    return lightboxEl;
  };
  const openLightbox = (url, filename) => {
    const el = ensureLightbox();
    el.querySelector('.lightbox-img').src = url;
    el.querySelector('.lightbox-img').alt = filename || '';
    const dl = el.querySelector('[data-action="download"]');
    dl.href = url;
    dl.setAttribute('download', filename || '');
    // The download endpoint returns Content-Disposition: attachment by default, which would
    // trigger a download instead of rendering in the new tab. Ask for inline disposition here.
    const sep = url.indexOf('?') === -1 ? '?' : '&';
    el.querySelector('[data-action="open"]').href = url + sep + 'disposition=inline';
    el.hidden = false;
    document.body.classList.add('lightbox-open');
  };
  const closeLightbox = () => {
    if (!lightboxEl) return;
    lightboxEl.hidden = true;
    lightboxEl.querySelector('.lightbox-img').src = '';
    document.body.classList.remove('lightbox-open');
  };

  const replaceMessageDom = (msg) => {
    const li = findMessageEl(msg.id);
    if (!li) return;
    // Detect an actual body edit (vs. a reaction-only update) so we only flash on edits.
    const prevBody = li.dataset.bodyMarkdown || '';
    const newBody = msg.bodyMarkdown || '';
    const isEdit = newBody !== prevBody;
    li.dataset.bodyMarkdown = newBody;
    const right = li.querySelector(':scope > div');
    if (!right) return;
    right.querySelectorAll('.message-body, .message-attachments, .message-reactions, .message-edit, .edited-tag, .poll-widget').forEach(n => n.remove());
    const meta = right.querySelector('.message-meta');
    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml;
      highlightCode(body);
      meta.after(body);
      if (isEdit) flashEdited(body);
    }
    if (msg.editedAt && meta && !meta.querySelector('.edited-tag')) {
      const tag = document.createElement('span');
      tag.className = 'edited-tag';
      tag.textContent = '(edited)';
      meta.appendChild(tag);
      if (isEdit) {
        tag.classList.add('just-changed');
        setTimeout(() => tag.classList.remove('just-changed'), 1000);
      }
    }
    if (msg.poll) {
      right.appendChild(renderPollWidget(msg.poll));
    }
    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }
    if (msg.reactions && msg.reactions.length) {
      right.appendChild(renderReactionTray(msg.reactions));
    }
    // Refresh action toolbar so edit visibility tracks the new body
    li.querySelector('.message-actions')?.remove();
    attachActions(li);
    positionDayDividers();
  };

  const flashEdited = (bodyEl) => {
    if (!bodyEl) return;
    bodyEl.classList.add('just-edited');
    setTimeout(() => bodyEl.classList.remove('just-edited'), 1500);
  };

  const removeMessageDom = (id) => {
    const li = findMessageEl(id);
    if (li) li.remove();
    // Drop any divider that was anchored to this message (orphans).
    document.querySelectorAll('#day-divider-layer .day-divider[data-anchor-id="' + CSS.escape(id) + '"]')
        .forEach(d => d.remove());
    // Remove from thread panel if present, and close if the parent itself got deleted.
    document.querySelectorAll('#thread-replies li[data-id="' + CSS.escape(id) + '"]').forEach(el => el.remove());
    const tp = document.querySelector('#thread-parent [data-id]');
    // dataset.id is always a string; id may arrive as a JSON number via the STOMP 'deleted'
    // frame. Coerce so the strict-equal doesn't silently miss the remote-delete case.
    if (tp && tp.dataset.id === String(id)) closeThread();
    positionDayDividers();
  };

  // Single delegate handles reactions / thread-indicator / msg-action clicks. Bound to
  // both the main feed and the thread panel so reactions + edit + delete all work in
  // either surface (a thread reply may have no twin in the main viewport).
  const handleMessageClick = (e) => {
    const reactionBtn = e.target.closest('.reaction');
    if (reactionBtn) {
      const li = reactionBtn.closest('li.message');
      if (li) toggleReaction(li.dataset.id, reactionBtn.dataset.emoji, reactionBtn.dataset.mine === 'true');
      return;
    }
    const threadBtn = e.target.closest('.thread-indicator');
    if (threadBtn) {
      const li = threadBtn.closest('li.message');
      if (li) openThread(li.dataset.id);
      return;
    }
    const btn = e.target.closest('.msg-action');
    if (!btn) return;
    const li = btn.closest('li.message');
    if (!li) return;
    const id = li.dataset.id;
    if (btn.dataset.action === 'edit') startEdit(li);
    else if (btn.dataset.action === 'delete') doDelete(id);
    else if (btn.dataset.action === 'reply') openThread(id);
    else if (btn.dataset.action === 'react') {
      openEmojiPicker(btn, (emoji) => toggleReaction(li.dataset.id, emoji, false));
    }
    else if (btn.dataset.action === 'permalink') copyPermalink(li);
  };
  if (messagesEl) {
    messagesEl.addEventListener('click', handleMessageClick);
    messagesEl.querySelectorAll('li.message').forEach(attachActions);
  }
  const threadPanelForClicks = document.getElementById('thread-panel');
  if (threadPanelForClicks) {
    threadPanelForClicks.addEventListener('click', handleMessageClick);
  }

  const startEdit = (li) => {
    if (li.querySelector('.message-edit')) return;
    const right = li.querySelector(':scope > div');
    const body = right.querySelector('.message-body');
    if (!body) return;
    const original = li.dataset.bodyMarkdown || '';
    const wrap = document.createElement('div');
    wrap.className = 'message-edit';
    wrap.innerHTML =
        '<textarea class="message-edit-input" rows="3"></textarea>' +
        '<div class="message-edit-actions">' +
        '<button type="button" class="message-edit-cancel">Cancel</button>' +
        '<button type="button" class="message-edit-save">Save</button>' +
        '</div>';
    const ta = wrap.querySelector('textarea');
    ta.value = original;
    body.replaceWith(wrap);
    ta.focus();
    ta.setSelectionRange(ta.value.length, ta.value.length);

    wrap.querySelector('.message-edit-cancel').addEventListener('click', () => {
      wrap.replaceWith(body);
    });
    wrap.querySelector('.message-edit-save').addEventListener('click', async () => {
      const newBody = ta.value.trim();
      if (!newBody) { alert('Body cannot be empty'); return; }
      const id = li.dataset.id;
      const res = await fetch('/api/messages/' + id, {
        method: 'PATCH',
        headers: headers(),
        body: JSON.stringify({ body: newBody })
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert('Edit failed: ' + (err.error || res.statusText));
        return;
      }
      // WS broadcast triggers replaceMessageDom — nothing else to do.
    });
    ta.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') {
        ev.preventDefault();
        wrap.replaceWith(body);
      } else if (ev.key === 'Enter' && !ev.shiftKey && !ev.ctrlKey && !ev.metaKey && !ev.altKey) {
        if (ev.isComposing || ev.keyCode === 229) return;
        ev.preventDefault();
        wrap.querySelector('.message-edit-save').click();
      }
    });
    wireAutoResize(ta);
  };

  async function doDelete(id) {
    if (!confirm('Delete this message?')) return;
    const res = await fetch('/api/messages/' + id, {
      method: 'DELETE',
      headers: headers()
    });
    if (!res.ok && res.status !== 204) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Delete failed: ' + (err.error || res.statusText));
      return;
    }
    // Optimistically remove the message in this tab the moment the server confirms — don't
    // wait for the WS broadcast round-trip. removeMessageDom is idempotent so the eventual
    // /topic/channels/{id} 'deleted' frame is a harmless no-op here, while still updating
    // any other tabs / other users that have the channel open.
    removeMessageDom(id);
  }

  // ---------- Permalink ----------
  // Query for the server (renders context-around), fragment for the client-side scroll/highlight.
  const permalinkFor = (messageId) => {
    const id = encodeURIComponent(messageId);
    return window.location.origin + '/channels/' + activeChannelId + '?m=' + id + '#m=' + id;
  };
  const flashToast = (text) => {
    const el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text;
    document.body.appendChild(el);
    setTimeout(() => { el.classList.add('show'); });
    setTimeout(() => { el.classList.remove('show'); }, 2200);
    setTimeout(() => { el.remove(); }, 2700);
  };
  async function copyPermalink(li) {
    const url = permalinkFor(li.dataset.id);
    try {
      await navigator.clipboard.writeText(url);
      flashToast('Link copied');
    } catch (_) {
      // Clipboard API may be unavailable on insecure origins — fall back to a prompt.
      window.prompt('Copy this link', url);
    }
  }
  const scrollToPermalinkTarget = () => {
    const m = (window.location.hash || '').match(/^#m=([^&]+)/);
    if (!m) return;
    const id = decodeURIComponent(m[1]);
    const el = findMessageEl(id);
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: 'auto' });
    el.classList.add('flash-highlight');
    setTimeout(() => el.classList.remove('flash-highlight'), 1800);
  };
  // Run on initial load + when fragment changes (e.g. user navigates within page).
  if (messagesEl) {
    setTimeout(scrollToPermalinkTarget, 50);
    window.addEventListener('hashchange', scrollToPermalinkTarget);
  }

  // ---------- Thread panel ----------
  const threadPanel = document.getElementById('thread-panel');
  const threadParentEl = document.getElementById('thread-parent');
  const threadRepliesEl = document.getElementById('thread-replies');
  const threadComposerForm = document.getElementById('thread-composer');
  const threadInput = document.getElementById('thread-input');
  const threadEmojiBtn = document.getElementById('thread-emoji');
  const threadCloseBtn = document.getElementById('thread-close');
  let openThreadId = null;
  threadEmojiBtn?.addEventListener('click', () => {
    openEmojiPicker(threadEmojiBtn, (e) => insertAtCursor(threadInput, e));
  });

  const closeThread = () => {
    if (!threadPanel) return;
    threadPanel.hidden = true;
    document.body.classList.remove('thread-open');
    openThreadId = null;
    if (threadParentEl) threadParentEl.innerHTML = '';
    if (threadRepliesEl) threadRepliesEl.innerHTML = '';
    if (threadInput) {
      threadInput.value = '';
      threadInput._autoResize?.();
    }
  };
  threadCloseBtn?.addEventListener('click', closeThread);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && threadPanel && !threadPanel.hidden) closeThread();
  });

  async function openThread(parentId) {
    if (!threadPanel) return;
    const res = await fetch('/api/messages/' + parentId + '/thread');
    if (!res.ok) { alert('Could not load thread'); return; }
    const data = await res.json();
    openThreadId = parentId;
    threadParentEl.innerHTML = '';
    threadRepliesEl.innerHTML = '';
    threadParentEl.appendChild(renderThreadMessage(data.parent, true));
    for (const r of data.replies) threadRepliesEl.appendChild(renderThreadMessage(r, false));
    threadPanel.hidden = false;
    document.body.classList.add('thread-open');
    threadInput?.focus();
  }

  const renderThreadMessage = (msg, isParent) => {
    const li = document.createElement('li');
    li.className = 'message thread-message' + (isParent ? ' thread-parent-msg' : '');
    li.dataset.id = msg.id;
    li.dataset.author = msg.authorUsername;
    li.dataset.bodyMarkdown = msg.bodyMarkdown || '';
    const name = msg.authorDisplayName || msg.authorUsername;
    const created = new Date(msg.createdAt);
    const initial = (name || '?').slice(0, 1).toUpperCase();
    li.innerHTML = `
      <div>
        <div class="message-meta">
          <span class="author"></span>
          <time></time>
        </div>
      </div>`;
    const avatar = buildAvatarEl({
      username: msg.authorUsername,
      letter: initial,
      hasAvatar: msg.authorHasAvatar,
      avatarVersion: msg.authorAvatarVersion,
    });
    li.insertBefore(avatar, li.firstChild);
    const authorSpan = li.querySelector('.author');
    authorSpan.textContent = name;
    authorSpan.dataset.author = msg.authorUsername;
    appendAuthorHandle(li.querySelector('.message-meta'), msg.authorDisplayName, msg.authorUsername);
    // Re-append <time> after the handle so order stays: name, @handle, time.
    const timeEl = li.querySelector('time');
    li.querySelector('.message-meta').appendChild(timeEl);
    timeEl.textContent = formatTime(created);
    if (msg.editedAt) {
      const tag = document.createElement('span');
      tag.className = 'edited-tag';
      tag.textContent = '(edited)';
      li.querySelector('.message-meta').appendChild(tag);
    }
    const right = li.querySelector(':scope > div');
    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml;
      highlightCode(body);
      right.appendChild(body);
    }
    if (msg.reactions && msg.reactions.length) {
      right.appendChild(renderReactionTray(msg.reactions));
    }
    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }
    if (msg.parentId) li.dataset.parentId = msg.parentId;
    attachActions(li);
    return li;
  };

  const appendThreadReply = (msg) => {
    if (!threadPanel || threadPanel.hidden) return;
    if (msg.parentId !== openThreadId) return;
    // De-dupe: the sender appends optimistically from the HTTP response and the WS
    // broadcast follows. Whichever arrives second is a no-op.
    if (threadRepliesEl.querySelector('[data-id="' + msg.id + '"]')) return;
    threadRepliesEl.appendChild(renderThreadMessage(msg, false));
    threadRepliesEl.scrollTop = threadRepliesEl.scrollHeight;
  };

  threadComposerForm?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!openThreadId) return;
    const body = threadInput.value.trim();
    if (!body) return;
    const res = await fetch('/api/messages/' + openThreadId + '/replies', {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ body })
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Reply failed: ' + (err.error || res.statusText));
      return;
    }
    // Render the reply locally from the HTTP response so the sender sees it instantly,
    // independent of WS round-trip timing. appendThreadReply de-dupes by id, so the
    // broadcast that arrives moments later is a no-op.
    const dto = await res.json().catch(() => null);
    if (dto) appendThreadReply(dto);
    threadInput.value = '';
    threadInput._autoResize?.();
  });
  // Enter-to-send is handled by the top-level document keydown handler.
  wireAutoResize(threadInput);

  // Live markdown preview for the thread reply composer — same /api/preview path the
  // channel composer uses so the rendered HTML is identical.
  (function wireThreadPreview() {
    const pane = document.getElementById('thread-preview');
    const body = document.getElementById('thread-preview-body');
    if (!pane || !body || !threadInput) return;
    let debounce = null;
    let req = 0;
    async function refresh() {
      const text = threadInput.value;
      if (!text.trim()) {
        pane.hidden = true;
        body.innerHTML = '';
        return;
      }
      const myReq = ++req;
      try {
        const res = await fetch('/api/preview', {
          method: 'POST',
          headers: headers(),
          body: JSON.stringify({ body: text }),
        });
        if (!res.ok) return;
        const data = await res.json();
        if (myReq !== req) return;
        body.innerHTML = data.html || '';
        highlightCode(body);
        pane.hidden = !data.html;
      } catch (_) { /* leave previous render */ }
    }
    threadInput.addEventListener('input', () => {
      clearTimeout(debounce);
      debounce = setTimeout(refresh, 220);
    });
    threadComposerForm?.addEventListener('submit', () => {
      clearTimeout(debounce);
      pane.hidden = true;
      body.innerHTML = '';
    });
  })();

  // Tutorial overlay + sidebar filter were moved to ./chrome.js — see chrome.init() at the
  // top of this file. They were structurally independent of the message-feed code in here.
