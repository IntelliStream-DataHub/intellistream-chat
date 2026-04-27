/*
 * Presence client. On page load, scans every [data-author] element and asks the server
 * for the current online state + custom status of each unique username. While a STOMP
 * client is active, subscribes to /topic/presence and applies live updates so dots and
 * status badges flip in real time.
 *
 * Public surface:
 *   window.Presence = {
 *     attachStomp(stompClient),     // wire WS subscription once connected
 *     refreshAll(),                 // re-scan DOM + fetch
 *     stateFor(username),           // last known PresenceDto, or null
 *     onChange(callback),           // subscribe to (username, dto) updates
 *   };
 */
(function () {
  if (window.Presence) return;

  /** @type {Map<string, {online:boolean,statusEmoji:?string,statusText:?string,statusClearAt:?string}>} */
  const state = new Map();
  /** @type {Set<(username:string, dto:object)=>void>} */
  const listeners = new Set();

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

  function attachStomp(stompClient) {
    if (!stompClient || typeof stompClient.subscribe !== 'function') return;
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

  window.Presence = { attachStomp, refreshAll, stateFor, onChange };

  // Prime the state on page load — even if the STOMP client never attaches (e.g. profile
  // page), we still want the dots painted from the latest server snapshot.
  refreshAll();
})();
