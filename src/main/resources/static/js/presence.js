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
 * Presence client. On page load, scans every [data-author] element and asks the server
 * for the current online state + custom status of each unique username. While a STOMP
 * client is active, subscribes to /topic/presence and applies live updates so dots and
 * status badges flip in real time.
 *
 * Public surface:
 *   window.Presence = {
 *     stompOptions(),               // StompJs.Client options every page's socket must carry
 *     attachStomp(stompClient),     // wire WS subscription once connected
 *     refreshAll(),                 // re-scan DOM + fetch
 *     stateFor(username),           // last known PresenceDto, or null
 *     onChange(callback),           // subscribe to (username, dto) updates
 *     me(),                         // last known PresenceDto for the signed-in user, or null
 *     isDnd(),                      // is the signed-in user in Do Not Disturb right now
 *   };
 */
(function () {
  if (window.Presence) return;

  /** @type {Map<string, {online:boolean,statusEmoji:?string,statusText:?string,statusClearAt:?string}>} */
  const state = new Map();
  /** @type {Set<(username:string, dto:object)=>void>} */
  const listeners = new Set();

  /*
   * Who the viewer is. Every page that can raise a notification carries this meta; pages that
   * don't (and a future one that forgets it) leave it null, and every caller below treats null
   * as "no override". That is the direction to fail in: a presence client that cannot work out
   * whose state it is looking at must not start silencing things.
   */
  const myUsername =
      document.querySelector('meta[name="me-username"]')?.getAttribute('content') || null;

  /*
   * The viewer's own last-known DTO, tracked beside `state` rather than read back out of it.
   * The map is keyed on the exact string the DOM asked about and the server echoes the spelling
   * it was given, so an exact-match lookup would work today and break the day a caller passes a
   * differently-cased name — which for this one entry means the DND gate silently becoming a
   * no-op. One case-insensitive comparison on the way in is cheaper than trusting that.
   */
  let selfState = null;

  function csrfHeader() {
    const headers = { 'Accept': 'application/json' };
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    if (token && header) headers[header] = token;
    return headers;
  }

  function uniqueAuthors() {
    const set = new Set();
    document.querySelectorAll('[data-author]').forEach((el) => {
      const u = el.getAttribute('data-author');
      if (u) set.add(u);
    });
    return [...set];
  }

  function applyToElement(el, dto) {
    el.classList.toggle('is-online', !!dto.online);
    el.classList.toggle('is-offline', !dto.online);
    // data-presence-kind drives the dot's color variant (CSS selects on the value).
    // ACTIVE → green, AWAY → yellow, DND → red, OFFLINE → gray (or hidden by CSS).
    if (dto.kind) {
      el.setAttribute('data-presence-kind', dto.kind);
    } else {
      el.removeAttribute('data-presence-kind');
    }
    // Status emoji overlay — only on the avatar elements themselves; reactions / mention
    // chips also use [data-author] but we don't want a 🍕 dangling off them.
    if (!el.classList.contains('avatar')) return;
    let badge = el.querySelector('.avatar-status-emoji');
    const showEmoji = dto.statusEmoji && dto.statusEmoji.length > 0;
    if (showEmoji) {
      if (!badge) {
        badge = document.createElement('span');
        badge.className = 'avatar-status-emoji';
        el.appendChild(badge);
      }
      badge.textContent = dto.statusEmoji;
      const tooltip = dto.statusText ? dto.statusEmoji + ' ' + dto.statusText : dto.statusEmoji;
      badge.title = tooltip;
    } else if (badge) {
      badge.remove();
    }
  }

  function applyEverywhere(username, dto) {
    const sel = '[data-author="' + (window.CSS && CSS.escape ? CSS.escape(username) : username) + '"]';
    document.querySelectorAll(sel).forEach((el) => applyToElement(el, dto));
  }

  function update(dto) {
    if (!dto || !dto.username) return;
    state.set(dto.username, dto);
    if (myUsername && dto.username.toLowerCase() === myUsername.toLowerCase()) {
      selfState = dto;
      // Hoisted onto <html> so the stylesheet can react to your own state without a second
      // source of truth in CSS-land. It is what keeps your own DND dot visible when a custom
      // status emoji has taken the corner the presence dot normally occupies.
      document.documentElement.setAttribute('data-self-presence', dto.kind || 'ACTIVE');
    }
    applyEverywhere(dto.username, dto);
    listeners.forEach((cb) => {
      try { cb(dto.username, dto); } catch (e) { /* listener errors must not break the bus */ }
    });
  }

  async function refreshAll() {
    const authors = uniqueAuthors();
    if (!authors.length) return;
    const url = '/api/presence?usernames=' + encodeURIComponent(authors.join(','));
    try {
      const res = await fetch(url, { headers: csrfHeader(), credentials: 'same-origin' });
      if (!res.ok) return;
      const list = await res.json();
      list.forEach((dto) => update(dto));
    } catch (e) {
      // Network blip — try again next refresh cycle.
    }
  }

  /*
   * ---------- "I am still here" ----------
   *
   * Auto-AWAY used to be derived from users.last_active_at, which records the last authenticated
   * HTTP request. That is not a person. Reading a channel makes no HTTP requests, and neither does
   * sending a message — that goes over STOMP, and the send path is query-free by design, so it
   * never touched the column. So the person doing the most talking in a room went yellow while
   * they were talking, and a forgotten background tab stayed green because its polls kept the
   * column warm. This reports the real thing instead.
   *
   * Throttled to one frame per PING_MS, which bounds both the cost (four frames a minute for
   * somebody typing continuously) and the error: the server sees an activity stamp at most PING_MS
   * older than the truth, so it can call somebody AWAY at most that early.
   *
   * Only while the tab is visible. A hidden tab is not a person looking at the screen, and treating
   * it as one is exactly the bug this replaces — in the other direction.
   */
  const PING_MS = 15_000;
  let stompRef = null;
  let lastPingAt = 0;
  // The unthrottled truth behind the pings: when this tab last saw real input. Loading the page
  // counts, exactly as the server counts a fresh CONNECT.
  let lastInputAt = Date.now();

  function pingActivity(force) {
    if (document.visibilityState !== 'visible') return;
    lastInputAt = Date.now();
    if (!stompRef || typeof stompRef.publish !== 'function') return;
    if (!force && lastInputAt - lastPingAt < PING_MS) return;
    try {
      stompRef.publish({ destination: '/app/presence/activity', body: '{}' });
      lastPingAt = lastInputAt;
    } catch (notConnected) {
      // The socket is down, which the server already reads as OFFLINE — a louder state than
      // anything this could report. lastPingAt is left alone so the next input retries rather
      // than waiting out a throttle window on a ping that never left.
    }
  }

  // Capture phase, passive: the same event set idle-logout.js watches, for the same reason — it is
  // the cheapest available definition of "a person is doing something".
  ['mousemove', 'keydown', 'scroll', 'touchstart', 'pointerdown', 'wheel'].forEach((evt) => {
    window.addEventListener(evt, () => pingActivity(false), { passive: true, capture: true });
  });

  // Coming back to a tab is activity, and it is the moment the yellow dot is most conspicuously
  // wrong — so this one skips the throttle rather than waiting for a mouse to move.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') pingActivity(true);
  });

  /*
   * ---------- Keeping the socket honest in a background tab ----------
   *
   * Two things a page's STOMP client has to do for presence to mean anything, handed to the pages
   * as options so neither page can construct a client without them:
   *
   * 1. Heartbeat from a Web Worker, not a page timer. Browsers throttle timers in a hidden tab —
   *    Chrome to once a minute after five minutes, and nothing about an open WebSocket exempts it.
   *    The STOMP heartbeat is a 10s timer and the server hangs up after 30s of silence, so a tab
   *    left behind while its owner read the news was disconnected, shown OFFLINE, reconnected, and
   *    disconnected again, forever — never once AWAY. Worker timers are not throttled.
   *
   * 2. Tell the server how idle this tab is on CONNECT. A reconnect is not a person arriving, but
   *    the server cannot tell a redial from a page load; without this header it stamped every
   *    connect as activity and a reconnecting background tab flashed green. The first connect
   *    reports "just now", which is what a page load is. See ClientIdleHeader on the server.
   */
  function stompOptions() {
    return {
      heartbeatStrategy: 'worker',
      beforeConnect: (client) => {
        client.connectHeaders = { 'idle-ms': String(Math.max(0, Date.now() - lastInputAt)) };
      },
    };
  }

  function attachStomp(stompClient) {
    if (!stompClient || typeof stompClient.subscribe !== 'function') return;
    stompRef = stompClient;
    // Connecting is itself activity — the server stamps it on CONNECT — so there is nothing to
    // send here; the first real input will do it.
    stompClient.subscribe('/topic/presence', (frame) => {
      try {
        const dto = JSON.parse(frame.body);
        update(dto);
      } catch (e) {
        // Ignore malformed frames.
      }
    });
    // Backfill the window between STOMP CONNECT and this subscription registering on
    // the broker. The server fires PresenceEventListener.onConnect (broadcasting
    // alice=online) the moment it processes the CONNECT frame, which is before the
    // client's onConnect callback runs and before this subscribe lands. Spring's
    // in-memory broker has no replay, so the broadcast is lost to us. The REST
    // endpoint queries the live PresenceTracker and gives us the current state —
    // including ourselves, who's now definitely online by the time this fires.
    refreshAll();
  }

  function onChange(cb) {
    if (typeof cb !== 'function') return () => {};
    listeners.add(cb);
    return () => listeners.delete(cb);
  }

  function stateFor(username) {
    return username ? state.get(username) || null : null;
  }

  /**
   * The signed-in user's own presence, or null until the first fetch lands (a few ms after
   * load) or on a page with no me-username meta.
   */
  function me() {
    return selfState;
  }

  /**
   * Is the signed-in user in Do Not Disturb? The one question notifications.js asks, given a
   * name so the answer lives here rather than being open-coded from `kind === 'DND'` in every
   * caller that grows an interest in it.
   *
   * <p>Only DND. AWAY and OFFLINE are statements about what other people see — "I'll answer
   * later", "don't show me as here" — and neither says anything about wanting to be left alone;
   * Slack notifies through both. Unknown state (before the first fetch, or a page without the
   * meta) reads as false, because an unanswerable question is not a request for silence.
   */
  function isDnd() {
    return selfState != null && selfState.kind === 'DND';
  }

  // Mutation observer: when chat.js inserts new messages with avatars, paint them with the
  // last known presence state without needing another network round-trip.
  const observer = new MutationObserver((mutations) => {
    for (const m of mutations) {
      m.addedNodes.forEach((node) => {
        if (!(node instanceof Element)) return;
        const els = node.matches('[data-author]')
          ? [node]
          : node.querySelectorAll ? [...node.querySelectorAll('[data-author]')] : [];
        els.forEach((el) => {
          const dto = state.get(el.getAttribute('data-author'));
          if (dto) applyToElement(el, dto);
        });
      });
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });

  window.Presence = { stompOptions, attachStomp, refreshAll, stateFor, onChange, me, isDnd };

  // Prime the state on page load — even if the STOMP client never attaches (e.g. profile
  // page), we still want the dots painted from the latest server snapshot.
  refreshAll();

  // Backstop poll. The transitions themselves now arrive as /topic/presence broadcasts —
  // connect and disconnect from PresenceEventListener, going idle from PresenceAwaySweeper,
  // coming back from PresenceWebSocketController — so this is no longer how the yellow dot
  // appears. It stays because a broadcast published between the STOMP CONNECT and this page's
  // subscription landing is simply lost (Spring's in-memory broker has no replay), and because
  // a page with no socket at all still wants its dots painted.
  setInterval(refreshAll, 60_000);
})();
