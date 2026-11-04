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
 * window.ChatConnection — says so when the live socket is down.
 *
 * The page-owned STOMP clients redial every four seconds, forever, and until now said nothing while
 * they did. A tab whose socket had dropped (laptop slept, proxy cut the idle connection, server
 * restarted) looked exactly like a live one: sidebar drawn, composer open, no new message ever
 * arriving. The only tell was a send that quietly took the HTTP fallback.
 *
 * So: a small strip while the socket is down, gone the moment it is back. Both pages call
 * ChatConnection.watch(stomp) once, after they have assigned their own onConnect — it wraps what is
 * there rather than replacing it, so page logic (backfill, subscriptions) runs exactly as before.
 *
 * What it deliberately does not do:
 *
 *   - It does not announce a blip. The client redials after reconnectDelay (4s) and a clean redial
 *     takes well under a second, so the strip waits GRACE_MS before it appears. It also never
 *     appears on the way out of the page: navigating away closes the socket too.
 *   - It does not compete with the sign-out notices. A dead session also kills the socket, and
 *     "connection lost" under "you have been signed out" is noise. The stylesheet hides this strip
 *     while #session-lost or the idle-logout dialog is on the page (body:has(...)), and the
 *     chat:signed-out event idle-logout.js fires makes this stop redialling a session that is gone.
 */
window.ChatConnection = (function () {
  'use strict';

  const GRACE_MS = 6000;

  let bar = null;
  let timer = null;
  let leaving = false;

  window.addEventListener('pagehide', () => { leaving = true; });

  const show = () => {
    timer = null;
    if (leaving || bar) return;
    bar = document.createElement('div');
    bar.className = 'connection-lost';
    bar.setAttribute('role', 'status');

    const text = document.createElement('p');
    text.className = 'connection-lost-text';
    const strong = document.createElement('strong');
    strong.textContent = 'Connection lost.';
    text.append(strong, document.createTextNode(
        ' Reconnecting… New messages will not appear until it is back.'));
    bar.append(text);
    document.body.appendChild(bar);
  };

  const hide = () => {
    if (timer) clearTimeout(timer);
    timer = null;
    if (bar) bar.remove();
    bar = null;
  };

  const lost = () => {
    // A second close while one is already pending (a failed redial) must not push the strip back.
    if (leaving || timer || bar) return;
    timer = setTimeout(show, GRACE_MS);
  };

  /**
   * Watch one StompJs.Client. Call after the page has assigned its own onConnect, before activate().
   */
  const watch = (stomp) => {
    if (!stomp) return;

    const prevConnect = stomp.onConnect;
    stomp.onConnect = function (frame) {
      hide();
      return typeof prevConnect === 'function' ? prevConnect.call(this, frame) : undefined;
    };

    const prevClose = stomp.onWebSocketClose;
    stomp.onWebSocketClose = function (evt) {
      lost();
      return typeof prevClose === 'function' ? prevClose.call(this, evt) : undefined;
    };

    // The session is gone (idle-logout.js): redialling can only be refused, every four seconds,
    // for as long as the tab stays open.
    window.addEventListener('chat:signed-out', () => {
      hide();
      leaving = true;
      stomp.deactivate();
    });
  };

  return { watch: watch };
})();
