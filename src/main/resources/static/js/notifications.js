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
 * Mention notifications: in-tab toast stack + (when permitted) OS notifications via the
 * Notification API. The toast always shows so the user gets feedback even when permission
 * is denied or unavailable; OS notification is opportunistic and additive.
 *
 * Permission strategy: don't prompt up-front. Wait until the first mention arrives, then
 * include an "Enable desktop alerts" button on the toast. Subsequent toasts skip the prompt.
 *
 * Public surface: window.MentionNotifications = {
 *   show({ author, channel, snippet, url, kind }),  kind: undefined | 'direct' | 'group'
 *   playChime, soundEnabled, setSoundEnabled, permissionState
 * }
 *
 * Despite the name it now carries direct and group messages too — see headline().
 */
(function () {
  const TOAST_TIMEOUT_MS = 8000;
  let stack = null;
  let askedThisSession = false;

  // ---------- Alert sound ----------
  // Synthesised with the Web Audio API rather than shipped as a file. It is ~40 lines against a
  // binary asset with a licence to track and a request to serve, it cannot 404, and the CSP does
  // not have to grant media-src. The cost is that it must be *composed* here rather than chosen,
  // so it is deliberately plain: two short notes, a rise, quiet.
  //
  // Per-device, not per-account, and stored in localStorage: whether you want a noise depends on
  // the room you are sitting in, not on who you are. Slack and Mattermost both treat it that way.
  const SOUND_KEY = 'ichat.notification-sound';
  const soundEnabled = () => localStorage.getItem(SOUND_KEY) !== 'off';
  const setSoundEnabled = (on) => localStorage.setItem(SOUND_KEY, on ? 'on' : 'off');

  let audioCtx = null;
  // Browsers refuse to start an AudioContext until the user has interacted with the page, and a
  // context created before that starts 'suspended' and stays silent. So it is created on the
  // first real gesture and reused — not on the first notification, which is exactly the moment
  // there has been no gesture and the sound would be dropped.
  const unlockAudio = () => {
    if (audioCtx) return;
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    try {
      audioCtx = new Ctx();
    } catch (e) {
      audioCtx = null;
    }
  };
  ['pointerdown', 'keydown'].forEach((evt) => {
    document.addEventListener(evt, unlockAudio, { once: true, passive: true });
  });

  const playChime = () => {
    if (!soundEnabled() || !audioCtx) return;
    // A context can be suspended again by the browser (backgrounded tab, media policy). Resuming
    // is async, so the notes are scheduled off the resulting time rather than "now".
    const start = (t0) => {
      // Two notes a fourth apart. Short, with an exponential decay, because a notification that
      // rings is one the user turns off.
      [[880, 0], [1174.66, 0.08]].forEach(([freq, delay]) => {
        const osc = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        osc.type = 'sine';
        osc.frequency.value = freq;
        const at = t0 + delay;
        // Ramp in over 8ms instead of starting at full gain: a hard start is an audible click.
        gain.gain.setValueAtTime(0.0001, at);
        gain.gain.exponentialRampToValueAtTime(0.12, at + 0.008);
        gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.22);
        osc.connect(gain).connect(audioCtx.destination);
        osc.start(at);
        osc.stop(at + 0.24);
      });
    };
    if (audioCtx.state === 'suspended') {
      audioCtx.resume().then(() => start(audioCtx.currentTime)).catch(() => {});
    } else {
      start(audioCtx.currentTime);
    }
  };

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

  /*
   * What the alert is about. A mention and a direct message are different events and reading
   * "mentioned you in #a direct message" is how you can tell one template was doing both jobs.
   *   (default) a mention in a channel
   *   'direct'  a one-to-one conversation — the room has no name, the sender is the name
   *   'group'   a named group conversation
   */
  function headline({ author, channel, kind }) {
    if (kind === 'direct') return author + ' sent you a direct message';
    if (kind === 'group') return author + ' posted in ' + channel;
    return author + ' mentioned you in #' + channel;
  }

  function fireOsNotification({ author, channel, snippet, url, kind }) {
    if (permissionState() !== 'granted') return null;
    try {
      const n = new Notification(headline({ author, channel, kind }), {
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

  function buildToast({ author, channel, snippet, url, kind }) {
    const li = document.createElement('div');
    li.className = 'notification-toast';
    li.setAttribute('role', 'status');

    const head = document.createElement('div');
    head.className = 'notification-toast-head';
    const headTitle = document.createElement('span');
    headTitle.className = 'notification-toast-title';
    headTitle.textContent = headline({ author, channel, kind });
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'notification-toast-close';
    close.setAttribute('aria-label', 'Dismiss');
    close.innerHTML = '<svg class="icon icon-sm" aria-hidden="true"><use href="#icon-close"/></svg>';
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
            fireOsNotification({ author, channel, snippet, url, kind });
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
    // Independent of the OS-notification permission on purpose. Denying desktop alerts is a
    // statement about banners, not about sound, and the two are separately useful: the sound is
    // what reaches you when the window is behind something else.
    playChime();
  }

  window.MentionNotifications = {
    show, permissionState, playChime, soundEnabled, setSoundEnabled,
  };
})();
