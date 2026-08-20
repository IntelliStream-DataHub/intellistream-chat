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
 * window.FaviconAlert — pulses the browser tab icon when something is addressed to you.
 *
 * The toast, the chime and the desktop notification all reach somebody who is looking at this
 * window or listening to this machine. The tab strip is the one place that reaches somebody who is
 * not: a mention that arrives while they are in a different tab leaves the toast to expire unseen,
 * and the only lasting trace is a badge inside a page they are not looking at. The tab title is
 * already carrying the app name, so the icon is what is left.
 *
 * Implemented as an href swap between two same-origin SVGs rather than as an animated icon,
 * because Chrome does not animate SVG favicons — SMIL and CSS both work in Firefox and Safari and
 * are simply ignored there. Swapping is the only thing all three do. The CSP's `img-src 'self'`
 * is also happier with two static files than with a canvas-generated data: URI.
 *
 * Two behaviours, because the same event means different things depending on where the user is:
 *
 *   - Window not focused: pulse until they come back. They have not seen it yet, and stopping on a
 *     timer would mean the signal is gone by the time it had a job to do.
 *   - Window focused: a short burst and then back to normal. They are already here; a flash says
 *     "look at the sidebar" and anything longer is a strobe on a tab they are staring at.
 *
 * Not gated on Do Not Disturb, for the same reason the sidebar badge and the unread count are not:
 * DND means "stop interrupting me", and it suppresses the three things that interrupt — toast,
 * sound, desktop alert. A tab icon interrupts nobody who is not already looking at the tab strip.
 */
window.FaviconAlert = (function () {
  'use strict';

  const ALERT_HREF = '/img/favicon-alert.svg';
  const PULSE_MS = 700;
  // Six half-cycles: alert, normal, alert, normal, alert, normal. Long enough to catch the eye of
  // somebody already looking at the tab, short enough not to become scenery.
  const FOCUSED_TICKS = 6;

  const link = () => document.querySelector('link[rel="icon"]');

  // The page's own icon, captured before anything swaps it. Read from the tag rather than assumed
  // to be /img/favicon.svg, because an admin can upload a custom logo and the tag then points at
  // /branding/logo with a cache-busting version on it.
  const original = link()?.getAttribute('href') || '/img/favicon.svg';

  let timer = null;
  let showingAlert = false;
  let remainingTicks = -1;   // -1 = until the window is looked at

  const paint = (href) => {
    const el = link();
    if (el && el.getAttribute('href') !== href) el.setAttribute('href', href);
  };

  const stop = () => {
    if (timer) clearInterval(timer);
    timer = null;
    showingAlert = false;
    remainingTicks = -1;
    paint(original);
  };

  const tick = () => {
    showingAlert = !showingAlert;
    paint(showingAlert ? ALERT_HREF : original);
    if (remainingTicks > 0) {
      remainingTicks -= 1;
      if (remainingTicks === 0) stop();
    }
  };

  /**
   * Start (or restart) the pulse. Safe to call on every mention — a second one while the first is
   * still running just re-arms it rather than starting a competing interval.
   */
  const pulse = () => {
    const focused = document.visibilityState === 'visible' && document.hasFocus();
    remainingTicks = focused ? FOCUSED_TICKS : -1;
    if (timer) return;              // already pulsing; the re-armed tick budget above is the update
    showingAlert = false;
    tick();                         // flip immediately — a 700ms wait before anything happens reads as nothing happening
    timer = setInterval(tick, PULSE_MS);
  };

  // Coming back to the window is the acknowledgement. Not "reading the message" — the sidebar
  // badge and the mention bell are what track that, and they outlive this. This is only ever
  // saying "something arrived while you were away", so being back is the whole of its job.
  window.addEventListener('focus', stop);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && document.hasFocus()) stop();
  });

  return { pulse: pulse, clear: stop };
})();
