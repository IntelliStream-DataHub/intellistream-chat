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
 * "Forward to…" — pick a destination, add a comment, send.
 *
 * Its own module rather than another few hundred lines in index.js, and shaped like poll-modal.js
 * so the two dialogs in this app behave the same way: a backdrop that closes on click-outside and
 * Escape, a form that keeps itself open with the server's own words when the server refuses, and no
 * inline handlers anywhere (strict CSP).
 *
 * The destination list is the viewer's own channels and conversations, fetched once when the dialog
 * opens. Archived channels are filtered out: the server refuses to post to one, and offering a
 * destination that cannot work is worse than not offering it.
 *
 * The disclosure warning is the part worth reading. Forwarding out of a private channel shows the
 * message to people who are not in that channel, and the API refuses unless the request says the
 * caller means to. That flag is set here, after the warning has been rendered and the confirmation
 * ticked — it is not a security control (a script can set it), it is what stops the convenient path
 * from also being the thoughtless one.
 */

let modalEl = null;

export function closeForwardDialog() {
  if (!modalEl) return;
  document.removeEventListener('keydown', onKeydown);
  modalEl.remove();
  modalEl = null;
}

function onKeydown(e) {
  if (e.key === 'Escape') closeForwardDialog();
}

/**
 * @param opts.messageId       the message being forwarded
 * @param opts.sourceIsPrivate the message's channel is PRIVATE — show the disclosure warning and
 *                             require the confirmation the API insists on
 * @param opts.sourceName      the source channel's name, for the warning's wording
 * @param opts.headers         () => fetch headers, including CSRF
 * @param opts.onDone          (result) => void, called after a successful forward
 */
