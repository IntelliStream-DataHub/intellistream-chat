/*
 * Copyright 2026 Olav Gjerde
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
    setTimeout(() => search.focus());
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

  // ---------- Public surface ----------
  window.ChatKit = {
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
  };
})();
