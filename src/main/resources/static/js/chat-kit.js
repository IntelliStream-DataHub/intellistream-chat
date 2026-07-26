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
 * Shared composer / avatar / emoji helpers used by both chat.js (channels) and
 * conversation.js (DMs). Loaded as a plain <script> so it must run before
 * either page-script. Exposes everything on window.ChatKit; nothing is hoisted
 * into the global scope directly.
 */
(function () {
  if (window.ChatKit) return; // idempotent in case the script is loaded twice

  // ---------- Avatar palette ----------
  const hashCode = (str) => {
    let h = 0;
    for (let i = 0; i < str.length; i++) h = ((h << 5) - h) + str.charCodeAt(i) | 0;
    return h;
  };
  const avatarColor = (username) => Math.abs(hashCode(username || '')) % 8;

  /** Backfill data-color on every server-rendered .avatar[data-author] on the page. */
  const backfillAvatarColors = (root = document) => {
    root.querySelectorAll('.avatar[data-author]').forEach((el) => {
      if (!el.dataset.color) el.dataset.color = String(avatarColor(el.dataset.author));
    });
  };

  /**
   * Build an avatar <span> with the standard structure (optional <img>, fallback
   * letter). Used everywhere the chat creates avatars in JS-rendered DOM.
   */
  const buildAvatarEl = (opts) => {
    const span = document.createElement('span');
    span.className = 'avatar';
    span.dataset.author = opts.username || '';
    span.dataset.color = String(avatarColor(opts.username));
    if (opts.hasAvatar && opts.username) {
      const img = document.createElement('img');
      img.className = 'avatar-image';
      img.alt = '';
      const v = opts.avatarVersion ? '?v=' + opts.avatarVersion : '';
      img.src = '/api/users/' + encodeURIComponent(opts.username) + '/avatar' + v;
      img.addEventListener('error', () => img.remove());
      span.appendChild(img);
    }
    const letter = document.createElement('span');
    letter.className = 'avatar-letter';
    letter.textContent = opts.letter || '?';
    span.appendChild(letter);
    return span;
  };

  // ---------- Date / number formatters ----------
  const dayKey = (d) =>
      d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const formatTime = (d) => d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  const formatBytes = (n) => {
    if (n == null) return '';
    const units = ['B', 'KB', 'MB', 'GB'];
    let u = 0;
    let v = n;
    while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
    return (u === 0 ? v : v.toFixed(1)) + ' ' + units[u];
  };

  // ---------- Composer textarea: caret insert + auto-resize ----------
  const insertAtCursor = (ta, text) => {
    if (!ta) return;
    const start = ta.selectionStart ?? ta.value.length;
    const end = ta.selectionEnd ?? ta.value.length;
    ta.value = ta.value.slice(0, start) + text + ta.value.slice(end);
    const caret = start + text.length;
    ta.selectionStart = ta.selectionEnd = caret;
    ta.focus();
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  };

  /**
   * Slack/Mattermost-style auto-grow: textarea expands from its CSS min-height up
   * to {@code maxPx}, then scrolls. Resetting height to 'auto' before reading
   * scrollHeight avoids the runaway-growth bug after deletes.
   */
  const wireAutoResize = (ta, maxPx = 260) => {
    if (!ta) return;
    const resize = () => {
      ta.style.height = 'auto';
      const h = Math.min(ta.scrollHeight, maxPx);
      ta.style.height = h + 'px';
      ta.style.overflowY = ta.scrollHeight > maxPx ? 'auto' : 'hidden';
    };
    ta.addEventListener('input', resize);
    resize();
    requestAnimationFrame(resize);
    ta._autoResize = resize;
  };

  // ---------- Markdown formatting toolbar ----------
  const wrapSelection = (ta, before, after, placeholder) => {
    const start = ta.selectionStart, end = ta.selectionEnd;
    const sel = ta.value.slice(start, end);
    const text = sel || (placeholder || '');
    ta.value = ta.value.slice(0, start) + before + text + after + ta.value.slice(end);
    ta.selectionStart = start + before.length;
    ta.selectionEnd = start + before.length + text.length;
    ta.focus();
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  };

  const prefixLines = (ta, prefix) => {
    const start = ta.selectionStart, end = ta.selectionEnd;
    const lineStart = ta.value.lastIndexOf('\n', start - 1) + 1;
    const nextNl = ta.value.indexOf('\n', end);
    const lineEnd = nextNl === -1 ? ta.value.length : nextNl;
    const block = ta.value.slice(lineStart, lineEnd);
    const transformed = (block || '').split('\n').map((l) => prefix + l).join('\n');
    ta.value = ta.value.slice(0, lineStart) + transformed + ta.value.slice(lineEnd);
    ta.selectionStart = lineStart;
    ta.selectionEnd = lineStart + transformed.length;
    ta.focus();
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  };

  const applyFormat = (ta, kind) => {
    if (!ta) return;
    const start = ta.selectionStart, end = ta.selectionEnd;
    const sel = ta.value.slice(start, end);
    switch (kind) {
      case 'bold':   return wrapSelection(ta, '**', '**', 'bold');
      case 'italic': return wrapSelection(ta, '_',  '_',  'italic');
      case 'strike': return wrapSelection(ta, '~~', '~~', 'strikethrough');
      case 'link': {
        const text = sel || 'text';
        const before = '[' + text + '](';
        const url = 'https://';
        ta.value = ta.value.slice(0, start) + before + url + ')' + ta.value.slice(end);
        ta.selectionStart = start + before.length;
        ta.selectionEnd   = start + before.length + url.length;
        ta.focus();
        ta.dispatchEvent(new Event('input', { bubbles: true }));
        return;
      }
      case 'code':
        if (sel.includes('\n')) return wrapSelection(ta, '```\n', '\n```', 'code');
        return wrapSelection(ta, '`', '`', 'code');
      case 'quote': return prefixLines(ta, '> ');
      case 'list':  return prefixLines(ta, '- ');
      default: return;
    }
  };

  const wireFormatToolbar = (toolbar) => {
    if (!toolbar) return;
    const targetId = toolbar.dataset.formatTarget;
    const ta = targetId ? document.getElementById(targetId) : null;
    if (!ta) return;
    let pendingSelection = null;
    toolbar.addEventListener('mousedown', (e) => {
      const btn = e.target.closest('button[data-format]');
      if (!btn) return;
      e.preventDefault(); // keep textarea focused, don't take it for the button
      pendingSelection = { start: ta.selectionStart, end: ta.selectionEnd };
    });
    toolbar.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-format]');
      if (!btn) return;
      e.preventDefault();
      if (pendingSelection) {
        ta.focus();
        ta.selectionStart = pendingSelection.start;
        ta.selectionEnd = pendingSelection.end;
        pendingSelection = null;
      }
      applyFormat(ta, btn.dataset.format);
    });
    ta.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return;
      const map = { b: 'bold', i: 'italic', k: 'link', e: 'code' };
      const f = map[e.key.toLowerCase()];
      if (f) { e.preventDefault(); applyFormat(ta, f); }
    });
  };

  /** Wire every toolbar with data-format-target on the page. Idempotent — call once at startup. */
  const wireAllFormatToolbars = (root = document) => {
    root.querySelectorAll('.composer-toolbar[data-format-target]').forEach(wireFormatToolbar);
  };

  // ---------- Markdown live preview ----------
  /**
   * Wire {@code textarea} to a paired preview pane. The pane is the container that
   * shows/hides with the rendered output; {@code body} is the inner div whose innerHTML
   * we set. Server-rendered preview ({@code POST /api/preview}) so the result is
   * identical to the posted message. Also supplies a hook to reset on submit.
   */
  const wireLivePreview = ({ textarea, pane, body, form, headers, highlight }) => {
    if (!textarea || !pane || !body) return;
    let debounce = null;
    let req = 0;
    const refresh = async () => {
      const text = textarea.value;
      if (!text.trim()) {
        pane.hidden = true;
        body.innerHTML = '';
        return;
      }
      const myReq = ++req;
      try {
        const res = await fetch('/api/preview', {
          method: 'POST',
          headers: typeof headers === 'function' ? headers() : (headers || { 'Content-Type': 'application/json' }),
          body: JSON.stringify({ body: text }),
        });
        if (!res.ok) return;
        const data = await res.json();
        if (myReq !== req) return; // stale
        body.innerHTML = data.html || '';
        if (typeof highlight === 'function') highlight(body);
        pane.hidden = !data.html;
      } catch (_) { /* leave previous render */ }
    };
    textarea.addEventListener('input', () => {
      clearTimeout(debounce);
      debounce = setTimeout(refresh, 220);
    });
    if (form) {
      form.addEventListener('submit', () => {
        clearTimeout(debounce);
        pane.hidden = true;
        body.innerHTML = '';
      });
    }
    return { refresh };
  };

  // ---------- Emoji picker ----------
  const REACTION_PICKER_EMOJI = ['👍','👎','❤️','😂','🎉','🚀','👀','🙏','🔥','💯','✅','❌'];
  let emojiPickerEl = null;

  const closeEmojiPicker = () => {
    emojiPickerEl?.remove();
    emojiPickerEl = null;
  };
  const onPickerOutside = (e) => {
    if (!emojiPickerEl) {
      document.removeEventListener('mousedown', onPickerOutside, { capture: true });
      return;
    }
    if (emojiPickerEl.contains(e.target)) return;
    closeEmojiPicker();
    document.removeEventListener('mousedown', onPickerOutside, { capture: true });
  };

  /**
   * Categorised + searchable emoji picker. Data comes from {@code window.EMOJI_DATA}
   * (loaded by emoji-data.js). If that's missing we fall back to the small reaction-only
   * set. {@code anchor} is the button the popover floats above; {@code onPick(emoji)}
   * fires on selection.
   */
  const openEmojiPicker = (anchor, onPick) => {
    closeEmojiPicker();
    if (!anchor) return;
    const groups = (window.EMOJI_DATA && window.EMOJI_DATA.groups)
      ? window.EMOJI_DATA.groups
      : [{ name: 'Common', icon: '😀', emojis: REACTION_PICKER_EMOJI.map((c) => ({ c, n: c, k: [] })) }];

    const picker = document.createElement('div');
    picker.className = 'emoji-picker';
    picker.setAttribute('role', 'dialog');

    const searchWrap = document.createElement('div');
    searchWrap.className = 'emoji-picker-search';
    const search = document.createElement('input');
    search.type = 'search';
    search.placeholder = 'Search emoji…';
    search.className = 'emoji-picker-search-input';
    search.autocomplete = 'off';
    searchWrap.appendChild(search);
    picker.appendChild(searchWrap);

    const tabs = document.createElement('div');
    tabs.className = 'emoji-picker-tabs';
    groups.forEach((g, i) => {
      const tab = document.createElement('button');
      tab.type = 'button';
      tab.className = 'emoji-picker-tab' + (i === 0 ? ' active' : '');
      tab.dataset.group = String(i);
      tab.textContent = g.icon;
      tab.title = g.name;
      tabs.appendChild(tab);
    });
    picker.appendChild(tabs);

    const results = document.createElement('div');
    results.className = 'emoji-picker-results';
    picker.appendChild(results);

    const buildEmojiBtn = (e) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'emoji-picker-btn';
      b.textContent = e.c;
      b.title = e.n;
      b.addEventListener('click', () => { closeEmojiPicker(); onPick(e.c); });
      return b;
    };
    const matches = (emoji, lower) => {
      if (emoji.n && emoji.n.toLowerCase().includes(lower)) return true;
      if (emoji.k) for (const k of emoji.k) if (k.toLowerCase().includes(lower)) return true;
      return false;
    };
    const renderGroups = () => {
      results.innerHTML = '';
      groups.forEach((g, i) => {
        const section = document.createElement('section');
        section.className = 'emoji-picker-section';
        section.dataset.group = String(i);
        const h = document.createElement('h4');
        h.textContent = g.name;
        section.appendChild(h);
        const grid = document.createElement('div');
        grid.className = 'emoji-picker-grid';
        for (const e of g.emojis) grid.appendChild(buildEmojiBtn(e));
        section.appendChild(grid);
        results.appendChild(section);
      });
    };
    const renderSearch = (q) => {
      results.innerHTML = '';
      const lower = q.toLowerCase();
      const grid = document.createElement('div');
      grid.className = 'emoji-picker-grid';
      let n = 0;
      outer: for (const g of groups) {
        for (const e of g.emojis) {
          if (matches(e, lower)) {
            grid.appendChild(buildEmojiBtn(e));
            if (++n >= 200) break outer;
          }
        }
      }
      if (n === 0) {
        const empty = document.createElement('div');
        empty.className = 'emoji-picker-empty';
        empty.textContent = 'No emoji found.';
        results.appendChild(empty);
      } else {
        results.appendChild(grid);
      }
    };

    tabs.addEventListener('click', (ev) => {
      const tab = ev.target.closest('.emoji-picker-tab');
      if (!tab) return;
      const idx = tab.dataset.group;
      tabs.querySelectorAll('.emoji-picker-tab').forEach((t) => t.classList.toggle('active', t === tab));
      const section = results.querySelector('.emoji-picker-section[data-group="' + idx + '"]');
      if (section) section.scrollIntoView({ behavior: 'auto', block: 'start' });
    });
    results.addEventListener('scroll', () => {
      const sections = results.querySelectorAll('.emoji-picker-section');
      const top = results.scrollTop;
      let active = sections[0];
      sections.forEach((s) => { if (s.offsetTop - 20 <= top) active = s; });
      const idx = active?.dataset.group;
      tabs.querySelectorAll('.emoji-picker-tab').forEach((t) => {
        t.classList.toggle('active', t.dataset.group === idx);
      });
    }, { passive: true });

    let debounce;
    search.addEventListener('input', () => {
      clearTimeout(debounce);
      const q = search.value.trim();
      debounce = setTimeout(() => {
        if (q.length === 0) { tabs.style.display = ''; renderGroups(); }
        else { tabs.style.display = 'none'; renderSearch(q); }
      }, 80);
    });
    search.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') { ev.preventDefault(); closeEmojiPicker(); }
    });

    renderGroups();
    document.body.appendChild(picker);
    const rect = anchor.getBoundingClientRect();
    picker.style.position = 'fixed';
    const desiredTop = rect.top - picker.offsetHeight - 6;
    picker.style.top = Math.max(8, desiredTop) + 'px';
    const desiredLeft = rect.left - picker.offsetWidth + rect.width;
    picker.style.left = Math.max(8, Math.min(desiredLeft, window.innerWidth - picker.offsetWidth - 8)) + 'px';
    emojiPickerEl = picker;
    // Autofocus the search on desktop only — on touch devices it would pop the
    // software keyboard over the picker the moment it opens.
    if (!touchOnly.matches) setTimeout(() => search.focus());
    setTimeout(() => document.addEventListener('mousedown', onPickerOutside, { capture: true }));
  };

  // ---------- Author handle (@username next to display name) ----------
  /**
   * Append the @handle next to an author's display name. Skipped when the user has
   * no distinct display name (so we don't render the redundant "alice @alice").
   */
  const appendAuthorHandle = (metaEl, displayName, username) => {
    if (!username) return;
    if (!displayName || displayName === username) return;
    const handle = document.createElement('span');
    handle.className = 'author-handle';
    handle.dataset.author = username;
    handle.textContent = '@' + username;
    metaEl.append(handle);
  };

  // ---------- Touch long-press → message action sheet ----------
  // Touch devices have no hover, so message actions are reached Slack-style:
  // press and hold for ~500ms (the platform long-press convention — ~400ms
  // Android, ~500ms iOS) and a bottom sheet slides up with a quick-reaction
  // emoji strip plus one large row per action. The rows are built from the
  // message's own hidden .message-actions buttons, so whatever actions a page
  // grants for that message (edit/delete/reply/…) appear with no duplicated
  // logic — tapping a row forwards the click to the original button. Pointer
  // Events carry the input type, so mouse and pen keep CSS :hover behaviour.
  const LONG_PRESS_MS = 500;
  const LONG_PRESS_SLOP_PX = 10; // finger drift beyond this is a scroll, not a press
  const touchOnly = window.matchMedia('(hover: none) and (pointer: coarse)');

  // Pages register their reaction toggler (channels and DMs hit different
  // endpoints); the sheet's emoji strip is hidden until one is registered.
  let quickReactionFn = null;
  const setQuickReaction = (fn) => { quickReactionFn = fn; };

  let sheetEl = null;
  let sheetBackdropEl = null;
  let sheetCloseTimer = null;

  const closeMessageSheet = () => {
    if (!sheetEl || sheetEl.hidden) return;
    sheetEl.classList.remove('open');
    sheetBackdropEl.classList.remove('open');
    clearTimeout(sheetCloseTimer);
    // Keep display until the slide-down transition ends.
    sheetCloseTimer = setTimeout(() => {
      sheetEl.hidden = true;
      sheetBackdropEl.hidden = true;
    }, 240);
  };

  const ensureSheet = () => {
    if (sheetEl) return;
    sheetBackdropEl = document.createElement('div');
    sheetBackdropEl.className = 'action-sheet-backdrop';
    sheetBackdropEl.hidden = true;
    sheetEl = document.createElement('div');
    sheetEl.className = 'action-sheet';
    sheetEl.hidden = true;
    sheetEl.setAttribute('role', 'dialog');
    sheetEl.setAttribute('aria-modal', 'true');
    sheetEl.setAttribute('aria-label', 'Message actions');
    document.body.append(sheetBackdropEl, sheetEl);
    sheetBackdropEl.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      closeMessageSheet();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeMessageSheet();
    });
  };

  const openMessageSheet = (li) => {
    ensureSheet();
    sheetEl.textContent = '';
    const handle = document.createElement('div');
    handle.className = 'action-sheet-handle';
    sheetEl.append(handle);

    // Quick-reaction strip — only when this message is reactable (the page adds a react
    // button for anything the viewer may react to, own messages included) and a toggler
    // is registered. Still a presence test rather than an assumption: a page that renders
    // messages without a react action gets a sheet without an emoji strip.
    if (quickReactionFn && li.dataset.id
        && li.querySelector('.msg-action[data-action="react"]')) {
      const strip = document.createElement('div');
      strip.className = 'action-sheet-reactions';
      for (const emoji of REACTION_PICKER_EMOJI) {
        const mine = !!li.querySelector(
            '.reaction[data-emoji="' + emoji + '"][data-mine="true"]');
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'action-sheet-reaction' + (mine ? ' mine' : '');
        btn.textContent = emoji;
        btn.title = (mine ? 'Remove ' : 'React with ') + emoji;
        btn.addEventListener('click', () => {
          closeMessageSheet();
          quickReactionFn(li.dataset.id, emoji, mine);
        });
        strip.append(btn);
      }
      sheetEl.append(strip);
    }

    const list = document.createElement('div');
    list.className = 'action-sheet-items';
    // Every action, including the ones a pointer device hides behind the ⋯ overflow — the sheet is
    // full-width labelled rows and has the room the hover strip does not. The ⋯ itself is excluded:
    // a list that already contains everything has no "more" to offer.
    li.querySelectorAll('.msg-action:not([data-action="react"]):not([data-action="more"])')
        .forEach((orig) => {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'action-sheet-item';
      row.dataset.action = orig.dataset.action || '';
      const icon = document.createElement('span');
      icon.className = 'action-sheet-icon';
      // The action buttons hold an <svg><use/></svg>, so textContent is empty — clone the node.
      // The textContent branch is the fallback for any caller still rendering a glyph.
      const svg = orig.querySelector('svg');
      if (svg) icon.append(svg.cloneNode(true));
      else icon.textContent = orig.textContent;
      const label = document.createElement('span');
      // Falls back to textContent only for a glyph button; every SVG one sets a title.
      label.textContent = orig.title || orig.textContent;
      row.append(icon, label);
      row.addEventListener('click', () => {
        closeMessageSheet();
        orig.click(); // the page's existing delegated handler does the rest
      });
      list.append(row);
    });
    sheetEl.append(list);
    if (!list.children.length && sheetEl.children.length < 3) return; // nothing to offer

    clearTimeout(sheetCloseTimer);
    sheetEl.hidden = false;
    sheetBackdropEl.hidden = false;
    requestAnimationFrame(() => {
      sheetEl.classList.add('open');
      sheetBackdropEl.classList.add('open');
    });
  };

  // Releasing a long-press can still synthesize a click on whatever is under the
  // finger (worst case: a link in the message body navigates away). Swallow the
  // first click after the sheet opens unless it lands on the sheet itself.
  const swallowNextClick = () => {
    const swallow = (e) => {
      document.removeEventListener('click', swallow, true);
      if (!(e.target instanceof Element) || !e.target.closest('.action-sheet')) {
        e.preventDefault();
        e.stopPropagation();
      }
    };
    document.addEventListener('click', swallow, true);
    setTimeout(() => document.removeEventListener('click', swallow, true), 600);
  };

  (function initLongPressActions() {
    let timer = null;
    let startX = 0;
    let startY = 0;
    const cancel = () => {
      clearTimeout(timer);
      timer = null;
    };
    document.addEventListener('pointerdown', (e) => {
      if (e.pointerType !== 'touch' || !touchOnly.matches) return;
      if (!(e.target instanceof Element)) return;
      const msg = e.target.closest('.message');
      if (!msg || !msg.querySelector('.message-actions')) return;
      startX = e.clientX;
      startY = e.clientY;
      timer = setTimeout(() => {
        timer = null;
        openMessageSheet(msg);
        swallowNextClick();
        navigator.vibrate?.(10); // subtle haptic on Android; no-op elsewhere
      }, LONG_PRESS_MS);
    });
    document.addEventListener('pointermove', (e) => {
      if (timer !== null
          && Math.hypot(e.clientX - startX, e.clientY - startY) > LONG_PRESS_SLOP_PX) {
        cancel();
      }
    });
    document.addEventListener('pointerup', cancel);
    document.addEventListener('pointercancel', cancel); // browser took over (scroll)
    // Android fires contextmenu (the text-selection sheet) at its own long-press
    // threshold; suppress it inside messages so it doesn't fight the action sheet.
    // Desktop right-click is unaffected because touchOnly never matches there.
    document.addEventListener('contextmenu', (e) => {
      if (touchOnly.matches && e.target instanceof Element && e.target.closest('.message')) {
        e.preventDefault();
      }
    });
  })();

  // ---------- Removed attachment ----------
  // A file deleted from the file manager leaves its message standing, so the message has to say
  // what happened. Rendered as plain text, not a link: the bytes are gone, and a download that
  // 404s is worse than no download. Named and dated because "a file used to be here" invites the
  // question this answers.
  const formatRemovedAt = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d) ? '' : d.toLocaleString([], {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    });
  };

  const buildRemovedAttachmentEl = (a) => {
    const el = document.createElement('span');
    el.className = 'attachment attachment-removed';
    el.innerHTML =
        '<svg class="icon attachment-icon" aria-hidden="true"><use href="#icon-paperclip"/></svg>' +
        '<span class="attachment-info">' +
          '<span class="attachment-name"></span>' +
          '<span class="attachment-meta"></span>' +
        '</span>';
    el.querySelector('.attachment-name').textContent = a.filename || 'File';
    const when = formatRemovedAt(a.deletedAt);
    el.querySelector('.attachment-meta').textContent =
        'Deleted' + (when ? ' ' + when : '') + (a.deletedBy ? ' by ' + a.deletedBy : '');
    el.title = el.querySelector('.attachment-meta').textContent;
    return el;
  };

  // ---------- Image lightbox ----------
  // Clicking an image attachment opens it in place, with download / open-in-tab / close, rather
  // than navigating away. Shared because both pages have image attachments and only one of them
  // had this: the conversation page opened a new browser tab instead, which is a different
  // product decision made by accident, in a copy nobody compared.
  //
  // Idempotent — the channel page calls it once and so does the conversation page, and a second
  // call must not attach a second delegate.
  let lightboxWired = false;
  const wireImageLightbox = () => {
    if (lightboxWired) return;
    lightboxWired = true;
  // One delegate covers both server-rendered messages (Thymeleaf in channels.html) and
  // JS-rendered ones; otherwise the historical-message links would just download via href.
  document.addEventListener('click', (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const link = e.target.closest('a.attachment-image');
    if (!link) return;
    e.preventDefault();
    const img = link.querySelector('img');
    openLightbox(link.getAttribute('href'), img?.alt || link.title || '');
  });

  let lightboxEl = null;
  const ensureLightbox = () => {
    if (lightboxEl) return lightboxEl;
    lightboxEl = document.createElement('div');
    lightboxEl.className = 'lightbox';
    lightboxEl.hidden = true;
    lightboxEl.innerHTML =
        '<div class="lightbox-toolbar">' +
          '<a class="lightbox-btn" data-action="download" title="Download" aria-label="Download">' +
            '<svg class="icon"><use href="#icon-download"/></svg>' +
          '</a>' +
          '<a class="lightbox-btn" data-action="open" target="_blank" rel="noopener" title="Open in new tab" aria-label="Open in new tab">' +
            '<svg class="icon"><use href="#icon-external"/></svg>' +
          '</a>' +
          '<button type="button" class="lightbox-btn" data-action="close" title="Close (Esc)" aria-label="Close">' +
            '<svg class="icon"><use href="#icon-close"/></svg>' +
          '</button>' +
        '</div>' +
        '<img class="lightbox-img" alt=""/>';
    document.body.appendChild(lightboxEl);
    lightboxEl.addEventListener('click', (e) => {
      if (e.target === lightboxEl) closeLightbox();
    });
    lightboxEl.querySelector('[data-action="close"]').addEventListener('click', closeLightbox);
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && lightboxEl && !lightboxEl.hidden) closeLightbox();
    });
    return lightboxEl;
  };
  const openLightbox = (url, filename) => {
    const el = ensureLightbox();
    el.querySelector('.lightbox-img').src = url;
    el.querySelector('.lightbox-img').alt = filename || '';
    const dl = el.querySelector('[data-action="download"]');
    dl.href = url;
    dl.setAttribute('download', filename || '');
    // The download endpoint returns Content-Disposition: attachment by default, which would
    // trigger a download instead of rendering in the new tab. Ask for inline disposition here.
    const sep = url.indexOf('?') === -1 ? '?' : '&';
    el.querySelector('[data-action="open"]').href = url + sep + 'disposition=inline';
    el.hidden = false;
    document.body.classList.add('lightbox-open');
  };
  const closeLightbox = () => {
    if (!lightboxEl) return;
    lightboxEl.hidden = true;
    lightboxEl.querySelector('.lightbox-img').src = '';
    document.body.classList.remove('lightbox-open');
  };
  };

  // ---------- Thread indicator ----------
  // The "N replies" widget that hangs off the bottom of a message that started a thread. Pure DOM
  // and identical on both pages, so it lives here rather than in each page script: the channel page
  // has its own copy today and this is the shape both should converge on.
  const replyLabel = (n) => n + ' ' + (n === 1 ? 'reply' : 'replies');

  const buildThreadIndicator = (count) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'thread-indicator';
    btn.dataset.count = String(count);
    btn.title = 'Open thread (' + replyLabel(count) + ')';
    btn.innerHTML = '<svg class="icon thread-indicator-icon" aria-hidden="true">'
        + '<use href="#icon-thread"/></svg><span class="thread-indicator-count"></span>';
    btn.querySelector('.thread-indicator-count').textContent = replyLabel(count);
    return btn;
  };

  /**
   * Move a message's reply count by {@code delta}, creating the indicator on the first reply and
   * removing it when the last one goes. {@code container} is the element the indicator belongs to —
   * the message's content column — because that is what differs between the two pages' markup.
   */
  const applyThreadIndicator = (container, delta) => {
    if (!container) return;
    let btn = container.querySelector(':scope > .thread-indicator');
    const count = Math.max(0, Number(btn?.dataset.count || 0) + delta);
    if (count === 0) {
      btn?.remove();
      return;
    }
    if (!btn) {
      btn = buildThreadIndicator(count);
      container.appendChild(btn);
      return;
    }
    btn.dataset.count = String(count);
    btn.querySelector('.thread-indicator-count').textContent = replyLabel(count);
    btn.title = 'Open thread (' + replyLabel(count) + ')';
  };

  // ---------- Thread panel ----------
  /**
   * The right-hand thread panel, as a controller over markup the page supplies.
   *
   * Everything in here is page-agnostic: opening and closing, the stale-response guard, Escape,
   * the reply composer with its auto-resize and server-rendered preview. What differs between a
   * channel thread and a conversation thread is only *where the messages come from* and *how a
   * message is drawn*, so those are the two callbacks — {@code loadThread} / {@code postReply} and
   * {@code renderMessage}.
   *
   * It lives here rather than in conversation.js because the channel page has the same panel, built
   * the same way, and a second copy is how the two would drift; the channel page is not switched
   * over in this change, deliberately, but this is the seam it can move to when it is.
   *
   * @param opts.ids            element ids: panel, parent, replies, form, input, emoji, close,
   *                            preview, previewBody
   * @param opts.loadThread     async (parentId) -> { parent, replies }
   * @param opts.postReply      async (parentId, body) -> message | null
   * @param opts.renderMessage  (message, isParent) -> HTMLLIElement
   * @param opts.headers        () -> fetch headers, for the preview call
   * @param opts.onError        (message) -> void; defaults to alert
   * @returns {{open: function, close: function, appendReply: function, openId: function}}
   */
  const createThreadPanel = (opts) => {
    const ids = opts.ids || {};
    const el = (id) => (id ? document.getElementById(id) : null);
    const panel = el(ids.panel || 'thread-panel');
    const parentEl = el(ids.parent || 'thread-parent');
    const repliesEl = el(ids.replies || 'thread-replies');
    const form = el(ids.form || 'thread-composer');
    const input = el(ids.input || 'thread-input');
    const emojiBtn = el(ids.emoji || 'thread-emoji');
    const closeBtn = el(ids.close || 'thread-close');
    const fail = opts.onError || ((m) => alert(m));
    if (!panel || !parentEl || !repliesEl) return null;

    let openId = null;
    let req = 0;

    const close = () => {
      // Bump the request id so a response still in flight can't reopen a panel the user closed.
      req++;
      panel.hidden = true;
      document.body.classList.remove('thread-open');
      openId = null;
      parentEl.innerHTML = '';
      repliesEl.innerHTML = '';
      if (input) {
        input.value = '';
        input._autoResize?.();
      }
    };

    const open = async (parentId) => {
      const myReq = ++req;
      let data;
      try {
        data = await opts.loadThread(parentId);
      } catch (e) {
        fail('Could not load thread');
        return;
      }
      if (myReq !== req || !data) return; // superseded by a newer open, or closed
      openId = parentId;
      parentEl.innerHTML = '';
      repliesEl.innerHTML = '';
      parentEl.appendChild(opts.renderMessage(data.parent, true));
      for (const r of data.replies || []) repliesEl.appendChild(opts.renderMessage(r, false));
      panel.hidden = false;
      document.body.classList.add('thread-open');
      input?.focus();
    };

    /** Add a reply to the open thread. A no-op unless it belongs to the thread on screen. */
    const appendReply = (msg) => {
      if (!msg || panel.hidden) return;
      if (String(msg.parentId) !== String(openId)) return;
      // De-dupe: the sender renders from the HTTP response and the broadcast follows.
      if (repliesEl.querySelector('[data-id="' + CSS.escape(String(msg.id)) + '"]')) return;
      repliesEl.appendChild(opts.renderMessage(msg, false));
      repliesEl.scrollTop = repliesEl.scrollHeight;
    };

    closeBtn?.addEventListener('click', close);
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && !panel.hidden) close();
    });
    emojiBtn?.addEventListener('click', () => {
      openEmojiPicker(emojiBtn, (e) => insertAtCursor(input, e));
    });

    form?.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (openId === null) return;
      const body = (input.value || '').trim();
      if (!body) return;
      let dto;
      try {
        dto = await opts.postReply(openId, body);
      } catch (err) {
        fail('Reply failed: ' + (err?.message || err));
        return;
      }
      // Render from the HTTP response so the sender sees it immediately, independent of the
      // WebSocket round trip; appendReply de-dupes so the broadcast that follows is a no-op.
      if (dto) appendReply(dto);
      input.value = '';
      input._autoResize?.();
    });
    if (input) {
      wireAutoResize(input);
      input.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
        if (e.isComposing || e.keyCode === 229) return;
        e.preventDefault();
        form?.requestSubmit();
      });
      wireLivePreview({
        textarea: input,
        pane: el(ids.preview || 'thread-preview'),
        body: el(ids.previewBody || 'thread-preview-body'),
        form,
        headers: opts.headers,
      });
    }

    return { open, close, appendReply, openId: () => openId };
  };

  // ---------- Popover ----------
  // Anchored dialog hung off a button, for occasional actions that would otherwise sit in the
  // sidebar flow pushing the lists down and competing with them for attention.
  //
  // A popover has obligations an inline <details> block doesn't: it has to close on Escape and
  // on a click elsewhere, or it strands the user with a floating panel and no obvious way out;
  // and focus has to move into it on open and back to the button on close, or a keyboard user
  // tabs into a form they can't see and never gets back.
  //
  // Lives here rather than in a page script because both the channel and conversation pages
  // need it, and a second copy is how the two drift apart.
  const wirePopover = (buttonId, popoverId, firstFieldSelector) => {
    const button = document.getElementById(buttonId);
    const popover = document.getElementById(popoverId);
    if (!button || !popover) return null;

    const isOpen = () => !popover.hidden;
    const close = ({ refocus = true } = {}) => {
      if (!isOpen()) return;
      popover.hidden = true;
      button.setAttribute('aria-expanded', 'false');
      if (refocus) button.focus();
    };
    const open = () => {
      popover.hidden = false;
      button.setAttribute('aria-expanded', 'true');
      popover.querySelector(firstFieldSelector)?.focus();
    };

    button.addEventListener('click', (e) => {
      e.stopPropagation();   // don't let the outside-click handler immediately re-close it
      isOpen() ? close() : open();
    });
    popover.addEventListener('click', (e) => e.stopPropagation());
    document.addEventListener('click', () => close({ refocus: false }));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && isOpen()) close();
    });
    return { open, close, isOpen };
  };

  // ---------- New conversation (direct or group) ----------
  // One popover for both, because from the user's side it is one intent: message these people.
  // The split into two endpoints is ours, not theirs — one recipient is a direct message, more
  // than one is a group. The name field only appears once it is a group, since that is the only
  // case that needs one, and asking up front for a title most conversations never use is what
  // made the old inline form feel like a chore.
  //
  // Wired here rather than in each page script because the channel and conversation pages carry
  // the identical markup; two copies is how they drift.
  const wireNewConversation = () => {
    const form = document.getElementById('new-conversation-form');
    if (!form) return;
    const popover = wirePopover('sidebar-dm-btn', 'sidebar-dm-popover', 'input[name="members"]');

    const membersInput = form.querySelector('input[name="members"]');
    const titleLabel = form.querySelector('.new-conversation-title');
    const titleInput = form.querySelector('input[name="title"]');
    const hint = form.querySelector('#new-conversation-hint');
    const submit = form.querySelector('#new-conversation-submit');

    const names = () => (membersInput.value || '')
        .split(/[,\s]+/).map((s) => s.trim()).filter(Boolean);

    const syncMode = () => {
      const isGroup = names().length > 1;
      titleLabel.hidden = !isGroup;
      submit.textContent = isGroup ? 'Create group' : 'Start';
      hint.textContent = isGroup
          ? 'A group needs a name.'
          : 'One name starts a direct message. Add more to make a group.';
    };
    membersInput.addEventListener('input', syncMode);

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    const headers = () => {
      const h = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
      if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
      return h;
    };

    const fail = (msg) => {
      hint.textContent = msg;
      hint.classList.add('form-hint-error');
      submit.disabled = false;
    };

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      hint.classList.remove('form-hint-error');
      const members = names();
      if (!members.length) return;

      const isGroup = members.length > 1;
      const title = (titleInput.value || '').trim();
      if (isGroup && !title) { fail('A group needs a name.'); titleInput.focus(); return; }

      submit.disabled = true;
      try {
        const res = isGroup
            ? await fetch('/api/conversations/group', {
                method: 'POST', headers: headers(),
                body: JSON.stringify({ title, members }),
              })
            : await fetch('/api/conversations/direct', {
                method: 'POST', headers: headers(),
                body: JSON.stringify({ username: members[0] }),
              });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          // The server reports unresolved names generically on purpose, so it can't be used
          // as a username-existence oracle. Pass its wording through rather than inventing one.
          fail(err.message || err.error || 'Could not start that conversation.');
          return;
        }
        const dto = await res.json();
        popover?.close({ refocus: false });
        window.location.href = '/conversations/' + dto.id;
      } catch (err) {
        fail('Could not start that conversation.');
      }
    });
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wireNewConversation);
  } else {
    wireNewConversation();
  }

  // ---------- Public surface ----------
  window.ChatKit = {
    wirePopover,
    buildThreadIndicator,
    applyThreadIndicator,
    createThreadPanel,
    wireImageLightbox,
    buildRemovedAttachmentEl,
    hashCode,
    avatarColor,
    backfillAvatarColors,
    buildAvatarEl,
    dayKey,
    formatTime,
    formatBytes,
    insertAtCursor,
    applyFormat,
    wireFormatToolbar,
    wireAllFormatToolbars,
    wireAutoResize,
    wireLivePreview,
    openEmojiPicker,
    closeEmojiPicker,
    REACTION_PICKER_EMOJI,
    appendAuthorHandle,
    setQuickReaction,
  };
})();
