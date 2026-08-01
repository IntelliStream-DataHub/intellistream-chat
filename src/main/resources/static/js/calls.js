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
 * Ringing, and the UI around it. The media lives in call-transport.js and this file never touches
 * an RTCPeerConnection — see the note there for why that boundary is worth keeping.
 *
 * This is the half of a calling feature where the bugs actually are. Media either negotiates or it
 * does not, and the browser tells you; ringing is a distributed state machine across two accounts
 * and every tab they have open, and its failures are the ones users report as "it just kept
 * ringing". So the states are named and there is exactly one function that moves between them.
 *
 *   idle        nothing happening
 *   outgoing    we placed a call, waiting for them to pick up
 *   incoming    they placed one, this tab is ringing
 *   connecting  answered, media negotiating
 *   active      connected
 *
 * Loaded on every page with a socket, not just the DM page: a call you cannot receive while reading
 * a channel is not a calling feature. Only the DM page has the buttons to *place* one.
 */
(function () {
  'use strict';

  const panel = document.getElementById('call-panel');
  if (!panel) return;

  const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content || '';
  const myUsername = meta('me-username');

  const els = {
    peerName: document.getElementById('call-peer-name'),
    status: document.getElementById('call-status'),
    avatar: document.getElementById('call-avatar'),
    timer: document.getElementById('call-timer'),
    stage: document.getElementById('call-stage'),
    remoteVideo: document.getElementById('call-remote-video'),
    localVideo: document.getElementById('call-local-video'),
    remoteAudio: document.getElementById('call-remote-audio'),
    accept: document.getElementById('call-accept'),
    decline: document.getElementById('call-decline'),
    hangup: document.getElementById('call-hangup'),
    mute: document.getElementById('call-mute'),
    camera: document.getElementById('call-camera'),
  };

  let stomp = null;
  let iceConfig = null;
  let transport = null;
  let state = 'idle';
  let call = null;          // {id, peer, peerName, media, polite}
  let timerHandle = null;
  let connectedAt = 0;
  let muted = false;
  let cameraOn = true;

  /* ---------- ringtone ---------- */

  /*
   * Synthesised rather than shipped as an audio file, for the same reason notifications.js
   * synthesises its chimes: the CSP allows no external media, and a bundled ringtone is a licensing
   * question and a few hundred KB for two notes.
   *
   * The ring is deliberately not the notification chime. A chime says something arrived and can be
   * read whenever; a ring says somebody is waiting for you right now, and the two should not sound
   * alike when one of them can be ignored and the other cannot.
   */
  const ringer = (function () {
    let ctx = null;
    let handle = null;

    const beep = () => {
      if (!ctx) return;
      const at = ctx.currentTime;
      [440, 480].forEach((freq) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.value = freq;
        gain.gain.setValueAtTime(0.0001, at);
        gain.gain.exponentialRampToValueAtTime(0.08, at + 0.02);
        gain.gain.setValueAtTime(0.08, at + 0.9);
        gain.gain.exponentialRampToValueAtTime(0.0001, at + 1.0);
        osc.connect(gain).connect(ctx.destination);
        osc.start(at);
        osc.stop(at + 1.05);
      });
    };

    return {
      start() {
        // DND suppresses the interruption, not the information: the panel still appears, so a call
        // that arrives while you are looking at the app is visible and answerable. What it does not
        // do is make noise at someone who has said they are heads-down. Same rule as
        // notifications.js, read from presence rather than from this tab's storage so every tab
        // agrees.
        if (window.Notifications && window.Notifications.dndActive
            && window.Notifications.dndActive()) {
          return;
        }
        if (handle) return;
        try {
          ctx = ctx || new (window.AudioContext || window.webkitAudioContext)();
          if (ctx.state === 'suspended') ctx.resume().catch(() => {});
        } catch (e) {
          return; // no audio context; the panel is still on screen
        }
        beep();
        handle = setInterval(beep, 3000);
      },
      stop() {
        if (handle) { clearInterval(handle); handle = null; }
      },
    };
  }());

  /* ---------- panel ---------- */

  const show = () => { panel.hidden = false; };
  const hide = () => { panel.hidden = true; };

  function setButtons() {
    const ringing = state === 'incoming';
    const live = state === 'outgoing' || state === 'connecting' || state === 'active';
    els.accept.hidden = !ringing;
    els.decline.hidden = !ringing;
    els.hangup.hidden = !live;
    // Mute and camera are meaningless before there is a local stream to mute.
    els.mute.hidden = !(state === 'connecting' || state === 'active');
    els.camera.hidden = els.mute.hidden || !(call && call.media === 'VIDEO');
  }

  function setStatus(text) {
    els.status.textContent = text;
  }

  function render() {
    if (state === 'idle') { hide(); return; }
    show();
    els.peerName.textContent = (call && call.peerName) || '';
    if (els.avatar) els.avatar.textContent = ((call && call.peerName) || '?').charAt(0).toUpperCase();
    els.stage.hidden = !(call && call.media === 'VIDEO' && (state === 'connecting' || state === 'active'));
    els.timer.hidden = state !== 'active';
    setButtons();
  }

  function startTimer() {
    connectedAt = Date.now();
    const tick = () => {
      const secs = Math.floor((Date.now() - connectedAt) / 1000);
      const mm = String(Math.floor(secs / 60)).padStart(2, '0');
      const ss = String(secs % 60).padStart(2, '0');
      els.timer.textContent = `${mm}:${ss}`;
    };
    tick();
    timerHandle = setInterval(tick, 1000);
  }

  /* ---------- state ---------- */

  /**
   * The only way out of a call. Every path that ends one — hangup, decline, remote end, failure,
   * a transport error — comes through here, because the alternative is five teardown paths and
   * four of them forgetting to stop the microphone.
   */
  function reset(message) {
    ringer.stop();
    if (timerHandle) { clearInterval(timerHandle); timerHandle = null; }
    if (transport) { transport.stop(); transport = null; }
    if (els.remoteAudio) els.remoteAudio.srcObject = null;
    if (els.remoteVideo) els.remoteVideo.srcObject = null;
    if (els.localVideo) els.localVideo.srcObject = null;
    muted = false;
    cameraOn = true;
    state = 'idle';
    call = null;

    if (message) {
      // Leave the panel up briefly with the closing line, so "Declined" or "No answer" is readable
      // rather than a flash. A call that vanishes the instant it ends leaves the caller unsure
      // whether it was answered.
      setStatus(message);
      els.timer.hidden = true;
      els.stage.hidden = true;
      setButtons();
      setTimeout(() => { if (state === 'idle') hide(); }, 2500);
    } else {
      hide();
    }
  }

  async function ensureIceConfig() {
    if (iceConfig) return iceConfig;
    const res = await fetch('/api/calls/ice', { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error('Could not load call configuration');
    iceConfig = await res.json();
    if (!iceConfig.available) throw new Error('Calling is not configured on this server');
    return iceConfig;
  }

  function sendSignal(kind, payload) {
    if (!stomp || !stomp.connected || !call) return;
    stomp.publish({
      destination: `/app/calls/${call.id}/signal`,
      body: JSON.stringify({ kind, payload }),
    });
  }

  /** Build the media transport. Identical for both sides except the perfect-negotiation role. */
  async function openTransport(polite) {
    const config = await ensureIceConfig();
    transport = window.CallTransport.createPeerTransport({
      polite,
      video: call.media === 'VIDEO',
      iceConfig: config,
      onSignal: sendSignal,
      onLocalStream: (stream) => {
        if (els.localVideo) els.localVideo.srcObject = stream;
      },
      onRemoteStream: (stream) => {
        // Audio and video are attached separately. A single <video> would carry both, but an
        // audio-only call would then depend on a video element being laid out and unhidden to be
        // audible at all, which is a silent failure waiting for the first CSS change.
        if (els.remoteAudio) els.remoteAudio.srcObject = stream;
        if (els.remoteVideo) els.remoteVideo.srcObject = stream;
      },
      onState: (connState) => {
        if (connState === 'connected' && state !== 'active') {
          state = 'active';
          setStatus('');
          render();
          startTimer();
        } else if (connState === 'disconnected' && state === 'active') {
          // Not terminal — usually a few seconds of bad network that recovers. Say so instead of
          // hanging up on somebody whose train went into a tunnel.
          setStatus('Reconnecting…');
        }
      },
      onError: (err) => {
        console.warn('[call] transport error', err);
        hangUp(err.message || 'Call failed');
      },
    });
    await transport.start();
  }

  /* ---------- actions ---------- */

  async function place(conversationId, media) {
    if (state !== 'idle') return;
    try {
      await ensureIceConfig();
    } catch (e) {
      reset(e.message);
      return;
    }
    // Optimistic: the panel goes up before the server answers, because a call button that does
    // nothing for a round trip gets pressed again. The `failed` and `busy` events are what retire
    // this state if it never becomes a call.
    state = 'outgoing';
    call = { id: null, peer: null, peerName: '…', media, polite: false };
    setStatus('Calling…');
    render();
    stomp.publish({
      destination: '/app/calls/invite',
      body: JSON.stringify({ conversationId: Number(conversationId), media }),
    });
  }

  async function acceptCall() {
    if (state !== 'incoming' || !call) return;
    ringer.stop();
    state = 'connecting';
    setStatus('Connecting…');
    render();
    stomp.publish({ destination: `/app/calls/${call.id}/accept`, body: '' });
    try {
      // The callee is the polite peer. Both sides add tracks and so both will offer; perfect
      // negotiation has exactly one of them yield, and it must be the same one every time.
      await openTransport(true);
    } catch (e) {
      console.warn('[call] could not open media', e);
      hangUp(e.message || 'Could not start the call');
    }
  }

  function declineCall() {
    if (state !== 'incoming' || !call) return;
    stomp.publish({ destination: `/app/calls/${call.id}/decline`, body: '' });
    reset('Declined');
  }

  function hangUp(message) {
    if (state === 'idle') return;
    if (call && call.id && stomp && stomp.connected) {
      stomp.publish({ destination: `/app/calls/${call.id}/hangup`, body: '' });
    }
    reset(message || 'Call ended');
  }

  function toggleMute() {
    if (!transport) return;
    muted = !muted;
    transport.setMuted(muted);
    els.mute.classList.toggle('is-active', muted);
    els.mute.setAttribute('aria-pressed', String(muted));
    els.mute.title = muted ? 'Unmute' : 'Mute';
  }

  function toggleCamera() {
    if (!transport) return;
    cameraOn = !cameraOn;
    transport.setCameraOn(cameraOn);
    els.camera.classList.toggle('is-active', !cameraOn);
    els.camera.setAttribute('aria-pressed', String(!cameraOn));
    els.camera.title = cameraOn ? 'Turn camera off' : 'Turn camera on';
  }

  /* ---------- server events ---------- */

  const ENDED_MESSAGES = {
    DECLINED: 'Declined',
    TIMEOUT: 'No answer',
    CANCELLED: 'Call cancelled',
    DISCONNECTED: 'Call disconnected',
    HANGUP: 'Call ended',
  };

  function onEvent(ev) {
    switch (ev.type) {
      case 'invite': {
        // Already busy on this tab: the server refuses a second call for the account anyway, so
        // this only fires for a race. Ignore it rather than replacing a call in progress.
        if (state !== 'idle') return;
        call = {
          id: ev.callId,
          peer: ev.peer,
          peerName: ev.peerDisplayName || ev.peer,
          media: ev.media,
          polite: true,
        };
        state = 'incoming';
        setStatus(ev.media === 'VIDEO' ? 'Incoming video call' : 'Incoming call');
        render();
        ringer.start();
        break;
      }
      case 'ringing': {
        if (state !== 'outgoing') return;
        call.id = ev.callId;
        call.peer = ev.peer;
        call.peerName = ev.peerDisplayName || ev.peer;
        setStatus('Ringing…');
        render();
        break;
      }
      case 'accepted': {
        if (state === 'outgoing') {
          // We are the caller and they picked up. Impolite peer — we hold our offer on a collision.
          state = 'connecting';
          setStatus('Connecting…');
          render();
          openTransport(false).catch((e) => {
            console.warn('[call] could not open media', e);
            hangUp(e.message || 'Could not start the call');
          });
        } else if (state === 'incoming') {
          // Another of this account's tabs answered first. Stop ringing here — this is the whole
          // of multi-device answering, and it needs no device id: the tab that answered has
          // already moved to 'connecting' and never reaches this branch.
          reset('Answered on another device');
        }
        break;
      }
      case 'signal': {
        if (transport && call && ev.callId === call.id) {
          transport.handleSignal(ev.signalKind, ev.payload);
        }
        break;
      }
      case 'busy': {
        const who = ev.peerDisplayName || ev.peer;
        reset(who === myUsername ? 'You are already on a call' : `${who} is on another call`);
        break;
      }
      case 'ended': {
        if (state === 'idle') return;
        if (call && call.id && ev.callId && ev.callId !== call.id) return;
        reset(ENDED_MESSAGES[ev.reason] || 'Call ended');
        break;
      }
      case 'failed': {
        reset('Calling is not available here');
        break;
      }
      default:
        break;
    }
  }

  /* ---------- wiring ---------- */

  els.accept?.addEventListener('click', acceptCall);
  els.decline?.addEventListener('click', declineCall);
  els.hangup?.addEventListener('click', () => hangUp());
  els.mute?.addEventListener('click', toggleMute);
  els.camera?.addEventListener('click', toggleCamera);

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || state === 'idle') return;
    // Escape declines a ringing call and does nothing to a live one — hanging up on a real
    // conversation should take a deliberate click, not the key people press to close things.
    if (state === 'incoming') declineCall();
  });

  // A tab closing mid-call must not leave the other side watching a timer. The server's disconnect
  // hook covers this too, but only after the socket actually closes; this is the fast path.
  window.addEventListener('pagehide', () => {
    if (state !== 'idle') hangUp();
  });

  document.querySelectorAll('[data-call-start]').forEach((btn) => {
    btn.addEventListener('click', () => {
      place(btn.dataset.callConversation, btn.dataset.callStart.toUpperCase());
    });
  });

  function attachStomp(client) {
    stomp = client;
    client.subscribe('/user/queue/calls', (frame) => {
      try {
        onEvent(JSON.parse(frame.body));
      } catch (e) {
        console.warn('[call] malformed event', e);
      }
    });
  }

  window.Calls = { attachStomp };
}());
