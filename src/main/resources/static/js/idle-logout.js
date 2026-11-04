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
 * Idle logout. After IDLE_TIMEOUT_MS without any user input the browser fires
 * POST /logout (Spring Security clears the session) and says so.
 *
 * The authoritative session lifetime is Keycloak's SSO Session Idle (Realm
 * settings → Sessions). This file just makes the logout proactive instead of
 * "next click discovers the session is dead". Spring's server.servlet.session.timeout
 * matches the same 8h default.
 *
 * It used to navigate straight to "/" afterwards, which left a person who came back from lunch
 * on the landing page with no idea why. Now the page stays where it is and a dialog explains what
 * happened; its one button reloads, which bounces through Keycloak and — because SecurityConfig's
 * request cache saved this page — comes back here. The dialog is up *before* the logout request
 * is sent, so a slow or failing request cannot leave the user looking at a page that still seems
 * alive. The chat:signed-out event it fires is what makes the page's socket stop redialling.
 *
 * Throttled — input events fire hundreds of times per second on a moving mouse,
 * so we re-arm the timer at most once per RESET_THROTTLE_MS.
 */
(function () {
  const IDLE_TIMEOUT_MS = 8 * 60 * 60 * 1000;   // 8 hours
  const RESET_THROTTLE_MS = 30 * 1000;          // re-arm at most once / 30s

  const meta = (n) => document.querySelector(`meta[name="${n}"]`)?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');

  let timer = null;
  let lastReset = 0;
  let signedOut = false;
  let loggedOut = Promise.resolve();

  const post = async () => {
    const headers = {};
    if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
    try {
      await fetch('/logout', { method: 'POST', headers, credentials: 'same-origin' });
    } catch (e) {
      // Network error — the dialog is already up; the reload below will find out the truth.
    }
  };

  // Built here, not rendered by Thymeleaf: this runs on every page that has a session, and the
  // same shell (poll-modal-*) keeps it looking like the app's other dialogs.
  const showDialog = () => {
    const backdrop = document.createElement('div');
    backdrop.className = 'poll-modal-backdrop idle-modal-backdrop';

    const dialog = document.createElement('div');
    dialog.className = 'poll-modal idle-modal';
    dialog.setAttribute('role', 'alertdialog');
    dialog.setAttribute('aria-modal', 'true');
    dialog.setAttribute('aria-labelledby', 'idle-modal-title');
    dialog.setAttribute('aria-describedby', 'idle-modal-text');

    const head = document.createElement('header');
    head.className = 'poll-modal-head';
    const title = document.createElement('h2');
    title.id = 'idle-modal-title';
    title.textContent = 'You have been signed out';
    head.append(title);

    const body = document.createElement('div');
    body.className = 'poll-modal-body';
    const text = document.createElement('p');
    text.id = 'idle-modal-text';
    text.className = 'idle-modal-text';
    text.textContent = 'You were inactive for a long time, so we signed you out to protect your '
        + 'account. New messages have stopped arriving. Sign in again to carry on where you left off.';
    const actions = document.createElement('div');
    actions.className = 'poll-modal-actions';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'poll-modal-submit idle-modal-signin';
    button.textContent = 'Sign in';
    button.addEventListener('click', async () => {
      button.disabled = true;
      await loggedOut;
      window.location.reload();
    });
    actions.append(button);
    body.append(text, actions);

    dialog.append(head, body);
    backdrop.append(dialog);

    // Nothing behind the dialog is usable any more, so take it out of reach of the keyboard and
    // assistive tech as well as the pointer.
    Array.from(document.body.children).forEach((el) => el.setAttribute('inert', ''));
    document.body.appendChild(backdrop);
    // The one thing there is to do; keep Tab from leaving it.
    button.focus();
    backdrop.addEventListener('keydown', (e) => { if (e.key === 'Tab') e.preventDefault(); });
  };

  const logout = () => {
    if (signedOut) return;
    signedOut = true;
    if (timer) clearTimeout(timer);
    timer = null;
    showDialog();
    window.dispatchEvent(new CustomEvent('chat:signed-out', { detail: { reason: 'idle' } }));
    loggedOut = post();
  };

  const arm = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(logout, IDLE_TIMEOUT_MS);
  };

  const reset = () => {
    if (signedOut) return;
    const now = Date.now();
    if (now - lastReset < RESET_THROTTLE_MS) return;
    lastReset = now;
    arm();
  };

  ['mousemove', 'keydown', 'scroll', 'touchstart', 'focus'].forEach((evt) => {
    window.addEventListener(evt, reset, { passive: true, capture: true });
  });

  // Prime the timer so a tab left open with zero subsequent input still expires.
  arm();
})();
