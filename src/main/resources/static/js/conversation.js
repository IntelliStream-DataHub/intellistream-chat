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
 * Direct-message page client. Subscribes to /topic/conversations/{id} and posts
 * to /app/conversations/{id}/send via STOMP. Mirrors the channel chat patterns in
 * chat.js: messages render with reactions, edit/delete buttons, and attachment
 * trays. Threads / typing indicators / read counts intentionally stay channel-only.
 */
(function () {
  const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');
  const myUsername = meta('me-username');
  const isAdmin = meta('me-is-admin') === 'true';
  const conversationId = meta('active-conversation-id');
  if (!conversationId) return;

  const messagesEl = document.getElementById('messages');
  const composer = document.getElementById('composer');
  const input = document.getElementById('composer-input');

  const headers = () => {
    const h = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
    return h;
  };

  // Shared utilities live in chat-kit.js (window.ChatKit) — destructure for terseness.
  const {
    backfillAvatarColors,
    buildAvatarEl,
    dayKey,
    formatTime,
    formatBytes,
    insertAtCursor,
    wireAutoResize,
    wireAllFormatToolbars,
    wireLivePreview,
    openEmojiPicker,
  } = window.ChatKit;
  backfillAvatarColors();

  const lastMessageEl = () => {
    const items = messagesEl.querySelectorAll('li.message');
    return items.length ? items[items.length - 1] : null;
  };

  const appendMessage = (msg) => {
    if (!msg || !msg.id) return;
    // De-dupe across WS replays (and the upcoming local-append optimisation).
    if (messagesEl.querySelector('li.message[data-id="' + msg.id + '"]')) return;
    // Measure BEFORE appending: only follow the tail if the reader is already near it, or the
    // message is their own — otherwise don't yank someone reading history down (BUG-15).
    const nearBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 120;
    const created = new Date(msg.createdAt);
    const curDay = dayKey(created);
    const prev = lastMessageEl();
    const prevDay = prev ? prev.dataset.day : null;
    const prevAuthor = prev ? prev.dataset.author : null;

    const sameAuthor = prevAuthor === msg.authorUsername && curDay === prevDay;
    const li = document.createElement('li');
    li.className = 'message' + (sameAuthor ? ' grouped' : '');
    li.dataset.id = msg.id;
    li.dataset.createdAt = msg.createdAt;
    li.dataset.author = msg.authorUsername;
    li.dataset.day = curDay;

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
    meta.append(author, time);
    right.appendChild(meta);

    li.dataset.bodyMarkdown = msg.bodyMarkdown || '';
    if (msg.editedAt) {
      const tag = document.createElement('span');
      tag.className = 'edited-tag';
      tag.textContent = '(edited)';
      meta.appendChild(tag);
    }

    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml || '';
      right.appendChild(body);
    }

    if (msg.reactions && msg.reactions.length) {
      right.appendChild(renderReactionTray(msg.reactions));
    }

    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }

    li.append(avatar, right);
    messagesEl.appendChild(li);
    attachActions(li);
    if (nearBottom || msg.authorUsername === myUsername) li.scrollIntoView({ block: 'end' });
  };

  // ---------- Reactions / actions toolbar (mirrors chat.js patterns) ----------
  const renderReactionTray = (groups) => {
    const tray = document.createElement('div');
    tray.className = 'message-reactions';
    for (const g of groups) tray.appendChild(buildReactionBubble(g));
    return tray;
  };
  const buildReactionBubble = (g) => {
    const mineDerived = g.usernames && g.usernames.includes(myUsername);
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
  const buildActions = (authorUsername, hasBody) => {
    const isMine = authorUsername === myUsername;
    const canDelete = isMine || isAdmin;
    const actions = document.createElement('div');
    actions.className = 'message-actions';
    let html = '';
    if (!isMine) html += '<button type="button" class="msg-action" data-action="react" title="Add reaction">😊</button>';
    if (isMine && hasBody) html += '<button type="button" class="msg-action" data-action="edit" title="Edit">✏️</button>';
    if (canDelete) html += '<button type="button" class="msg-action" data-action="delete" title="Delete">🗑</button>';
    actions.innerHTML = html;
    return actions;
  };
  const attachActions = (li) => {
    if (!li || li.querySelector('.message-actions')) return;
    const author = li.dataset.author;
    const hasBody = !!(li.dataset.bodyMarkdown && li.dataset.bodyMarkdown.length > 0);
    const actions = buildActions(author, hasBody);
    if (actions.children.length > 0) li.appendChild(actions);
  };
  // Wire actions for server-rendered messages on initial load.
  messagesEl?.querySelectorAll('li.message').forEach(attachActions);

  const findMessageEl = (id) =>
      messagesEl ? messagesEl.querySelector('li.message[data-id="' + CSS.escape(id) + '"]') : null;

  const replaceMessageDom = (msg) => {
    const li = findMessageEl(msg.id);
    if (!li) return;
    const right = li.querySelector(':scope > div');
    if (!right) return;
    const isEdit = (msg.bodyMarkdown || '') !== (li.dataset.bodyMarkdown || '');
    // If the author has an edit form open and this update is only a reaction/attachment change
    // (not a body edit), refresh just those trays — removing .message-edit here would destroy
    // their unsaved draft the moment anyone reacts (BUG-14).
    if (right.querySelector('.message-edit') && !isEdit) {
      right.querySelectorAll('.message-reactions, .message-attachments').forEach(n => n.remove());
      if (msg.attachments && msg.attachments.length) right.appendChild(renderAttachmentTray(msg.attachments));
      if (msg.reactions && msg.reactions.length) right.appendChild(renderReactionTray(msg.reactions));
      return;
    }
    li.dataset.bodyMarkdown = msg.bodyMarkdown || '';
    right.querySelectorAll('.message-body, .message-reactions, .message-attachments, .message-edit, .edited-tag').forEach(n => n.remove());
    const meta = right.querySelector('.message-meta');
    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml || '';
      meta.after(body);
    }
    if (msg.editedAt && meta && !meta.querySelector('.edited-tag')) {
      const tag = document.createElement('span');
      tag.className = 'edited-tag';
      tag.textContent = '(edited)';
      meta.appendChild(tag);
    }
    if (msg.reactions && msg.reactions.length) {
      right.appendChild(renderReactionTray(msg.reactions));
    }
    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }
    li.querySelector('.message-actions')?.remove();
    attachActions(li);
  };

  const removeMessageDom = (id) => {
    const li = findMessageEl(id);
    if (li) li.remove();
  };

  async function toggleReaction(messageId, emoji, currentlyMine) {
    const url = '/api/conversations/messages/' + messageId + '/reactions'
        + (currentlyMine ? '/' + encodeURIComponent(emoji) : '');
    const res = await fetch(url, {
      method: currentlyMine ? 'DELETE' : 'POST',
      headers: headers(),
      body: currentlyMine ? undefined : JSON.stringify({ emoji }),
    });
    if (!res.ok && res.status !== 204) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Reaction failed: ' + (err.error || err.message || res.statusText));
    }
  }
  // Lets the mobile long-press action sheet's emoji strip toggle reactions.
  window.ChatKit?.setQuickReaction(toggleReaction);

  async function deleteMessage(id) {
    if (!confirm('Delete this message?')) return;
    const res = await fetch('/api/conversations/messages/' + id, {
      method: 'DELETE',
      headers: headers(),
    });
    if (!res.ok && res.status !== 204) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Delete failed: ' + (err.error || err.message || res.statusText));
      return;
    }
    removeMessageDom(id);
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
      const res = await fetch('/api/conversations/messages/' + id, {
        method: 'PATCH',
        headers: headers(),
        body: JSON.stringify({ body: newBody }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert('Edit failed: ' + (err.error || err.message || res.statusText));
        return;
      }
      // WS broadcast triggers replaceMessageDom — nothing else to do.
    });
    ta.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') { ev.preventDefault(); wrap.replaceWith(body); }
      else if (ev.key === 'Enter' && !ev.shiftKey && !ev.ctrlKey && !ev.metaKey && !ev.altKey) {
        if (ev.isComposing || ev.keyCode === 229) return;
        ev.preventDefault();
        wrap.querySelector('.message-edit-save').click();
      }
    });
    wireAutoResize(ta);
  };

  // Click delegation for reactions / edit / delete / react.
  messagesEl?.addEventListener('click', (e) => {
    const reactionBtn = e.target.closest('.reaction');
    if (reactionBtn) {
      const li = reactionBtn.closest('li.message');
      if (li) toggleReaction(li.dataset.id, reactionBtn.dataset.emoji, reactionBtn.dataset.mine === 'true');
      return;
    }
    const btn = e.target.closest('.msg-action');
    if (!btn) return;
    const li = btn.closest('li.message');
    if (!li) return;
    const id = li.dataset.id;
    if (btn.dataset.action === 'edit') startEdit(li);
    else if (btn.dataset.action === 'delete') deleteMessage(id);
    else if (btn.dataset.action === 'react') {
      openEmojiPicker(btn, (emoji) => toggleReaction(id, emoji, false));
    }
  });

  // ---------- Attachment rendering ----------
  // Mirrors chat.js's renderAttachmentTray + buildAttachmentLink for DMs. Image attachments
  // open an in-page lightbox via the document-level delegate that ships in chat.js — but
  // chat.js isn't loaded here, so wire a minimal local delegate further below.
  function buildAttachmentLink(a) {
    const isImage = (a.contentType || '').startsWith('image/');
    const link = document.createElement('a');
    link.href = a.downloadUrl;
    link.title = a.filename;
    if (isImage) {
      link.className = 'attachment-image';
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
  }
  function renderAttachmentTray(attachments) {
    const tray = document.createElement('div');
    tray.className = 'message-attachments';
    for (const a of attachments) tray.append(buildAttachmentLink(a));
    return tray;
  }
  // Minimal lightbox delegate so DM image attachments open full-screen on click.
  document.addEventListener('click', (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const link = e.target.closest('a.attachment-image');
    if (!link) return;
    e.preventDefault();
    const url = link.getAttribute('href');
    const sep = url.indexOf('?') === -1 ? '?' : '&';
    window.open(url + sep + 'disposition=inline', '_blank', 'noopener');
  });

  // ---------- STOMP connection ----------
  const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
  const stomp = new StompJs.Client({
    brokerURL: wsUrl,
    reconnectDelay: 4000,
  });

  // The conversation topic carries ConversationMessageDto (new message) and lightweight
  // ConversationEvent envelopes (member-added, message-updated, message-deleted). Discriminate
  // by the `type` field that only ConversationEvent carries.
  function handleFrame(payload) {
    if (payload && payload.type === 'member-added') {
      if (typeof window.__refreshGroupMembers === 'function') window.__refreshGroupMembers();
      return;
    }
    if (payload && payload.type === 'message-updated') {
      if (payload.message) replaceMessageDom(payload.message);
      return;
    }
    if (payload && payload.type === 'message-deleted') {
      if (payload.messageId) removeMessageDom(payload.messageId);
      return;
    }
    appendMessage(payload);
  }

  let stompConnectedBefore = false;
  let backfilling = false;
  const pendingLive = [];

  // On a RECONNECT the simple broker replayed nothing, so DMs sent during the outage are missing
  // (BUG-3 — this catch-up existed on the channel page but never the DM page). Page ?after= until
  // caught up (server caps each page at 50); buffer live frames during the backfill so they can't
  // land ahead of the older rows still loading, then replay them in arrival order.
  async function backfillMissedMessages() {
    backfilling = true;
    try {
      for (let page = 0; page < 50; page++) {
        const last = lastMessageEl();
        const after = last ? last.dataset.createdAt : null;
        if (!after) break;
        const rows = await fetch('/api/conversations/' + conversationId + '/messages?after='
              + encodeURIComponent(after), { headers: headers() })
          .then((r) => (r.ok ? r.json() : []))
          .catch(() => []);
        if (!rows || rows.length === 0) break;
        rows.forEach(appendMessage);
        if (rows.length < 50) break;
      }
    } finally {
      backfilling = false;
      pendingLive.splice(0).forEach(handleFrame);
    }
  }

  stomp.onConnect = () => {
    if (stompConnectedBefore) backfillMissedMessages();
    stompConnectedBefore = true;
    stomp.subscribe('/topic/conversations/' + conversationId, (frame) => {
      try {
        const payload = JSON.parse(frame.body);
        if (backfilling) { pendingLive.push(payload); return; }
        handleFrame(payload);
      } catch (e) { /* ignore malformed frame */ }
    });
    if (window.Presence) window.Presence.attachStomp(stomp);
  };
  // Surface failures so the user can spot a CSP / handshake / auth issue in devtools
  // instead of an opaque "Not connected" with no clue why.
  stomp.onWebSocketError = (e) => console.warn('[DM] WebSocket error', e);
  stomp.onStompError = (frame) => console.warn('[DM] STOMP error', frame?.headers, frame?.body);
  stomp.activate();

  // ---------- Send ----------
  // Wait briefly for STOMP to connect (covers the page-load race where the user
  // submits before the WebSocket handshake finishes). Surface a clear error if
  // we still can't reach the broker — silent drop made the previous version look
  // broken on Bob's screen even though typing worked fine.
  async function awaitConnected(timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (!stomp.connected && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 50));
    }
    return stomp.connected;
  }

  // Send via STOMP when connected; otherwise fall back to HTTP POST so the message
  // still reaches the server when the WebSocket handshake is blocked / failing.
  // The server broadcasts the saved DTO over /topic/conversations/{id} either way,
  // so connected peers get the same live experience.
  async function sendBody(body) {
    if (await awaitConnected(800)) {
      try {
        stomp.publish({
          destination: '/app/conversations/' + conversationId + '/send',
          body: JSON.stringify({ body }),
        });
        return true;
      } catch (err) {
        console.warn('[DM] STOMP publish failed, falling back to HTTP', err);
      }
    }
    try {
      const res = await fetch('/api/conversations/' + conversationId + '/messages', {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ body }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert('Send failed: ' + (err.error || res.statusText));
        return false;
      }
      const dto = await res.json().catch(() => null);
      if (dto) appendMessage(dto);
      return true;
    } catch (err) {
      alert('Could not send the message: ' + (err?.message || err));
      return false;
    }
  }

  composer.addEventListener('submit', async (e) => {
    e.preventDefault();
    const body = (input.value || '').trim();
    if (!body) return;
    const ok = await sendBody(body);
    if (ok) {
      input.value = '';
      input.focus();
      input._autoResize?.();
    }
  });

  // Enter to send, Shift+Enter for newline. Skip while an IME composition is active
  // so CJK input doesn't accidentally submit.
  input.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.isComposing || e.keyCode === 229) return;
    e.preventDefault();
    composer.requestSubmit();
  });

  // ---------- Composer parity (markdown toolbar, auto-resize, live preview) ----------
  wireAllFormatToolbars();
  wireAutoResize(input);
  wireLivePreview({
    textarea: input,
    pane: document.getElementById('composer-preview'),
    body: document.getElementById('composer-preview-body'),
    form: composer,
    headers,
  });
  composer.addEventListener('submit', () => input._autoResize?.());

  const emojiBtn = document.getElementById('composer-emoji');
  if (emojiBtn) {
    emojiBtn.addEventListener('click', () => {
      openEmojiPicker(emojiBtn, (e) => insertAtCursor(input, e));
    });
  }

  // ---------- Attach (file upload) ----------
  // Multipart POST to /api/conversations/{id}/attachments. Server creates a new conversation
  // message with the attachment, broadcasts via STOMP, our subscription appends it. The
  // composer's text body, if any, is sent as a regular STOMP send before the upload so the
  // user's typed message and their file don't collapse into a single attachment caption.
  const attachBtn = document.getElementById('composer-attach');
  const fileInput = document.getElementById('composer-file');
  if (attachBtn && fileInput) {
    attachBtn.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', async () => {
      const files = Array.from(fileInput.files || []);
      fileInput.value = '';
      if (files.length === 0) return;

      // Flush any typed text first as its own message, before the attachments arrive,
      // so the order in the thread reads naturally.
      const typed = (input.value || '').trim();
      if (typed) {
        await sendBody(typed);
        input.value = '';
        input._autoResize?.();
      }

      for (const f of files) {
        const fd = new FormData();
        fd.append('file', f);
        try {
          const headersOut = {};
          if (csrfToken && csrfHeader) headersOut[csrfHeader] = csrfToken;
          const res = await fetch('/api/conversations/' + conversationId + '/attachments', {
            method: 'POST',
            headers: headersOut,
            body: fd,
          });
          if (!res.ok) {
            const err = await res.json().catch(() => ({ error: res.statusText }));
            let msg;
            if (res.status === 413 && typeof err.maxBytes === 'number') {
              const mib = (err.maxBytes / (1024 * 1024)).toFixed(0);
              msg = 'File too large — your account is capped at ' + mib + ' MiB per upload.';
            } else {
              msg = err.message || err.error || res.statusText;
            }
            alert('Upload failed for ' + f.name + ': ' + msg);
            return;
          }
          // The WebSocket broadcast will deliver the new ConversationMessageDto to all
          // subscribers (including us) and appendMessage handles rendering.
        } catch (err) {
          alert('Upload failed for ' + f.name + ': ' + (err?.message || err));
          return;
        }
      }
    });
  }

  // Sidebar mobile toggle (mirrors chat/index.js). The CSS slide-in keys off
  // body.sidebar-open — the earlier `.sidebar.open` class had no CSS behind it,
  // so the hamburger silently did nothing on this page.
  const sidebarToggle = document.getElementById('sidebar-toggle');
  const backdrop = document.getElementById('sidebar-backdrop');
  if (sidebarToggle) {
    const setOpen = (open) => {
      document.body.classList.toggle('sidebar-open', open);
      sidebarToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (backdrop) backdrop.hidden = !open;
    };
    sidebarToggle.addEventListener('click', () => setOpen(!document.body.classList.contains('sidebar-open')));
    backdrop?.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && document.body.classList.contains('sidebar-open')) setOpen(false);
    });
    window.addEventListener('resize', () => {
      if (window.innerWidth > 768 && document.body.classList.contains('sidebar-open')) setOpen(false);
    });
  }

  // Group-create form in the sidebar — mirrors chat.js. Kept inline rather than in a
  // shared module because both pages load both files would be wasteful for one form.
  document.getElementById('sidebar-create-group-btn')?.addEventListener('click', () => {
    document.getElementById('sidebar-create-group-toggle')?.click();
  });
  document.getElementById('create-group-form-sidebar')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const title = (fd.get('title') || '').toString().trim();
    const members = (fd.get('members') || '').toString()
        .split(/[,\s]+/)
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

  // Auto-scroll to the bottom on first paint. Re-runs after a tick and once images
  // load, since avatars / inline images can grow content height *after* the initial
  // scroll, leaving the viewport short of the latest message.
  const scrollToBottom = () => {
    if (!messagesEl) return;
    messagesEl.scrollTop = messagesEl.scrollHeight;
  };
  if (messagesEl) {
    scrollToBottom();
    requestAnimationFrame(scrollToBottom);
    setTimeout(scrollToBottom, 50);
    setTimeout(scrollToBottom, 300);
    messagesEl.querySelectorAll('img').forEach((img) => {
      if (!img.complete) img.addEventListener('load', scrollToBottom, { once: true });
    });
  }

  // ---------- Group members panel ----------
  // Only renders when the page is a GROUP conversation (the template already gates
  // the section + toggle button on activeConversation.type). We fetch /members on
  // demand the first time the panel opens, and refresh when a member-added event
  // arrives over /topic/conversations/{id}.
  const channelView = document.querySelector('.channel-view');
  const isGroup = channelView?.dataset.conversationType === 'GROUP';
  if (isGroup) {
    const toggle = document.getElementById('group-members-toggle');
    const panel = document.getElementById('group-members-panel');
    const closeBtn = document.getElementById('group-members-close');
    const list = document.getElementById('group-members-list');
    const countEl = document.getElementById('group-members-count');
    const addForm = document.getElementById('group-add-member-form');

    let loaded = false;
    let currentMembers = [];

    const renderMembers = (members) => {
      currentMembers = members;
      countEl.textContent = String(members.length);
      list.innerHTML = '';
      if (!members.length) {
        const empty = document.createElement('li');
        empty.className = 'dm-empty';
        empty.textContent = 'No members yet.';
        list.appendChild(empty);
        return;
      }
      for (const m of members) {
        const li = document.createElement('li');
        const name = m.displayName || m.username;
        const av = buildAvatarEl({
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
        if (m.admin) {
          const badge = document.createElement('small');
          badge.className = 'dm-admin-tag';
          badge.title = 'Workspace administrator';
          badge.textContent = 'admin';
          li.appendChild(badge);
        }
        list.appendChild(li);
      }
    };

    const loadMembers = async () => {
      try {
        const res = await fetch('/api/conversations/' + conversationId + '/members',
            { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
        if (!res.ok) throw new Error('members load failed: ' + res.status);
        renderMembers(await res.json());
        loaded = true;
      } catch (e) {
        list.innerHTML = '<li class="dm-empty">Could not load members.</li>';
      }
    };

    const setOpen = (open) => {
      if (open && !loaded) loadMembers();
      panel.hidden = !open;
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    };
    toggle?.addEventListener('click', (e) => {
      e.stopPropagation();
      setOpen(panel.hidden);
    });
    closeBtn?.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && !panel.hidden) setOpen(false);
    });

    addForm?.addEventListener('submit', async (e) => {
      e.preventDefault();
      const fd = new FormData(addForm);
      const username = (fd.get('username') || '').toString().trim();
      if (!username) return;
      const res = await fetch('/api/conversations/' + conversationId + '/members', {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ username }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        alert('Could not add member: ' + (err.message || err.error || res.statusText));
        return;
      }
      addForm.reset();
      await loadMembers();
    });

    // Eagerly populate the count badge so the panel header shows "Members (N)" before
    // the user opens it. Cheap — same endpoint as the open-panel fetch.
    loadMembers();
    // Expose for the STOMP member-added handler defined above; one tiny window-level
    // surface beats threading the function through closure boundaries.
    window.__refreshGroupMembers = loadMembers;
  }
})();
