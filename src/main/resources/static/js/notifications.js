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
 * Mention notifications: in-tab toast stack + (when permitted) OS notifications via the
 * Notification API. The toast always shows so the user gets feedback even when permission
 * is denied or unavailable; OS notification is opportunistic and additive.
 *
 * Permission strategy: don't prompt up-front. Wait until the first mention arrives, then
 * include an "Enable desktop alerts" button on the toast. Subsequent toasts skip the prompt.
 *
 * Public surface: window.MentionNotifications = { show({ author, channel, snippet, url }) }.
 */
(function () {
  const TOAST_TIMEOUT_MS = 8000;
  let stack = null;
  let askedThisSession = false;

  function ensureStack() {
    if (stack) return stack;
    stack = document.createElement('div');
    stack.className = 'notification-stack';
    document.body.appendChild(stack);
    return stack;
  }

  function permissionState() {
    if (typeof Notification === 'undefined') return 'unsupported';
    return Notification.permission; // 'granted' | 'denied' | 'default'
  }

  function fireOsNotification({ author, channel, snippet, url }) {
    if (permissionState() !== 'granted') return null;
    try {
      const n = new Notification(author + ' in #' + channel, {
        body: snippet || '',
        tag: 'mention:' + url,        // collapses repeated mentions to the same message
        renotify: false,
      });
      n.onclick = () => {
        try { window.focus(); } catch (e) {}
        if (url) window.location.href = url;
        n.close();
      };
      return n;
    } catch (e) {
      return null; // Some browsers throw inside iframes / private modes
    }
  }

  function buildToast({ author, channel, snippet, url }) {
    const li = document.createElement('div');
    li.className = 'notification-toast';
    li.setAttribute('role', 'status');

    const head = document.createElement('div');
    head.className = 'notification-toast-head';
    const headTitle = document.createElement('span');
    headTitle.className = 'notification-toast-title';
    headTitle.textContent = author + ' mentioned you in #' + channel;
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'notification-toast-close';
    close.setAttribute('aria-label', 'Dismiss');
    close.textContent = '×';
    head.append(headTitle, close);

    const body = document.createElement('div');
    body.className = 'notification-toast-body';
    body.textContent = snippet || '';

    li.append(head, body);

    // Inline "Enable desktop alerts" CTA — only on first toast of the session AND only when
    // the browser has neither granted nor blocked us yet.
    if (!askedThisSession && permissionState() === 'default') {
      askedThisSession = true;
      const cta = document.createElement('button');
      cta.type = 'button';
      cta.className = 'notification-toast-cta';
      cta.textContent = 'Enable desktop alerts';
      cta.addEventListener('click', () => {
        cta.disabled = true;
        Notification.requestPermission().then((perm) => {
          if (perm === 'granted') {
            fireOsNotification({ author, channel, snippet, url });
            cta.remove();
          } else {
            cta.textContent = 'Desktop alerts blocked';
          }
        });
      });
      li.appendChild(cta);
    }

    let timer = null;
    const dismiss = () => {
      if (timer) { clearTimeout(timer); timer = null; }
      li.classList.add('leaving');
      setTimeout(() => li.remove(), 200);
    };
    close.addEventListener('click', (e) => { e.stopPropagation(); dismiss(); });
    li.addEventListener('click', () => {
      if (url) window.location.href = url;
    });
    timer = setTimeout(dismiss, TOAST_TIMEOUT_MS);
    return li;
  }

  function show(opts) {
    if (!opts || !opts.author) return;
    ensureStack().appendChild(buildToast(opts));
    fireOsNotification(opts);
  }

  window.MentionNotifications = { show, permissionState };
})();
