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
 *   show({ author, channel, snippet, url, kind }),
 *       kind: undefined (a mention) | 'thread' | 'channel' | 'direct' | 'group'
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
  //
  // Two switches, because they are two different interruptions. A direct message is a person
  // waiting on you; a mention is your name going past in a room that is talking anyway. Plenty of
  // people want the first and not the second, and one switch forces them to give up both.
  const SOUND_KEYS = {
    mention: 'ichat.notification-sound.mention',
    conversation: 'ichat.notification-sound.dm',
  };
  const LEGACY_SOUND_KEY = 'ichat.notification-sound';

  const soundKeyFor = (kind) =>
      (kind === 'direct' || kind === 'group') ? SOUND_KEYS.conversation : SOUND_KEYS.mention;

  const soundEnabled = (kind) => {
    const stored = localStorage.getItem(soundKeyFor(kind));
    // Fall back to the single switch this replaced, so anyone who had already turned sound off
    // stays off instead of having it come back on when they upgrade.
    if (stored === null) return localStorage.getItem(LEGACY_SOUND_KEY) !== 'off';
    return stored !== 'off';
  };
  const setSoundEnabled = (kind, on) =>
      localStorage.setItem(soundKeyFor(kind), on ? 'on' : 'off');

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

  // Fifteen voices, synthesised like the original rather than shipped as files — same reasoning:
  // no binary assets, no licences to track, no media-src in the CSP, nothing to 404. Each is a
  // short list of [frequency, delay] notes plus a waveform and a decay, which is enough range to
  // sound different without any of them sounding like an alarm. Gain is tuned per voice
  // rather than shared: a square wave and a low triangle at the same amplitude are not the
  // same loudness, and one voice being twice as loud as the rest is how a picker becomes a
  // trap.
  //
  // 'chime' is first and is the default, because it is what everyone already has: changing the
  // sound under people who never asked for a new one is its own small annoyance.
  const VOICES = {
    chime:   { label: 'Chime',   type: 'sine',     decay: 0.22, gain: 0.12,
               notes: [[880, 0], [1174.66, 0.08]] },
    ping:    { label: 'Ping',    type: 'sine',     decay: 0.34, gain: 0.10,
               notes: [[1318.51, 0]] },
    knock:   { label: 'Knock',   type: 'triangle', decay: 0.13, gain: 0.20,
               notes: [[196, 0], [147, 0.09]] },
    marimba: { label: 'Marimba', type: 'triangle', decay: 0.26, gain: 0.13,
               notes: [[659.25, 0], [783.99, 0.07], [1046.5, 0.14]] },
    pulse:   { label: 'Pulse',   type: 'square',   decay: 0.10, gain: 0.05,
               notes: [[523.25, 0], [523.25, 0.13]] },
    bell:    { label: 'Bell',    type: 'sine',     decay: 0.50, gain: 0.10,
               notes: [[1046.5, 0], [1567.98, 0.05]] },
    drop:    { label: 'Drop',    type: 'sine',     decay: 0.24, gain: 0.12,
               notes: [[880, 0], [587.33, 0.07]] },
    rise:    { label: 'Rise',    type: 'sine',     decay: 0.24, gain: 0.12,
               notes: [[523.25, 0], [880, 0.07]] },
    bloop:   { label: 'Bloop',   type: 'sine',     decay: 0.18, gain: 0.16,
               notes: [[392, 0], [523.25, 0.06]] },
    tritone: { label: 'Tri-tone', type: 'sine',    decay: 0.20, gain: 0.11,
               notes: [[1046.5, 0], [880, 0.06], [698.46, 0.12]] },
    glass:   { label: 'Glass',   type: 'sine',     decay: 0.16, gain: 0.08,
               notes: [[1760, 0], [2093, 0.05]] },
    wood:    { label: 'Wood',    type: 'triangle', decay: 0.12, gain: 0.24,
               notes: [[130.81, 0]] },
    sonar:   { label: 'Sonar',   type: 'sine',     decay: 0.55, gain: 0.11,
               notes: [[440, 0]] },
    tick:    { label: 'Tick',    type: 'square',   decay: 0.04, gain: 0.04,
               notes: [[1200, 0]] },
    arp:     { label: 'Arpeggio', type: 'triangle', decay: 0.22, gain: 0.11,
               notes: [[523.25, 0], [659.25, 0.05], [783.99, 0.10], [1046.5, 0.15]] },
  };
  const DEFAULT_VOICE = 'chime';

  const VOICE_KEYS = {
    mention: 'ichat.notification-sound.voice.mention',
    conversation: 'ichat.notification-sound.voice.dm',
  };
  const voiceKeyFor = (kind) =>
      (kind === 'direct' || kind === 'group') ? VOICE_KEYS.conversation : VOICE_KEYS.mention;

  /** Which voice this kind uses. Unknown or missing falls back rather than going silent. */
  const soundVoice = (kind) => {
    const stored = localStorage.getItem(voiceKeyFor(kind));
    return (stored && VOICES[stored]) ? stored : DEFAULT_VOICE;
  };
  const setSoundVoice = (kind, name) => {
    if (VOICES[name]) localStorage.setItem(voiceKeyFor(kind), name);
  };
  /** [{name, label}] for building a picker without exporting the synthesis details. */
  const soundVoices = () => Object.entries(VOICES).map(([name, v]) => ({ name, label: v.label }));

  const emit = (voiceName) => {
    if (!audioCtx) return;
    const v = VOICES[voiceName] || VOICES[DEFAULT_VOICE];
    // A context can be suspended again by the browser (backgrounded tab, media policy). Resuming
    // is async, so the notes are scheduled off the resulting time rather than "now".
    const start = (t0) => {
      v.notes.forEach(([freq, delay]) => {
        const osc = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        osc.type = v.type;
        osc.frequency.value = freq;
        const at = t0 + delay;
        // Ramp in over 8ms instead of starting at full gain: a hard start is an audible click.
        gain.gain.setValueAtTime(0.0001, at);
        gain.gain.exponentialRampToValueAtTime(v.gain, at + 0.008);
        gain.gain.exponentialRampToValueAtTime(0.0001, at + v.decay);
        osc.connect(gain).connect(audioCtx.destination);
        osc.start(at);
        osc.stop(at + v.decay + 0.02);
      });
    };
    if (audioCtx.state === 'suspended') {
      audioCtx.resume().then(() => start(audioCtx.currentTime)).catch(() => {});
    } else {
      start(audioCtx.currentTime);
    }
  };

  /** Play the sound this kind is configured for, if that kind is switched on at all. */
  const playChime = (kind) => {
    if (!soundEnabled(kind)) return;
    emit(soundVoice(kind));
  };

  /** Play a named voice regardless of the on/off switches — for previewing a picker. */
  const playVoice = (name) => emit(name);

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
   *   'thread'  a reply in a thread the reader is in — neither a mention nor ordinary traffic
   *   'channel' an ordinary message in a channel the reader set to notify on everything
   *   'direct'  a one-to-one conversation — the room has no name, the sender is the name
   *   'group'   a named group conversation
   */
  function headline({ author, channel, kind }) {
    if (kind === 'direct') return author + ' sent you a direct message';
    if (kind === 'group') return author + ' posted in ' + channel;
    // A reply in a thread the reader has written in. Distinct from both of its neighbours on
    // purpose: it is not a mention, and "posted in #channel" would send them hunting through the
    // main feed for a message that is inside a thread.
    if (kind === 'thread') return author + ' replied to a thread in #' + channel;
    // Not a mention — an ordinary message in a channel the user set to notify on everything.
    // Saying "mentioned you" here would be a lie, and the kind of lie that teaches people to
    // ignore the notification.
    if (kind === 'channel') return author + ' posted in #' + channel;
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
    playChime(opts.kind);
  }

  window.MentionNotifications = {
    show, permissionState, playChime, playVoice,
    soundEnabled, setSoundEnabled, soundVoice, setSoundVoice, soundVoices,
  };
})();
