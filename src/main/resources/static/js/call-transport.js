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
 * The media half of a call, behind a seam.
 *
 * WHY THIS IS A SEAM AND NOT JUST A PEER CONNECTION
 *
 * Group calls need an SFU — a server that receives one stream per participant and forwards it —
 * because a mesh makes every participant encode one stream per other participant, and that wall is
 * at four to six people whatever the server can do. When that lands, this file is what gets
 * replaced: a LiveKit-backed transport exposing the same six methods, with calls.js untouched.
 *
 * So the rule is that nothing outside this file may touch an RTCPeerConnection, a track, or an SDP.
 * calls.js drives ringing, the UI and the DND gate; this drives media. If that boundary holds, the
 * SFU is a module swap. If it leaks, the SFU is a rewrite that keeps getting postponed.
 *
 * The interface:
 *   start()                 acquire the mic (and camera), open the connection, begin negotiating
 *   accept(payload)         (callee) take the caller's offer
 *   handleSignal(k, p)      feed in an SDP or candidate that arrived over STOMP
 *   setMuted(bool)          microphone
 *   setCameraOn(bool)       camera
 *   stop()                  release everything
 * and the callbacks in `opts`: onSignal, onLocalStream, onRemoteStream, onState, onError.
 */
(function () {
  'use strict';

  /*
   * Preferred video codecs, best first. VP9 leads for compression — roughly a third off VP8 at the
   * same quality — and because it is the codec that carries SVC, which is what the SFU will use to
   * send a low layer to the grid and full resolution to whoever is speaking.
   *
   * This is a *preference*, never a requirement. VP8 and H.264 are mandatory-to-implement in
   * WebRTC, so anything that cannot do VP9 negotiates down on its own and the call still connects.
   * That is also why the list is a sort rather than a filter: dropping the codecs we do not prefer
   * would turn a graceful downgrade into a failure to negotiate at all, and dropping the auxiliary
   * entries below would be worse still.
   */
  const VIDEO_CODEC_ORDER = ['video/VP9', 'video/VP8', 'video/H264', 'video/AV1'];

  /*
   * rtx is retransmission, red is redundant encoding, ulpfec is forward error correction. None of
   * them is a codec you choose; all of them are machinery the chosen codec leans on for loss
   * recovery. setCodecPreferences replaces the whole list, so leaving these out of the array
   * silently turns off packet recovery and the symptom is a call that degrades badly on a lossy
   * network for no visible reason. They keep their relative order at the end.
   */
  const AUXILIARY_CODECS = /\/(rtx|red|ulpfec|flexfec)/i;

  /* Per-stream ceiling for 1:1 video. Not about the relay's bandwidth — one call is nothing
   * against a gigabit — but about the uplink of whoever is on hotel wifi. WebRTC's own congestion
   * control will find a lower rate when it needs to; this stops it from finding a much higher one
   * and spending someone's tethered connection on a talking head. */
  const MAX_VIDEO_BITRATE = 1_200_000;

  const AUDIO_CONSTRAINTS = {
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true,
  };

  const VIDEO_CONSTRAINTS = {
    width: { ideal: 1280 },
    height: { ideal: 720 },
    frameRate: { ideal: 30 },
  };

  /**
   * Reorder an m-line's codecs to our preference without losing any of them.
   * @param {RTCRtpTransceiver} transceiver
   */
  function preferVideoCodecs(transceiver) {
    if (typeof transceiver.setCodecPreferences !== 'function') return;
    const caps = (window.RTCRtpSender && RTCRtpSender.getCapabilities)
      ? RTCRtpSender.getCapabilities('video')
      : null;
    if (!caps || !caps.codecs) return;

    const rank = (codec) => {
      if (AUXILIARY_CODECS.test(codec.mimeType)) return VIDEO_CODEC_ORDER.length + 1;
      const i = VIDEO_CODEC_ORDER.indexOf(codec.mimeType);
      return i === -1 ? VIDEO_CODEC_ORDER.length : i;
    };
    const ordered = caps.codecs.slice().sort((a, b) => rank(a) - rank(b));
    try {
      transceiver.setCodecPreferences(ordered);
    } catch (e) {
      // A browser that rejects the list has its own opinion about codecs, and its own opinion is a
      // working call. Never let a preference become a failure.
      console.warn('Could not set codec preferences', e);
    }
  }

  /*
   * Turn on Opus DTX and in-band FEC.
   *
   * THIS IS THE ONE PLACE THIS FILE EDITS AN SDP, AND IT IS DELIBERATE.
   *
   * Munging SDP is normally the wrong tool — it is fragile, it breaks on browser updates, and
   * setCodecPreferences exists precisely so nobody has to reorder m-lines by hand. But DTX and FEC
   * live in the opus fmtp line and no API surfaces them: `RTCRtpEncodingParameters.dtx` was drafted
   * and unshipped, and there has never been anything for FEC. So the choice is this narrow,
   * well-understood edit — append two parameters to one fmtp line, leaving everything else in the
   * SDP exactly as the browser wrote it — or ship without them.
   *
   * They are worth the exception. DTX stops sending audio when nobody is talking, which is most of
   * a call for most participants and is the single biggest saving available on the relay's egress.
   * FEC is what makes speech survive packet loss without a retransmit round trip, and on a relayed
   * call every packet has already taken a detour.
   */
  function enableOpusFeatures(sdp) {
    const opusPayload = /a=rtpmap:(\d+) opus\/48000/i.exec(sdp);
    if (!opusPayload) return sdp;
    const pt = opusPayload[1];
    const fmtp = new RegExp(`a=fmtp:${pt} ([^\\r\\n]*)`);
    if (fmtp.test(sdp)) {
      return sdp.replace(fmtp, (line, params) => {
        let out = params;
        if (!/usedtx=/.test(out)) out += ';usedtx=1';
        if (!/useinbandfec=/.test(out)) out += ';useinbandfec=1';
        // Voice is mono. Stereo doubles the bitrate to transmit a second copy of one person
        // talking into one microphone.
        if (!/stereo=/.test(out)) out += ';stereo=0';
        return `a=fmtp:${pt} ${out}`;
      });
    }
    return sdp.replace(
      new RegExp(`(a=rtpmap:${pt} opus/48000[^\\r\\n]*)`),
      `$1\r\na=fmtp:${pt} usedtx=1;useinbandfec=1;stereo=0`
    );
  }

  /** Cap the outbound video rate and tell the encoder what to sacrifice first. */
  async function shapeVideoSender(sender) {
    if (!sender || !sender.getParameters) return;
    try {
      const params = sender.getParameters();
      if (!params.encodings || !params.encodings.length) params.encodings = [{}];
      params.encodings[0].maxBitrate = MAX_VIDEO_BITRATE;
      // Faces move; slides do not. For a talking head a smooth 15fps at a lower resolution reads
      // far better than a sharp slideshow, so resolution is what gives when the link is tight.
      params.degradationPreference = 'maintain-framerate';
      await sender.setParameters(params);
    } catch (e) {
      console.warn('Could not shape the video sender', e);
    }
  }

  /**
   * @param {object} opts
   * @param {boolean} opts.polite  perfect-negotiation role. The callee is polite: it yields when
   *   both ends offer at once. Assigned by role from the server rather than negotiated between the
   *   peers, because two polite peers deadlock as surely as two impolite ones collide.
   * @param {boolean} opts.video   whether to open the camera
   * @param {object}  opts.iceConfig  as served by GET /api/calls/ice
   */
  function createPeerTransport(opts) {
    const { polite, video, iceConfig } = opts;
    const emitSignal = opts.onSignal || function () {};
    const onLocalStream = opts.onLocalStream || function () {};
    const onRemoteStream = opts.onRemoteStream || function () {};
    const onState = opts.onState || function () {};
    const onError = opts.onError || function () {};

    let pc = null;
    let localStream = null;
    let stopped = false;

    // Perfect negotiation bookkeeping (the pattern from the WebRTC spec). Without it, two peers
    // that offer in the same instant — which happens whenever both sides add a track at once, and
    // on every ICE restart — deadlock or tear the connection down. The failure is intermittent and
    // unreproducible, which is exactly the kind worth spending thirty lines to rule out.
    let makingOffer = false;
    let ignoreOffer = false;
    let settingRemoteAnswer = false;

    // Candidates that arrived before setRemoteDescription. The peer starts trickling the moment it
    // has a local description, so on a fast network the first candidates routinely beat the offer
    // they belong to; addIceCandidate would throw on every one of them and those network paths
    // would simply never be tried.
    const pendingCandidates = [];

    function buildConnection() {
      const config = {
        iceServers: iceConfig.iceServers || [],
        // Served, not hardcoded — ichat.calls.force-relay decides it. 'relay' means the browser
        // gathers only TURN candidates: every call goes through the relay, nobody learns anybody's
        // IP address, and a call either works for everyone or fails for everyone.
        iceTransportPolicy: iceConfig.iceTransportPolicy || 'relay',
        bundlePolicy: 'max-bundle',
      };
      const conn = new RTCPeerConnection(config);

      conn.addEventListener('negotiationneeded', async () => {
        if (stopped) return;
        try {
          makingOffer = true;
          await conn.setLocalDescription();
          emitSignal('offer', withOpusFeatures(conn.localDescription));
        } catch (e) {
          onError(e);
        } finally {
          makingOffer = false;
        }
      });

      conn.addEventListener('icecandidate', ({ candidate }) => {
        // The null candidate marks the end of gathering. Nothing to relay, and sending it would
        // have the peer call addIceCandidate(null), which some browsers treat as "no more
        // candidates ever" — a real way to lose a connection that was about to succeed.
        if (candidate) emitSignal('candidate', candidate.toJSON());
      });

      conn.addEventListener('track', (event) => {
        if (event.streams && event.streams[0]) onRemoteStream(event.streams[0]);
      });

      conn.addEventListener('connectionstatechange', () => {
        onState(conn.connectionState);
        // 'failed' is terminal — ICE has exhausted every candidate pair. 'disconnected' is not; it
        // is routinely a few seconds of bad wifi that recovers on its own, so it is reported and
        // not acted on.
        if (conn.connectionState === 'failed') onError(new Error('Connection failed'));
      });

      return conn;
    }

    function withOpusFeatures(description) {
      return { type: description.type, sdp: enableOpusFeatures(description.sdp) };
    }

    async function openMedia() {
      if (!window.isSecureContext) {
        // Worth its own message: getUserMedia is silently unavailable outside a secure context, and
        // localhost is exempt while a LAN address is not — so this fires exactly when someone is
        // testing over http://192.168.x.x and would otherwise see "calls are broken".
        throw new Error('Calls need HTTPS. Camera and microphone access is blocked on an '
          + 'insecure origin (localhost is the only exception).');
      }
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error('This browser cannot make calls.');
      }
      localStream = await navigator.mediaDevices.getUserMedia({
        audio: AUDIO_CONSTRAINTS,
        video: video ? VIDEO_CONSTRAINTS : false,
      });
      onLocalStream(localStream);
      return localStream;
    }

    function publish(stream) {
      stream.getTracks().forEach((track) => pc.addTrack(track, stream));
      pc.getTransceivers().forEach((t) => {
        const kind = (t.sender.track && t.sender.track.kind)
          || (t.receiver.track && t.receiver.track.kind);
        if (kind === 'video') {
          preferVideoCodecs(t);
          shapeVideoSender(t.sender);
        }
      });
    }

    async function drainCandidates() {
      while (pendingCandidates.length) {
        const candidate = pendingCandidates.shift();
        try {
          await pc.addIceCandidate(candidate);
        } catch (e) {
          if (!ignoreOffer) console.warn('Discarding an unusable ICE candidate', e);
        }
      }
    }

    return {
      /** Open the mic/camera and the connection. The caller's side then offers automatically. */
      async start() {
        pc = buildConnection();
        publish(await openMedia());
      },

      /**
       * Feed in an SDP or candidate from the peer.
       *
       * This is the perfect-negotiation core. The impolite peer ignores an offer that collides with
       * its own; the polite peer rolls back and takes the other side's. Exactly one of them yields,
       * which is the whole point — and it is why the role is assigned by the server rather than
       * agreed between two clients that would have to agree about who spoke first.
       */
      async handleSignal(kind, payload) {
        if (stopped || !pc) return;
        try {
          if (kind === 'offer' || kind === 'answer') {
            const ready = !makingOffer
              && (pc.signalingState === 'stable' || settingRemoteAnswer);
            const collision = kind === 'offer' && !ready;

            ignoreOffer = !polite && collision;
            if (ignoreOffer) return;

            settingRemoteAnswer = kind === 'answer';
            await pc.setRemoteDescription(payload);
            settingRemoteAnswer = false;
            await drainCandidates();

            if (kind === 'offer') {
              await pc.setLocalDescription();
              emitSignal('answer', withOpusFeatures(pc.localDescription));
            }
          } else if (kind === 'candidate') {
            if (!pc.remoteDescription) {
              pendingCandidates.push(payload);
              return;
            }
            try {
              await pc.addIceCandidate(payload);
            } catch (e) {
              // An offer we deliberately ignored leaves candidates behind that belong to it.
              // Dropping those is correct; anything else is worth a line in the console.
              if (!ignoreOffer) console.warn('Discarding an unusable ICE candidate', e);
            }
          }
        } catch (e) {
          onError(e);
        }
      },

      setMuted(muted) {
        if (!localStream) return;
        localStream.getAudioTracks().forEach((t) => { t.enabled = !muted; });
      },

      setCameraOn(on) {
        if (!localStream) return;
        localStream.getVideoTracks().forEach((t) => { t.enabled = on; });
      },

      hasVideo() {
        return !!(localStream && localStream.getVideoTracks().length);
      },

      /** Release the devices and the connection. Idempotent — teardown races are routine here. */
      stop() {
        if (stopped) return;
        stopped = true;
        if (localStream) {
          // Stopping the tracks is what turns the camera light off. Closing the peer connection
          // alone leaves the device open, and a light that stays on after a call is the single
          // most alarming bug a calling feature can ship.
          localStream.getTracks().forEach((t) => t.stop());
          localStream = null;
        }
        if (pc) {
          pc.close();
          pc = null;
        }
      },
    };
  }

  window.CallTransport = { createPeerTransport };
}());
