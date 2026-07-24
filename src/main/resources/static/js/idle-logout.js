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
 * Idle logout. After IDLE_TIMEOUT_MS without any user input the browser fires
 * POST /logout (Spring Security clears the session) and navigates home.
 *
 * The authoritative session lifetime is Keycloak's SSO Session Idle (Realm
 * settings → Sessions). This file just makes the logout proactive instead of
 * "next click discovers the session is dead". Spring's server.servlet.session.timeout
 * matches the same 8h default.
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

  const logout = async () => {
    const headers = {};
    if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
    try {
      await fetch('/logout', { method: 'POST', headers, credentials: 'same-origin' });
    } catch (e) {
      // Network error — still navigate; the user is intentionally being signed out.
    }
    window.location.href = '/';
  };

  const arm = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(logout, IDLE_TIMEOUT_MS);
  };

  const reset = () => {
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
