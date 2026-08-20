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
 * window.ChatSession — notices that the session has ended and says so.
 *
 * A signed-out tab does not look signed out. The sidebar is still drawn, the composer still accepts
 * text, and every request the page makes is answered with a 302 to Keycloak that fetch follows
 * transparently and resolves as a 200 full of login-page HTML. Nothing throws. The socket is dead,
 * so no new message ever arrives, and the first honest feedback the user gets is a send that
 * silently does nothing — or, worse, a page that looks current and is hours stale.
 *
 * So: ask GET /api/session, which is permitAll and can therefore answer "no", and when it does,
 * put an unmissable bar on the page with a button that reloads. Reloading is the whole fix — the
 * page bounces through Keycloak, and if the SSO session is still alive it comes straight back to
 * where the user was, because SecurityConfig's request cache saved the page they were on.
 *
 * Three things it deliberately does not do:
 *
 *   - It does not poll a hidden tab. A forgotten background tab holding a session open forever is
 *     the failure mode this kind of watchdog usually introduces, and a tab nobody is looking at
 *     does not need to know.
 *   - It does not treat a failed request as a lost session. Offline, a flaky network and a
 *     restarting server all look like this, and telling somebody they have been signed out when
 *     they have not is worse than the silence it replaces.
 *   - It does not sign anybody out. Ending the session on a timer is idle-logout.js's job; this
 *     file only reports what has already happened.
 */
window.ChatSession = (function () {
  'use strict';

  const POLL_MS = 60 * 1000;

  const meta = (name) => document.querySelector('meta[name="' + name + '"]')?.content || '';

  // Who the server rendered this page for. A session that is valid but belongs to somebody else —
  // an account switch in another tab — is just as stale as no session, and reads as "still signed
  // in" to anything that only checks a boolean.
  const pageUser = meta('me-username');

  let timer = null;
  let announced = false;

  // ---------- The bar ----------

  /**
   * Built here rather than rendered by Thymeleaf and hidden, because the server cannot render a
   * page for a session that has already expired — by the time this is needed, a fresh page load is
   * exactly the thing that is no longer possible.
   */
  const announce = (kind) => {
    if (announced) return;
    announced = true;
    stop();

    const bar = document.createElement('div');
    bar.id = 'session-lost';
    bar.className = 'session-lost';
    bar.setAttribute('role', 'alert');

    const text = document.createElement('p');
    text.className = 'session-lost-text';
    const strong = document.createElement('strong');
    if (kind === 'switched') {
      strong.textContent = 'You are signed in as someone else.';
      text.append(strong, document.createTextNode(
          ' This tab is still showing ' + (pageUser ? '@' + pageUser + '’s' : 'another account’s')
          + ' view. Reload to catch up.'));
    } else {
      strong.textContent = 'You have been signed out.';
      text.append(strong, document.createTextNode(
          ' New messages have stopped arriving and anything you send here will not go through.'
          + ' Reload to sign back in — you will come back to this page.'));
    }

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'session-lost-reload';
    button.textContent = 'Reload';
    button.addEventListener('click', () => window.location.reload());

    bar.append(text, button);
    document.body.appendChild(bar);
    // Focus the one thing there is to do. The bar is an alert, so a screen reader announces it
    // regardless; this is for everybody else who just alt-tabbed back.
    button.focus({ preventScroll: true });
  };

  // ---------- The probe ----------

  /**
   * One check. Resolves to true while the session is good, false once it is not, and null when we
   * could not tell — which is treated as "no news", never as a lost session.
   */
  const check = async () => {
    if (announced) return false;
    let status;
    try {
      const res = await fetch('/api/session', {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
        cache: 'no-store',
      });
      if (!res.ok) return null;
      status = await res.json();
    } catch (offlineOrRestarting) {
      return null;
    }

    if (!status || typeof status.authenticated !== 'boolean') return null;
    if (!status.authenticated) {
      announce('lost');
      return false;
    }
    if (pageUser && status.username && status.username !== pageUser) {
      announce('switched');
      return false;
    }
    return true;
  };

  // ---------- Scheduling ----------

  const stop = () => {
    if (timer) clearInterval(timer);
    timer = null;
  };

  const start = () => {
    if (timer || announced) return;
    timer = setInterval(check, POLL_MS);
  };

  // Visible tabs poll; hidden ones do not. Coming back to a tab is also the moment the answer is
  // most likely to have changed and most worth knowing — a laptop reopened after lunch — so
  // becoming visible checks immediately rather than waiting out the interval.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      check();
      start();
    } else {
      stop();
    }
  });

  // Regaining the network is the other moment worth a look: whatever happened while offline, this
  // is the first point at which we can find out.
  window.addEventListener('online', check);

  if (document.visibilityState === 'visible') start();

  return { check: check, announce: announce, stop: stop };
})();