export function openForwardDialog(opts) {
  closeForwardDialog();

  modalEl = document.createElement('div');
  modalEl.className = 'poll-modal-backdrop forward-modal-backdrop';
  modalEl.innerHTML =
      '<div class="poll-modal forward-modal" role="dialog" aria-modal="true"' +
           ' aria-labelledby="forward-modal-title">' +
        '<header class="poll-modal-head">' +
          '<h2 id="forward-modal-title">Forward message</h2>' +
          '<button type="button" class="icon-btn forward-modal-close" aria-label="Close">' +
            '<svg class="icon"><use href="#icon-close"/></svg>' +
          '</button>' +
        '</header>' +
        '<form class="poll-modal-body forward-modal-body">' +
          '<div class="forward-warning" hidden>' +
            '<p class="forward-warning-text"></p>' +
            '<label class="forward-ack">' +
              '<input type="checkbox" class="forward-ack-input"/>' +
              '<span>I mean to share this outside the channel it was written in.</span>' +
            '</label>' +
          '</div>' +
          '<label class="poll-field">Send to' +
            '<input type="search" class="forward-filter" autocomplete="off"' +
                  ' placeholder="Filter channels and people…" maxlength="80"/>' +
          '</label>' +
          '<ul class="forward-targets" role="listbox" aria-label="Destination"></ul>' +
          '<label class="poll-field">Add a comment (optional)' +
            '<textarea class="forward-comment" rows="2" maxlength="2000"' +
                     ' placeholder="Why you are sending this"></textarea>' +
          '</label>' +
          '<p class="poll-modal-error forward-modal-error" hidden></p>' +
          '<div class="poll-modal-actions">' +
            '<button type="button" class="poll-modal-cancel forward-modal-cancel">Cancel</button>' +
            '<button type="submit" class="poll-modal-submit forward-modal-submit" disabled>' +
              'Forward</button>' +
          '</div>' +
        '</form>' +
      '</div>';
  document.body.appendChild(modalEl);

  const form = modalEl.querySelector('.forward-modal-body');
  const filter = modalEl.querySelector('.forward-filter');
  const listEl = modalEl.querySelector('.forward-targets');
  const commentEl = modalEl.querySelector('.forward-comment');
  const errorEl = modalEl.querySelector('.forward-modal-error');
  const submitBtn = modalEl.querySelector('.forward-modal-submit');
  const warning = modalEl.querySelector('.forward-warning');
  const ackInput = modalEl.querySelector('.forward-ack-input');

  if (opts.sourceIsPrivate) {
    warning.hidden = false;
    modalEl.querySelector('.forward-warning-text').textContent =
        'This message is from the private channel #' + (opts.sourceName || '')
        + '. Anyone who can read where you send it will be able to read it, whether or not they '
        + 'are in that channel.';
  }

  let targets = [];
  let chosen = null;

  const syncSubmit = () => {
    submitBtn.disabled = !chosen || (opts.sourceIsPrivate && !ackInput.checked);
  };
  ackInput.addEventListener('change', syncSubmit);

  const render = () => {
    const q = filter.value.trim().toLowerCase();
    listEl.textContent = '';
    const shown = targets.filter((t) => !q || t.label.toLowerCase().includes(q));
    if (!shown.length) {
      const empty = document.createElement('li');
      empty.className = 'forward-empty';
      empty.textContent = targets.length ? 'Nothing matches that.' : 'Nowhere to forward this to.';
      listEl.append(empty);
      return;
    }
    for (const t of shown.slice(0, 60)) {
      const li = document.createElement('li');
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'forward-target'
          + (chosen && chosen.key === t.key ? ' is-chosen' : '');
      btn.setAttribute('role', 'option');
      btn.setAttribute('aria-selected', chosen && chosen.key === t.key ? 'true' : 'false');
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('class', 'icon icon-sm');
      icon.setAttribute('aria-hidden', 'true');
      const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
      use.setAttribute('href', '#icon-' + t.icon);
      icon.append(use);
      const label = document.createElement('span');
      label.textContent = t.label;
      btn.append(icon, label);
      btn.addEventListener('click', () => {
        chosen = t;
        render();
        syncSubmit();
      });
      li.append(btn);
      listEl.append(li);
    }
  };

  const loadTargets = async () => {
    const [channels, conversations] = await Promise.all([
      fetch('/api/channels/mine', { headers: { 'Accept': 'application/json' },
        credentials: 'same-origin' }).then((r) => (r.ok ? r.json() : [])).catch(() => []),
      fetch('/api/conversations', { headers: { 'Accept': 'application/json' },
        credentials: 'same-origin' }).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    ]);
    targets = [];
    for (const c of Array.isArray(channels) ? channels : []) {
      // Archived channels take no writes, and the source room is not a destination.
      if (c.archived) continue;
      if (String(c.id) === String(opts.sourceChannelId)) continue;
      targets.push({
        key: 'c' + c.id,
        channelId: c.id,
        label: '#' + c.name,
        icon: c.type === 'PRIVATE' ? 'lock' : 'group',
      });
    }
    for (const c of Array.isArray(conversations) ? conversations : []) {
      targets.push({
        key: 'd' + c.id,
        conversationId: c.id,
        label: c.title || c.otherDisplayName || c.otherUsername || 'Direct message',
        icon: 'send',
      });
    }
    render();
  };

  filter.addEventListener('input', render);
  modalEl.querySelector('.forward-modal-close').addEventListener('click', closeForwardDialog);
  modalEl.querySelector('.forward-modal-cancel').addEventListener('click', closeForwardDialog);
  modalEl.addEventListener('click', (e) => { if (e.target === modalEl) closeForwardDialog(); });
  document.addEventListener('keydown', onKeydown);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!chosen) return;
    errorEl.hidden = true;
    submitBtn.disabled = true;
    try {
      const res = await fetch('/api/messages/' + encodeURIComponent(opts.messageId) + '/forward', {
        method: 'POST',
        headers: opts.headers(),
        body: JSON.stringify({
          channelId: chosen.channelId || null,
          conversationId: chosen.conversationId || null,
          comment: commentEl.value.trim() || null,
          acknowledgeDisclosure: ackInput.checked,
        }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || err.error || res.statusText);
      }
      const result = await res.json().catch(() => null);
      closeForwardDialog();
      opts.onDone?.(result, chosen);
    } catch (err) {
      // Kept open with the server's own words, so a refused forward explains itself and the
      // comment already typed survives.
      errorEl.textContent = (err && err.message) || 'Could not forward that message.';
      errorEl.hidden = false;
      syncSubmit();
    }
  });

  loadTargets();
  filter.focus();
}
