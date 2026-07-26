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
 * @-mention typeahead for the composer.
 *
 * Why it exists: the UI leads with display names, the mention syntax needs the handle, and
 * nothing in the app told you the mapping while composing. Two people can share a display name,
 * and the member list showed names without handles — so "who is @alice?" had no answer at the one
 * moment it mattered. An unresolvable @handle then failed silently: no notification, no warning,
 * text that looks exactly like a working mention.
 *
 * Deliberately self-attaching and self-contained. It hangs off the DOM (#composer-input,
 * #thread-input, and the DM page's composer, which shares the first id) and talks to one endpoint;
 * it imports nothing but shared.js. chat/index.js does not know it exists, which is what lets the
 * DM page — which never loads index.js — get the same behaviour from the same code.
 *
 * The one coupling that matters is Enter. Enter-to-send lives in a document-level keydown handler
 * in index.js, in the bubble phase, so this module (bound to the textarea, i.e. the target) sees
 * the key first. When the popup is open, Enter completes and calls stopPropagation so the message
 * is not also sent; when it is closed, the event is untouched and Enter sends exactly as before.
 * The same reasoning applies to Escape, which index.js uses to close the thread panel.
 */

import { meta } from './shared.js';

/** Rows requested from the server. Wider than we display, so narrowing can happen locally. */
const FETCH_LIMIT = 25;
/** Rows shown at once. Beyond about this many, a picker stops being faster than typing. */
const SHOW_LIMIT = 8;
/** Long enough to swallow a burst of typing, short enough to feel like it is keeping up. */
const DEBOUNCE_MS = 160;

/**
 * Characters that may appear in a handle *after* the '@'. Mirrors the capture group of
 * MentionService.MENTION — if this is looser than the server's pattern, the popup offers
 * completions that would not parse as a mention once sent.
 */
const HANDLE_CHAR = /[A-Za-z0-9_.-]/;
/**
 * What may sit immediately before the '@'. Mirrors the server pattern's anchor
 * `(?:^|(?<=[\s(\[]))`, which is what stops "foo@bar.com" being read as a mention of @bar.com.
 * Popping a member list inside an email address is the same bug wearing a UI.
 */
const BOUNDARY_BEFORE = /[\s([]/;
/** No handle is this long; a longer token means the user is typing prose, not a name. */
const MAX_TOKEN_LEN = 100;
/** Keys that move the caret out from under the popup's token. */
const CARET_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown']);

/**
 * Read the @-token the caret is currently inside, or null.
 *
 * @return {{at: number, end: number, query: string}|null} `at` is the index of the '@' itself,
 *   `end` the caret, `query` the text between them.
 */
export function activeMentionToken(value, caretStart, caretEnd) {
  if (typeof value !== 'string') return null;
  // A selection is not a caret: completing would silently delete whatever is selected.
  if (caretStart !== caretEnd) return null;
  let i = caretStart;
  while (i > 0 && HANDLE_CHAR.test(value[i - 1])) i--;
  if (i === 0 || value[i - 1] !== '@') return null;
  const at = i - 1;
  if (at > 0 && !BOUNDARY_BEFORE.test(value[at - 1])) return null;
  const query = value.slice(i, caretStart);
  if (query.length > MAX_TOKEN_LEN) return null;
  return { at, end: caretStart, query };
}

/** Local narrowing, matching the server's filter: case-insensitive substring on either field. */
function matches(item, query) {
  if (!query) return true;
  const q = query.toLowerCase();
  // Broadcasts are prefix-matched server-side, so narrow them the same way: "@ann" must not keep
  // offering @channel just because "channel" contains "ann".
  if (item.kind === 'broadcast') return (item.username || '').startsWith(q);
  return (item.username || '').toLowerCase().includes(q)
      || (item.displayName || '').toLowerCase().includes(q);
}

/**
 * What a broadcast row says it will do. The member count comes from the server; @here reports none,
 * because its audience is whoever is connected when Send is pressed and a number captured mid-typing
 * would already be wrong.
 */
function broadcastNote(item) {
  const n = item.notifyCount;
  switch (item.username) {
    case 'here':
      return 'Notifies members who are online';
    case 'everyone':
      return n ? 'Same as @channel · notifies ' + n + ' members' : 'Same as @channel';
    default:
      return n ? 'Notifies all ' + n + ' members' : 'Notifies every member';
  }
}

/**
 * Where a given composer posts to. Read from the DOM rather than passed in, so the boot module
 * does not have to know which page it is on: the channel page's .composer-wrap carries
 * data-channel-id, the DM page's carries data-conversation-id, and the thread composer sits
 * outside both and falls back to the page's active channel.
 */
function scopeFor(input) {
  const wrap = input.closest('[data-channel-id], [data-conversation-id]');
  if (wrap && wrap.dataset.channelId) return { channelId: wrap.dataset.channelId };
  if (wrap && wrap.dataset.conversationId) return { conversationId: wrap.dataset.conversationId };
  const channelId = meta('active-channel-id');
  return channelId ? { channelId } : null;
}

/** Avatar element matching the rest of the app; ChatKit owns the colour palette and the <img>. */
function avatarFor(item) {
  const letter = ((item.displayName || item.username || '?').trim()[0] || '?').toUpperCase();
  const kit = window.ChatKit && window.ChatKit.buildAvatarEl;
  if (kit) {
    const el = kit({
      username: item.username,
      hasAvatar: item.hasAvatar,
      avatarVersion: item.avatarVersion,
      letter,
    });
    // No data-author: that attribute is what the hovercard binds to, and a hovercard opening on
    // top of the dropdown you are picking from covers the rest of the list.
    el.removeAttribute('data-author');
    return el;
  }
  const span = document.createElement('span');
  span.className = 'avatar';
  const initial = document.createElement('span');
  initial.className = 'avatar-letter';
  initial.textContent = letter;
  span.appendChild(initial);
  return span;
}

/**
 * Stand-in for the avatar on a broadcast row. From the SVG sprite, not an emoji: the sprite icons
 * inherit currentColor and so follow the theme, which a glyph cannot (see AGENT.md).
 */
function broadcastIcon() {
  const wrap = document.createElement('span');
  wrap.className = 'mention-row-icon';
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'icon icon-sm');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#icon-users');
  svg.appendChild(use);
  wrap.appendChild(svg);
  return wrap;
}

/**
 * Wire one textarea. Safe to call for an element that does not exist (returns immediately) and
 * idempotent per element, so a page that boots twice does not end up with two popups.
 */
export function attachMentionAutocomplete(input) {
  if (!input || input.dataset.mentionAutocomplete === 'on') return;
  input.dataset.mentionAutocomplete = 'on';

  let panel = null;
  let items = [];
  let activeIndex = 0;
  let token = null;
  let debounce = null;
  let inflight = 0;
  // Last server response, kept so that extending the query narrows locally instead of asking
  // again: a full result set for "al" already contains every possible answer for "ali".
  let cache = null; // { scopeKey, query, items, truncated }

  const close = () => {
    clearTimeout(debounce);
    inflight++;
    if (panel) panel.remove();
    panel = null;
    items = [];
    token = null;
    activeIndex = 0;
    input.removeAttribute('aria-activedescendant');
  };

  const position = () => {
    if (!panel) return;
    const r = input.getBoundingClientRect();
    const w = panel.offsetWidth;
    const h = panel.offsetHeight;
    panel.style.left = Math.max(8, Math.min(r.left, window.innerWidth - w - 8)) + 'px';
    // Above the field by default: the composer sits at the bottom of the window, so a panel
    // hanging below it would open off-screen.
    const above = r.top - h - 6;
    panel.style.top = (above >= 8 ? above : Math.min(r.bottom + 6, window.innerHeight - h - 8)) + 'px';
  };

  const highlight = () => {
    if (!panel) return;
    const rows = panel.querySelectorAll('.mention-row');
    rows.forEach((row, i) => {
      const on = i === activeIndex;
      row.classList.toggle('active', on);
      row.setAttribute('aria-selected', on ? 'true' : 'false');
      if (on) {
        input.setAttribute('aria-activedescendant', row.id);
        row.scrollIntoView({ block: 'nearest' });
      }
    });
  };

  const complete = (item) => {
    // Re-read the token: the value may have changed between render and click.
    const current = activeMentionToken(input.value, input.selectionStart, input.selectionEnd) || token;
    if (!current) { close(); return; }
    const insert = '@' + item.username + ' ';
    const before = input.value.slice(0, current.at);
    const after = input.value.slice(current.end);
    input.value = before + insert + after;
    const caret = before.length + insert.length;
    input.setSelectionRange(caret, caret);
    close();
    input.focus();
    // Auto-resize and the live preview both listen for input events; a programmatic value
    // change does not fire one on its own.
    input.dispatchEvent(new Event('input', { bubbles: true }));
  };

  const render = (rows) => {
    const shown = rows.slice(0, SHOW_LIMIT);
    if (!shown.length) {
      // Nothing to offer: close rather than show an empty panel. An open-but-useless popup would
      // also be holding Enter hostage, and Enter has to send the message.
      close();
      return;
    }
    const keepIndex = Math.min(activeIndex, shown.length - 1);
    if (panel) panel.remove();
    items = shown;
    panel = document.createElement('div');
    panel.className = 'search-dropdown mention-dropdown';
    panel.setAttribute('role', 'listbox');
    panel.id = 'mention-dropdown-' + input.id;
    shown.forEach((item, i) => {
      const broadcast = item.kind === 'broadcast';
      if (broadcast && (i === 0 || shown[i - 1].kind !== 'broadcast')) {
        // One label above the broadcast group. A handle that notifies a roomful of people should
        // not sit in the list of individuals looking like one more name.
        const hint = document.createElement('div');
        hint.className = 'mention-dropdown-hint';
        hint.textContent = 'Notify a group';
        panel.appendChild(hint);
      }
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'search-dropdown-row mention-row';
      row.id = panel.id + '-' + i;
      row.setAttribute('role', 'option');
      row.appendChild(broadcast ? broadcastIcon() : avatarFor(item));
      const text = document.createElement('span');
      text.className = 'mention-row-text';
      const name = document.createElement('span');
      name.className = 'mention-row-name';
      name.textContent = broadcast ? '@' + item.username : (item.displayName || item.username);
      text.appendChild(name);
      if (!broadcast) {
        const handle = document.createElement('span');
        handle.className = 'mention-row-handle';
        handle.textContent = '@' + item.username;
        text.appendChild(handle);
      }
      row.appendChild(text);
      if (broadcast) {
        // The audience size, in front of the user while they are still choosing the handle. This is
        // the whole warning: Slack shows "this will notify 240 people" in a dialog after the fact,
        // and a number you read before typing is harder to dismiss by reflex.
        const note = document.createElement('span');
        note.className = 'mention-row-note';
        note.textContent = broadcastNote(item);
        row.appendChild(note);
      } else if (item.member === false) {
        // A public channel can be mentioned into by name even for someone who hasn't joined; they
        // do get notified, but they won't have the channel in their sidebar, and saying so here is
        // cheaper than the confusion later.
        const note = document.createElement('span');
        note.className = 'mention-row-note';
        note.textContent = 'not in channel';
        row.appendChild(note);
      }
      // mousedown, not click: click fires after blur, and blur closes the panel.
      row.addEventListener('mousedown', (ev) => {
        ev.preventDefault();
        complete(item);
      });
      row.addEventListener('mouseenter', () => { activeIndex = i; highlight(); });
      panel.appendChild(row);
    });
    document.body.appendChild(panel);
    activeIndex = keepIndex;
    position();
    highlight();
  };

  async function fetchCandidates(scope, query) {
    const params = new URLSearchParams(scope);
    params.set('q', query);
    params.set('limit', String(FETCH_LIMIT));
    const res = await fetch('/api/mention-candidates?' + params.toString(),
        { credentials: 'same-origin' });
    if (!res.ok) return null;
    const body = await res.json();
    return Array.isArray(body) ? body : null;
  }

  const refresh = () => {
    const next = activeMentionToken(input.value, input.selectionStart, input.selectionEnd);
    if (!next) { close(); return; }
    const scope = scopeFor(input);
    if (!scope) { close(); return; }
    const scopeKey = JSON.stringify(scope);
    token = next;

    // Narrow the cached page locally when the query only grew — the server already told us
    // everything that can match a shorter prefix, so this is a keystroke with no round-trip.
    if (cache && cache.scopeKey === scopeKey && !cache.truncated
        && next.query.toLowerCase().startsWith(cache.query)) {
      render(cache.items.filter((it) => matches(it, next.query)));
      return;
    }

    clearTimeout(debounce);
    const myReq = ++inflight;
    debounce = setTimeout(async () => {
      const rows = await fetchCandidates(scope, next.query);
      if (myReq !== inflight) return;    // superseded by a newer keystroke
      if (!rows) { close(); return; }
      cache = {
        scopeKey,
        query: next.query.toLowerCase(),
        items: rows,
        truncated: rows.length >= FETCH_LIMIT,
      };
      // The caret may have moved on while the request was in flight.
      const now = activeMentionToken(input.value, input.selectionStart, input.selectionEnd);
      if (!now) { close(); return; }
      token = now;
      render(rows.filter((it) => matches(it, now.query)));
    }, DEBOUNCE_MS);
  };

  input.addEventListener('input', (e) => {
    if (e.isComposing) return;
    refresh();
  });

  // Capture phase, on the document, rather than a listener on the textarea: the composers already
  // have their own Enter-to-send keydown handlers (a document-level one in chat/index.js for the
  // channel page, one bound straight to the textarea in conversation.js for the DM page). Two
  // listeners on the same element fire in registration order and stopPropagation does not stop a
  // sibling, so a textarea-level listener here would lose the race on the DM page — the message
  // would send while the popup was open. Capturing from the document runs strictly before both,
  // and stopPropagation then keeps the key from ever reaching them.
  document.addEventListener('keydown', (e) => {
    if (e.target !== input) return;
    if (!panel || !items.length) return;
    if (e.isComposing || e.keyCode === 229) return;
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        activeIndex = (activeIndex + 1) % items.length;
        highlight();
        break;
      case 'ArrowUp':
        e.preventDefault();
        activeIndex = (activeIndex - 1 + items.length) % items.length;
        highlight();
        break;
      case 'Enter':
      case 'Tab': {
        if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
        e.preventDefault();
        // Enter must not also reach the send handlers, and Tab must not move focus out of the
        // composer. With the popup closed neither key is touched, so both behave as before.
        e.stopPropagation();
        complete(items[activeIndex] || items[0]);
        break;
      }
      case 'Escape':
        e.preventDefault();
        // Escape closes the popup and nothing else — index.js also uses it to close the thread
        // panel, which would otherwise vanish from under the composer being typed into.
        e.stopPropagation();
        close();
        break;
      default:
        // Any key that moves the caret leaves the popup describing a token the caret is no longer
        // in, and Enter would then complete into the wrong place. Let the key through, drop the
        // popup; the next keystroke reopens it from wherever the caret ended up.
        if (CARET_KEYS.has(e.key)) close();
        break;
    }
  }, true);

  input.addEventListener('blur', close);
  input.addEventListener('click', close);
  document.addEventListener('mousedown', (e) => {
    if (!panel) return;
    if (panel.contains(e.target) || e.target === input) return;
    close();
  });
  window.addEventListener('resize', position);
  window.addEventListener('scroll', position, true);
}

/** Attach to every composer on the page that exists. */
export function initMentionAutocomplete(ids = ['composer-input', 'thread-input']) {
  ids.forEach((id) => attachMentionAutocomplete(document.getElementById(id)));
}
