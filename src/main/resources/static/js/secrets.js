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
 * /secrets (secrets.html): seal a secret into a one-time link, and see what became of the ones
 * already shared.
 *
 * The plaintext never leaves this page unencrypted: SecretCrypto.seal runs here, and what is POSTed
 * is ciphertext plus a verifier. The key goes into the link this page shows and nowhere else — not
 * to the server, not to storage — so the link cannot be shown again after the page moves on, and
 * the page says so.
 *
 * A classic script like saved.js, and like it built entirely with createElement + textContent:
 * labels, handles and browser summaries are all someone's text.
 *
 * Promise chains rather than async/await: an await is a point where the function stops and the
 * browser can recalculate style and layout, so DOM work spread over several awaits is several
 * passes. Each .then() below ends with one synchronous block that does all of that step's DOM work
 * at once.
 */
(function () {
  const form = document.getElementById('secret-form');
  if (!form) return;

  const textEl = document.getElementById('secret-text');
  const labelEl = document.getElementById('secret-label');
  const lifetimeEl = document.getElementById('secret-lifetime');
  const anyoneEl = document.getElementById('secret-anyone');
  const separateEl = document.getElementById('secret-separate-key');
  const sizeEl = document.getElementById('secret-size');
  const errorEl = document.getElementById('secret-error');
  const submitBtn = document.getElementById('secret-submit');
  const resultEl = document.getElementById('secret-result');
  const linkEl = document.getElementById('secret-link');
  const keyRow = document.getElementById('secret-key-row');
  const keyEl = document.getElementById('secret-key');
  const listEl = document.getElementById('secret-list');
  const listErrorEl = document.getElementById('secret-list-error');
  const moreBtn = document.getElementById('secret-more');

  const maxBytes = Number(form.dataset.maxBytes) || 16384;

  const meta = (name) => document.querySelector('meta[name="' + name + '"]')?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');
  const headers = () => {
    const h = { 'Content-Type': 'application/json' };
    if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
    return h;
  };

  const showError = (el, msg) => {
    if (!el) return;
    el.textContent = msg || '';
    el.hidden = !msg;
  };

  /** The server's own wording when it sent one, else `fallback`. */
  const messageFrom = (res, fallback) => res.json().then(
      (body) => (body && typeof body.message === 'string' && body.message) ? body.message : fallback,
      () => fallback);

  if (!SecretCrypto.available()) {
    document.getElementById('secret-unsupported').hidden = false;
    submitBtn.disabled = true;
  }

  // ---------- Size meter ----------

  const updateSize = () => {
    const bytes = SecretCrypto.byteLength(textEl.value);
    const over = bytes > maxBytes;
    sizeEl.textContent = bytes === 0 ? '' : (over
        ? 'Too long: ' + bytes.toLocaleString() + ' of ' + maxBytes.toLocaleString() + ' bytes'
        : bytes.toLocaleString() + ' of ' + maxBytes.toLocaleString() + ' bytes');
    sizeEl.classList.toggle('is-over', over);
  };
  textEl.addEventListener('input', updateSize);

  // ---------- Create ----------

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    showError(errorEl, '');
    const plaintext = textEl.value;
    if (!plaintext.trim()) {
      showError(errorEl, 'Type or paste the secret to share.');
      textEl.focus();
      return;
    }
    if (SecretCrypto.byteLength(plaintext) > maxBytes) {
      showError(errorEl, 'That is too long to share this way — the limit is ' + maxBytes.toLocaleString() + ' bytes.');
      return;
    }
    submitBtn.disabled = true;
    const separate = separateEl.checked;

    SecretCrypto.seal(plaintext)
        .then((sealed) => fetch('/api/secrets', {
          method: 'POST',
          headers: headers(),
          cache: 'no-store',
          body: JSON.stringify({
            payload: sealed.payload,
            verifier: sealed.verifier,
            label: labelEl.value,
            lifetimeSeconds: Number(lifetimeEl.value),
            audience: anyoneEl && anyoneEl.checked ? 'ANYONE' : 'SIGNED_IN',
          }),
        }).then((res) => {
          if (!res.ok) {
            return messageFrom(res, 'The secret could not be shared (' + res.status + ').')
                .then((msg) => { showError(errorEl, msg); });
          }
          return res.json().then((created) => {
            // One synchronous block: the whole panel swap costs a single style and layout pass.
            const link = location.origin + '/s/' + created.publicId;
            linkEl.value = separate ? link : link + '#' + sealed.key;
            keyEl.value = separate ? sealed.key : '';
            keyRow.hidden = !separate;
            // The plaintext has served its purpose; nothing on the page should still hold it,
            // including a field Firefox would restore on back or reload.
            textEl.value = '';
            labelEl.value = '';
            updateSize();
            form.hidden = true;
            resultEl.hidden = false;
            linkEl.focus();
            linkEl.select();
            loadList(true);
          });
        }))
        .catch(() => { showError(errorEl, 'The secret could not be encrypted in this browser.'); })
        .then(() => { submitBtn.disabled = !SecretCrypto.available(); });
  });

  const copyFrom = (input, button, doneLabel) => {
    const original = button.textContent;
    const done = () => {
      button.textContent = doneLabel;
      setTimeout(() => { button.textContent = original; }, 2000);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(input.value).then(done, () => { input.select(); });
    } else {
      input.select();
    }
  };
  document.getElementById('secret-copy-link').addEventListener('click', (e) => copyFrom(linkEl, e.currentTarget, 'Copied'));
  document.getElementById('secret-copy-key').addEventListener('click', (e) => copyFrom(keyEl, e.currentTarget, 'Copied'));

  document.getElementById('secret-another').addEventListener('click', () => {
    linkEl.value = '';
    keyEl.value = '';
    resultEl.hidden = true;
    form.hidden = false;
    textEl.focus();
  });

  // ---------- Your secrets ----------

  const STATE_LABELS = { WAITING: 'Waiting', OPENED: 'Opened', EXPIRED: 'Expired', REVOKED: 'Revoked' };

  const when = (iso) => (iso ? ChatTime.formatDateTime(iso) : '');

  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };

  /* "Opened 14:02 by @bob", "Opened 14:02 · no account · Firefox on Windows · 203.0.113.7", … */
  const describe = (s) => {
    const line = el('span', 'secrets-detail');
    switch (s.state) {
      case 'WAITING':
        line.textContent = 'Expires ' + when(s.expiresAt);
        break;
      case 'OPENED':
        if (s.openedAnonymously) {
          line.append('Opened ' + when(s.openedAt) + ' · no account');
          if (s.openedClient) line.append(' · ' + s.openedClient);
          if (s.openedIp) {
            line.append(' · ');
            const ip = el('span', 'secrets-ip', s.openedIp);
            ip.title = 'IP address as reported by the proxy';
            line.append(ip);
          }
        } else {
          line.textContent = 'Opened ' + when(s.openedAt) + ' by @' + (s.openedByName || 'a deleted account');
        }
        break;
      case 'EXPIRED':
        line.textContent = 'Expired ' + when(s.expiresAt) + ' without being opened';
        break;
      case 'REVOKED':
        line.textContent = 'Revoked ' + when(s.revokedAt);
        break;
      default:
        line.textContent = '';
    }
    return line;
  };

  const renderRow = (s) => {
    const li = el('li', 'secrets-item');
    li.dataset.id = s.publicId;

    const head = el('div', 'secrets-item-head');
    head.append(el('span', 'secrets-label', s.label || 'Untitled secret'));
    head.append(el('span', 'secrets-state secrets-state-' + String(s.state).toLowerCase(), STATE_LABELS[s.state] || s.state));
    if (s.audience === 'ANYONE') head.append(el('span', 'secrets-chip', 'Anyone with the link'));
    li.append(head);

    const info = el('div', 'secrets-item-meta');
    info.append(el('span', 'secrets-created', 'Created ' + when(s.createdAt)));
    info.append(describe(s));
    li.append(info);

    if (s.state === 'WAITING') {
      const actions = el('div', 'secrets-item-actions');
      const revoke = el('button', 'link-btn secrets-revoke', 'Revoke');
      revoke.type = 'button';
      let armed = false;
      let disarm = null;
      revoke.addEventListener('click', () => {
        // Two steps, inline: a revoke cannot be taken back, and a confirm() dialog is what the rest
        // of the app has moved away from.
        if (!armed) {
          armed = true;
          revoke.textContent = 'Click again to revoke';
          revoke.classList.add('is-armed');
          disarm = setTimeout(() => {
            armed = false;
            revoke.textContent = 'Revoke';
            revoke.classList.remove('is-armed');
          }, 4000);
          return;
        }
        clearTimeout(disarm);
        revoke.disabled = true;
        fetch('/api/secrets/' + encodeURIComponent(s.publicId), {
          method: 'DELETE', headers: headers(), cache: 'no-store',
        }).then((res) => {
          if (!res.ok) {
            return messageFrom(res, 'Could not revoke that secret (' + res.status + ').')
                .then((msg) => {
                  showError(listErrorEl, msg);
                  revoke.disabled = false;
                });
          }
          return res.json().then((view) => { li.replaceWith(renderRow(view)); });
        }).catch(() => {
          showError(listErrorEl, 'Could not reach the server.');
          revoke.disabled = false;
        });
      });
      actions.append(revoke);
      li.append(actions);
    }
    return li;
  };

  let page = 0;

  const loadList = (reset) => {
    if (reset) page = 0;
    showError(listErrorEl, '');
    fetch('/api/secrets?page=' + page, { headers: headers(), cache: 'no-store' })
        .then((res) => {
          if (!res.ok) {
            showError(listErrorEl, 'Could not load your secrets (' + res.status + ').');
            return null;
          }
          return res.json();
        })
        .then((data) => {
          if (!data) return;
          // Built off-document and attached once: one insertion, one style and layout pass, however
          // many rows came back.
          const rows = document.createDocumentFragment();
          if (reset && data.items.length === 0) {
            rows.append(el('li', 'files-empty', 'You have not shared any secrets yet.'));
          }
          data.items.forEach((row) => rows.append(renderRow(row)));
          if (reset) listEl.replaceChildren(rows);
          else listEl.append(rows);
          moreBtn.hidden = !data.hasMore;
        })
        .catch(() => { showError(listErrorEl, 'Could not reach the server.'); });
  };

  moreBtn.addEventListener('click', () => {
    page += 1;
    loadList(false);
  });

  loadList(true);
})();
