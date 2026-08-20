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
 * Direct-message page client. Subscribes to /topic/conversations/{id} and posts
 * to /app/conversations/{id}/send via STOMP. Mirrors the channel chat patterns in
 * chat.js: messages render with reactions, edit/delete buttons, attachment trays
 * and threads.
 *
 * Threads used to be channel-only, and the reason given was that a DM is a simpler
 * surface. It is not: a DM gets long and busy exactly the way a channel does, and
 * when it does, the room three pixels to its left is visibly better at it. The
 * panel, its composer and its stale-request guard are ChatKit.createThreadPanel —
 * shared with the channel page by construction rather than by copy.
 */
(function () {
  const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');
  const myUsername = meta('me-username');
  const isAdmin = meta('me-is-admin') === 'true';
  const conversationId = meta('active-conversation-id');
  const lastReadAt = meta('conversation-last-read-at');
  const isSoloConversation = meta('conversation-solo') === 'true';
  const accountNotifyDefault = meta('me-notify-default') || 'MENTIONS';
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
    appendAuthorHandle,
    applyThreadIndicator,
    createThreadPanel,
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
    // A reply belongs in its thread, not in the feed. Its parent's "N replies" indicator moves
    // either way — that is the only trace a thread leaves in the conversation, and it has to move
    // whether or not the panel happens to be open on it.
    if (msg.parentId) {
      threadPanel?.appendReply(msg);
      bumpThreadIndicator(msg.parentId, +1);
      return;
    }
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

    // The link card, if the DTO carries one; a live message's arrives as a `link-preview` frame.
    const preview = window.ChatKit.buildLinkPreviewEl(msg.linkPreview);
    if (preview) right.appendChild(preview);

    if (msg.reactions && msg.reactions.length) {
      right.appendChild(renderReactionTray(msg.reactions));
    }

    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }
    if (msg.replyCount > 0) {
      right.appendChild(window.ChatKit.buildThreadIndicator(msg.replyCount));
    }

    li.append(avatar, right);
    messagesEl.appendChild(li);
    attachActions(li);
    if (nearBottom || msg.authorUsername === myUsername) {
      // Scroll now and again as each image lands. An image attachment has no height until its
      // bytes arrive, so a single scroll stops at what is momentarily the bottom and the picture
      // then pushes itself below the fold. The first-paint path below already accounts for this;
      // the live append path did not.
      const stick = () => li.scrollIntoView({ block: 'end' });
      stick();
      li.querySelectorAll('img').forEach((img) => {
        if (img.complete) return;
        img.addEventListener('load', stick, { once: true });
        img.addEventListener('error', stick, { once: true });
      });
    }
  };

  // ---------- Server-rendered timestamps ----------
  /*
   * Re-key the day of each server-rendered message from its instant.
   *
   * The <time> text itself is already handled generically by ChatTime.rewriteAll(), and the server
   * rendered it in the viewer's zone to begin with. What can still be stale is data-day: the server
   * computed it from the best zone it had, and when the browser's detected zone overrules that one
   * (a first sign-in whose zone was only inferred from Accept-Language, or somebody who has since
   * travelled) the key describes a different calendar day than the one the reader is in. It is read
   * as prevDay when the next live message decides whether to group under the message above it, so a
   * stale key silently breaks grouping at exactly the boundary a reader is watching.
   *
   * Cheap and idempotent: when the zones agree — the common case — every key is rewritten to the
   * value it already had.
   */
  const rekeyServerDays = () => {
    if (!messagesEl) return;
    messagesEl.querySelectorAll('li.message[data-created-at]').forEach((li) => {
      li.dataset.day = dayKey(new Date(li.dataset.createdAt));
    });
  };
  rekeyServerDays();

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
  // ---------- Saved messages ----------
  // Which of this conversation's messages the viewer has saved, as strings so a data-id compares
  // directly. Fetched once rather than carried on every ConversationMessageDto: a save is a fact
  // about one reader, and putting it on the message would mean a join on the feed's read path to
  // serve it. Same shape as the channel page, same reasoning.
  //
  // The endpoint and its tests have been here since saved messages landed; the DM page just never
  // got the button, because its action row lives in a file that change did not touch.
  const savedMessageIds = new Set();
  const repaintActions = (id) => {
    const sel = id === undefined
        ? 'li.message'
        : 'li.message[data-id="' + CSS.escape(String(id)) + '"]';
    document.querySelectorAll(sel).forEach((el) => {
      el.querySelector('.message-actions')?.remove();
      attachActions(el);
    });
  };
  const loadSavedIds = async () => {
    try {
      const res = await fetch('/api/saved/conversation-ids?conversationId='
              + encodeURIComponent(conversationId),
          { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
      if (!res.ok) return;
      const ids = await res.json();
      if (!Array.isArray(ids)) return;
      savedMessageIds.clear();
      for (const id of ids) savedMessageIds.add(String(id));
      // The rows on screen were drawn before the answer arrived; repaint their toolbars.
      repaintActions();
    } catch (_) {
      // A failed lookup leaves every bookmark drawn as "not saved". Clicking one still saves,
      // which is idempotent server-side, so the worst case is a wrong-looking icon.
    }
  };

  async function toggleSave(id, save) {
    const res = await fetch('/api/saved/conversation-messages/' + encodeURIComponent(id), {
      method: save ? 'PUT' : 'DELETE', headers: headers(),
    });
    if (!res.ok && res.status !== 204) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Could not update your saved messages: ' + (err.message || err.error || res.statusText));
      return;
    }
    if (save) savedMessageIds.add(String(id)); else savedMessageIds.delete(String(id));
    // Repaint every copy — the feed row and, for a thread parent, the panel's twin.
    repaintActions(id);
  }

  const buildActions = (li) => {
    const authorUsername = li.dataset.author;
    const hasBody = !!(li.dataset.bodyMarkdown && li.dataset.bodyMarkdown.length > 0);
    const isMine = authorUsername === myUsername;
    const canDelete = isMine || isAdmin;
    const isThreadReply = !!li.dataset.parentId;
    const actions = document.createElement('div');
    actions.className = 'message-actions';
    // Sprite icons, matching the channel view — see the note in chat/index.js buildActions.
    const action = (name, icon, title) =>
        '<button type="button" class="msg-action" data-action="' + name + '" title="' + title + '"'
        + ' aria-label="' + title + '"><svg class="icon" aria-hidden="true"><use href="#icon-'
        + icon + '"/></svg></button>';
    let html = '';
    // React on every message including your own, as the channel row does — a ✅ on your own
    // announcement is a normal thing to want, and the server has allowed it since this session.
    html += action('react', 'face-smile', 'Add reaction');
    // A reply may not be replied to: threads are one level deep, which is what the server enforces
    // and what makes the panel a flat list. Offering the button anyway would be a guaranteed 400.
    if (!isThreadReply) html += action('reply', 'reply', 'Reply in thread');
    // Saving is a private note to yourself that leaves no trace in the conversation, so anyone who
    // can read the message can keep it. Inline rather than behind an overflow for the reason the
    // channel row keeps it inline: one click is the whole point of a to-do queue.
    html += savedMessageIds.has(String(li.dataset.id))
        ? action('unsave', 'bookmark-filled', 'Remove from saved')
        : action('save', 'bookmark', 'Save for later');
    if (isMine && hasBody) html += action('edit', 'pencil', 'Edit');
    if (canDelete) html += action('delete', 'trash', 'Delete');
    actions.innerHTML = html;
    return actions;
  };
  const attachActions = (li) => {
    if (!li || li.querySelector('.message-actions')) return;
    const actions = buildActions(li);
    if (actions.children.length > 0) li.appendChild(actions);
  };
  // Wire actions for server-rendered messages on initial load, then ask which of them are saved
  // and repaint. Drawing first and correcting means the row is usable before the round trip.
  messagesEl?.querySelectorAll('li.message').forEach(attachActions);
  loadSavedIds();

  // Feed first, then the thread panel: an older reply lives only in the panel's <ol>, so a
  // reaction or edit broadcast for it has nowhere else to land.
  const findMessageEl = (id) => {
    const sel = 'li.message[data-id="' + CSS.escape(String(id)) + '"]';
    return (messagesEl && messagesEl.querySelector(sel))
        || document.querySelector('#thread-replies ' + sel)
        || document.querySelector('#thread-parent ' + sel);
  };

  /** Move the "N replies" widget on a parent that is on screen. Silent when it isn't. */
  const bumpThreadIndicator = (parentId, delta) => {
    const li = findMessageEl(parentId);
    const right = li?.querySelector(':scope > div');
    applyThreadIndicator(right, delta);
  };

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
    right.querySelectorAll('.message-body, .link-preview, .message-reactions, .message-attachments, .message-edit, .edited-tag, .thread-indicator').forEach(n => n.remove());
    const meta = right.querySelector('.message-meta');
    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml || '';
      meta.after(body);
      const preview = window.ChatKit.buildLinkPreviewEl(msg.linkPreview);
      if (preview) body.after(preview);
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
    // Re-added last so it keeps its place at the bottom of the message. It was stripped above with
    // the rest of the content column rather than preserved: leaving it in place would put the
    // freshly appended trays underneath it, which is not where they go.
    if (msg.replyCount > 0) {
      right.appendChild(window.ChatKit.buildThreadIndicator(msg.replyCount));
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

  // Click delegation for reactions / edit / delete / react / reply. Bound at the document rather
  // than on #messages because the same rows exist inside the thread panel, which is a sibling of
  // the feed — a listener on the feed alone would leave every action in the panel inert.
  document.addEventListener('click', (e) => {
    if (!(e.target instanceof Element)) return;
    const indicator = e.target.closest('.thread-indicator');
    if (indicator) {
      const li = indicator.closest('li.message');
      if (li) threadPanel?.open(li.dataset.id);
      return;
    }
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
    else if (btn.dataset.action === 'reply') threadPanel?.open(id);
    else if (btn.dataset.action === 'save') toggleSave(id, true);
    else if (btn.dataset.action === 'unsave') toggleSave(id, false);
    else if (btn.dataset.action === 'react') {
      openEmojiPicker(btn, (emoji) => toggleReaction(id, emoji, false));
    }
  });

  // ---------- Thread panel ----------
  // The panel itself is ChatKit.createThreadPanel; what is local to this page is where a thread
  // comes from and how one of its messages is drawn.
  const renderThreadMessage = (msg, isParent) => {
    const li = document.createElement('li');
    li.className = 'message thread-message' + (isParent ? ' thread-parent-msg' : '');
    li.dataset.id = msg.id;
    li.dataset.author = msg.authorUsername;
    li.dataset.bodyMarkdown = msg.bodyMarkdown || '';
    if (msg.parentId) li.dataset.parentId = msg.parentId;
    const name = msg.authorDisplayName || msg.authorUsername;
    const created = new Date(msg.createdAt);
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
    meta.appendChild(author);
    appendAuthorHandle(meta, msg.authorDisplayName, msg.authorUsername);
    const time = document.createElement('time');
    time.textContent = formatTime(created);
    meta.appendChild(time);
    if (msg.editedAt) {
      const tag = document.createElement('span');
      tag.className = 'edited-tag';
      tag.textContent = '(edited)';
      meta.appendChild(tag);
    }
    right.appendChild(meta);
    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml || '';
      right.appendChild(body);
    }
    const preview = window.ChatKit.buildLinkPreviewEl(msg.linkPreview);
    if (preview) right.appendChild(preview);
    if (msg.reactions && msg.reactions.length) right.appendChild(renderReactionTray(msg.reactions));
    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }
    li.append(avatar, right);
    attachActions(li);
    return li;
  };

  const threadPanel = createThreadPanel({
    ids: {},
    headers,
    loadThread: async (parentId) => {
      const res = await fetch('/api/conversations/messages/' + encodeURIComponent(parentId) + '/thread',
          { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
      if (!res.ok) throw new Error(res.statusText);
      return res.json();
    },
    postReply: async (parentId, body) => {
      const res = await fetch('/api/conversations/messages/' + encodeURIComponent(parentId) + '/replies', {
        method: 'POST', headers: headers(), body: JSON.stringify({ body }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        throw new Error(err.message || err.error || res.statusText);
      }
      return res.json().catch(() => null);
    },
    renderMessage: renderThreadMessage,
  });

  // ---------- Attachment rendering ----------
  // Mirrors chat.js's renderAttachmentTray + buildAttachmentLink for DMs. Image attachments
  // open an in-page lightbox via the document-level delegate that ships in chat.js — but
  // chat.js isn't loaded here, so wire a minimal local delegate further below.
  function buildAttachmentLink(a) {
    // Tombstone: the file was deleted from the file manager, the message stayed.
    if (a.deletedAt) return window.ChatKit.buildRemovedAttachmentEl(a);
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
  // The same in-page lightbox the channel page uses. This was a window.open to a new browser
  // tab — the "minimal" version — which is why image attachments felt different in a DM.
  window.ChatKit.wireImageLightbox();

  // ---------- Typing indicator ----------
  // Receiving and sending halves both come from ChatKit; what is local is the destination and the
  // decision to stop pinging once the composer is empty. An empty composer is not "typing" — a
  // ping after the last character was deleted would leave the other person watching a phantom for
  // the tracker's whole four-second grace.
  const typing = window.ChatKit.createTypingTracker(document.getElementById('typing-indicator'));
  const publishTyping = window.ChatKit.throttledPing(() => {
    if (!stomp.connected) return;
    stomp.publish({ destination: '/app/conversations/' + conversationId + '/typing', body: '{}' });
  });
  input.addEventListener('input', () => {
    if (input.value.trim().length > 0) publishTyping();
  });

  // ---------- STOMP connection ----------
  const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
  // Presence.stompOptions() is not optional: it is the worker-driven heartbeat that keeps this
  // socket alive in a background tab, and the idle-ms header that keeps a reconnect from reading
  // as activity. Same shape as chat/index.js; keep the two identical.
  const stomp = new StompJs.Client(Object.assign({
    brokerURL: wsUrl,
    reconnectDelay: 4000,
  }, window.Presence ? window.Presence.stompOptions() : {}));

  // The conversation topic carries ConversationMessageDto (new message) and lightweight
  // ConversationEvent envelopes (member-added, message-updated, message-deleted, link-preview).
  // Discriminate by the `type` field that only ConversationEvent carries.
  // ---------- Read state ----------
  // The marker advances on live traffic, but only while the tab is actually in the foreground. A
  // conversation left open in a background tab must NOT silently mark incoming messages read: that
  // would wipe the sidebar badge and the toast for messages nobody looked at. Same rule the channel
  // page follows, and the reason the refocus catch-up below exists.
  const markRead = () => {
    fetch('/api/conversations/' + conversationId + '/read',
        { method: 'POST', headers: headers() }).catch(() => {});
  };
  const isForeground = () => document.visibilityState === 'visible' && document.hasFocus();
  const catchUpRead = () => { if (isForeground()) markRead(); };
  document.addEventListener('visibilitychange', catchUpRead);
  window.addEventListener('focus', catchUpRead);

  /**
   * Move one sidebar DM row's unread badge. The count lives in `data-unread` rather than being read
   * back out of the badge's own text — the badge renders "99+" past ninety-nine, and parsing that
   * back gives 99 and then 100, which is a number that only ever gets more wrong.
   */
  // Shared with the channel page via window.ChatKit, not reimplemented here. The local copy this
  // replaces had two faults the shared one does not: it bailed out for a conversation with no
  // sidebar row, so the first message from somebody you had never spoken to was invisible, and it
  // counted from a `data-unread` attribute the server does not render — so the first live message
  // overwrote a server-rendered "3" with "1" instead of making it "4".
  const bumpSidebarUnread = (alert) => window.ChatKit?.bumpConversationUnread(alert);

  function handleFrame(payload) {
    if (payload && (payload.type === 'member-added' || payload.type === 'member-left')) {
      if (typeof window.__refreshGroupMembers === 'function') window.__refreshGroupMembers();
      return;
    }
    if (payload && payload.type === 'message-updated') {
      if (payload.message) replaceMessageDom(payload.message);
      return;
    }
    if (payload && payload.type === 'link-preview') {
      // The card for a message that contained a link, a moment after the message. Every copy on
      // screen — the feed's and, if it is open on that message, the thread panel's.
      const sel = 'li.message[data-id="' + CSS.escape(payload.messageId) + '"]';
      document.querySelectorAll(sel).forEach((li) => window.ChatKit.applyLinkPreview(li, payload.linkPreview));
      return;
    }
    if (payload && payload.type === 'message-deleted') {
      if (payload.messageId) removeMessageDom(payload.messageId);
      // A deleted reply takes one off its parent's count. A deleted parent takes its whole thread
      // with it, and the panel showing that thread has to close rather than sit there displaying
      // a conversation that no longer exists.
      if (payload.parentId) bumpThreadIndicator(payload.parentId, -1);
      else if (String(threadPanel?.openId()) === String(payload.messageId)) threadPanel.close();
      return;
    }
    appendMessage(payload);
    // A message the viewer is watching arrive is a message they have read — including a thread
    // reply, which counts toward this conversation's unread the same way a channel's does. Their
    // own is skipped: posting is not reading, and in a solo conversation the only thing that
    // writes without them is a fired reminder, which they should still find marked.
    if (payload && payload.id && payload.authorUsername !== myUsername && isForeground()) {
      markRead();
    }
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
    // Typing pings. Own pings are filtered here rather than not sent, because the server has to
    // broadcast to the whole conversation either way and a self-conversation would otherwise watch
    // itself type.
    stomp.subscribe('/topic/conversations/' + conversationId + '/typing', (frame) => {
      try {
        const t = JSON.parse(frame.body);
        if (t.username && t.username !== myUsername) {
          typing.note(t.username, t.displayName || t.username);
        }
      } catch (e) { /* ignore malformed frame */ }
    });
    // Messages in the user's OTHER conversations. The one on screen is handled by the topic
    // subscription above, so it is filtered out here — and so is the case where this tab is
    // showing that conversation and focused, because a notification for something the user is
    // visibly reading is the fastest way to make them turn notifications off.
    stomp.subscribe('/user/queue/conversation-alerts', (frame) => {
      try {
        const a = JSON.parse(frame.body);
        const isCurrent = String(a.conversationId) === String(conversationId);
        // The sidebar badge for a DM was server-rendered and then never moved, so a message
        // arriving in another conversation left the row saying whatever it said at page load until
        // the next navigation. It is bumped here for the same reason the channel page bumps its
        // own: an unread count that is only true immediately after a reload is not a count.
        if (!isCurrent) bumpSidebarUnread(a);
        if (!window.MentionNotifications) return;
        if (isCurrent && document.visibilityState === 'visible' && document.hasFocus()) return;
        window.MentionNotifications.show({
          author: a.author,
          channel: a.title,
          kind: a.type === 'DIRECT' ? 'direct' : 'group',
          snippet: a.preview,
          url: '/conversations/' + a.conversationId,
        });
      } catch (e) { /* ignore malformed */ }
    });
    if (window.Presence) window.Presence.attachStomp(stomp);
    // Incoming calls and the signalling for one already in progress. calls.js no-ops when the
    // panel is absent, which is what a deployment with no TURN server configured looks like.
    if (window.Calls) window.Calls.attachStomp(stomp);
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
        try {
          // Raw-body upload — the File is the request body, streamed straight through to disk.
          // See the channel uploader in chat/index.js for why this isn't multipart.
          const headersOut = {
            'Content-Type': f.type || 'application/octet-stream',
            'X-Upload-Filename': encodeURIComponent(f.name),
          };
          if (csrfToken && csrfHeader) headersOut[csrfHeader] = csrfToken;
          const res = await fetch('/api/conversations/' + conversationId + '/attachments', {
            method: 'POST',
            headers: headersOut,
            body: f,
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

  // ---------- Notification level ----------
  // Same control the channel page has, against the same account-wide default: the row stores
  // DEFAULT ("follow the account default") rather than a copy of what it resolved to, so changing
  // the account setting moves every conversation the user has not explicitly overridden.
  (() => {
    const toggle = document.getElementById('conversation-settings-toggle');
    const panel = document.getElementById('conversation-settings-panel');
    const closeBtn = document.getElementById('conversation-settings-close');
    const select = document.getElementById('conversation-notify-level');
    const status = document.getElementById('conversation-notify-status');
    if (!toggle || !panel) return;

    const setOpen = (open) => {
      panel.hidden = !open;
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      // Both header panels are .channel-admin-dropdown, and both anchor to `right: 1rem` — two
      // open at once is two panels in the same place. Closing the other one here rather than
      // giving this one its own offset: they are alternatives, not a pair to compare.
      if (open) window.__closeGroupMembersPanel?.();
    };
    toggle.addEventListener('click', (e) => { e.stopPropagation(); setOpen(panel.hidden); });
    closeBtn?.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && !panel.hidden) setOpen(false);
    });

    select?.addEventListener('change', async () => {
      const level = select.value;
      const previous = select.dataset.current || 'DEFAULT';
      try {
        const res = await fetch('/api/conversations/' + conversationId + '/notify', {
          method: 'PUT', headers: headers(), body: JSON.stringify({ level }),
        });
        if (!res.ok) throw new Error(res.statusText);
        const dto = await res.json();
        // Repaint from what the server stored, not from what we asked for — the two differ if
        // anything ever normalises the value, and a picker showing the request rather than the
        // result is a picker that can lie.
        select.value = dto.level;
        select.dataset.current = dto.level;
        applyMuteCue(dto.level);
        if (status) {
          status.hidden = false;
          status.textContent = 'Saved.';
          setTimeout(() => { status.hidden = true; }, 2000);
        }
      } catch (e) {
        select.value = previous;
        if (status) {
          status.hidden = false;
          status.textContent = 'Could not save that setting.';
        }
      }
    });

    // The sidebar row and the header bell both show mute state; both move with the picker rather
    // than waiting for a reload, since the whole point of the setting is felt immediately.
    const applyMuteCue = (rawLevel) => {
      const resolved = rawLevel === 'DEFAULT' ? accountNotifyDefault : rawLevel;
      const li = document.querySelector('#sidebar-dm-list li[data-conv-id="'
          + CSS.escape(String(conversationId)) + '"]');
      if (li) {
        li.dataset.notifyLevel = rawLevel;
        li.dataset.muted = String(resolved === 'NONE');
      }
      toggle.querySelector('use')?.setAttribute('href',
          resolved === 'NONE' ? '#icon-bell-slash' : '#icon-bell');
    };
    applyMuteCue(select?.dataset.current || 'DEFAULT');
  })();

  // ---------- Leave the group ----------
  // Two-step, mirroring the channel leave. The server revokes the socket subscription too — it has
  // to, since the broker authorises SUBSCRIBE once and never re-checks — but this page must not be
  // relying on that to stop showing a conversation it just left.
  (() => {
    const trigger = document.getElementById('conversation-leave-btn');
    const panel = document.getElementById('conversation-leave-confirm');
    const cancel = document.getElementById('conversation-leave-cancel');
    const go = document.getElementById('conversation-leave-go');
    if (!trigger || !panel || !go) return;

    trigger.addEventListener('click', () => {
      panel.hidden = false;
      trigger.hidden = true;
      go.focus();
    });
    cancel?.addEventListener('click', () => {
      panel.hidden = true;
      trigger.hidden = false;
      trigger.focus();
    });

    go.addEventListener('click', async () => {
      go.disabled = true;
      go.textContent = 'Leaving…';
      try {
        const res = await fetch('/api/conversations/' + conversationId + '/leave', {
          method: 'POST', headers: headers(),
        });
        if (!res.ok && res.status !== 204) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.message || err.error || res.statusText);
        }
        // This page is no longer ours to be on — it would render as a 403 on the next load.
        window.location.href = '/channels';
      } catch (e) {
        go.disabled = false;
        go.textContent = 'Leave group';
        alert('Could not leave: ' + (e?.message || e));
      }
    });
  })();

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

  // The "New message" popover beside the Direct messages header wires itself in chat-kit.js —
  // this page and the channel page carry identical markup, and this was the second copy.

  // Auto-scroll to the bottom on first paint. Re-runs after a tick and once images
  // load, since avatars / inline images can grow content height *after* the initial
  // scroll, leaving the viewport short of the latest message.
  const scrollToBottom = () => {
    if (!messagesEl) return;
    messagesEl.scrollTop = messagesEl.scrollHeight;
  };
  if (messagesEl) {
    // The "new messages" line goes in before the scroll, so the scroll accounts for its height.
    // Drawn once, from the marker as it stood when the page was requested, and then never moved:
    // a divider that chased the marker would slide down the screen as you read and mark nothing.
    window.ChatKit.applyUnreadDivider(messagesEl, lastReadAt, {
      me: myUsername,
      // In a conversation you are the only member of, your own messages are the only messages —
      // and a fired /remind me is one of them. Excluding them there would mean the line could
      // never appear, which is the one place it is most useful.
      countOwn: isSoloConversation,
    });
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
        label.className = 'member-name';
        label.textContent = name;
        const handle = document.createElement('small');
        handle.className = 'member-handle';
        handle.textContent = '@' + m.username;
        // Same column contract as the channel members panel — see chat/index.js.
        const meta = document.createElement('span');
        meta.className = 'member-meta';
        li.append(av, label, handle, meta);
        if (m.admin) {
          const badge = document.createElement('small');
          badge.className = 'dm-admin-tag';
          badge.title = 'Workspace administrator';
          badge.textContent = 'admin';
          meta.appendChild(badge);
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
      // See the note on the settings panel: the two header dropdowns share an anchor point.
      if (open) document.getElementById('conversation-settings-close')?.click();
    };
    // Exposed so the settings panel can close this one when it opens. One tiny window-level surface,
    // the same trick __refreshGroupMembers already uses, rather than hoisting both panels into a
    // shared scope they otherwise have no reason to share.
    window.__closeGroupMembersPanel = () => setOpen(false);
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
