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
 * Live message-search box: debounced query, keyboard-navigable dropdown, clear button.
 *
 * Lives in its own module because both the channel page and the direct-message page carry one.
 * It used to be a closure inside chat/index.js, which is why the DM page had no message search
 * at all — the behaviour was reachable from exactly one page, and the fix was either to copy it
 * or to move it. Copies of this much logic drift.
 *
 * Results come back as SearchHitDto: a discriminated `scope` plus a precomputed `url`, so this
 * never has to know how to build a link to a channel message versus a conversation message.
 */

/**
 * @param inputId      id of the <input type="search">
 * @param opts.scopeChannelIdFn  () => channelId, to scope the query to one channel; null = global
 * @param opts.anchorRight       hang the panel off the window's right margin instead of the
 *                               field's left edge. True for narrow fields sitting at the right of
 *                               a header, where left-anchoring runs the panel off-screen.
 */
export function initSearchBox(inputId, opts = {}) {
  const input = document.getElementById(inputId);
  if (!input) return;

  const scopeChannelIdFn = opts.scopeChannelIdFn || null;
  const anchorRight = !!opts.anchorRight;

  // channelId → channel name, read from the sidebar so a row can say "#general" without a second
  // round-trip. Only a hint: the server also sends channelName, which is the fallback for a
  // channel that isn't in this page's shortlist.
  const channelNames = new Map();
  document.querySelectorAll('#sidebar-channel-list li, .sidebar .channel-list > li')
      .forEach((li) => {
        const a = li.querySelector('a');
        if (!a) return;
        const id = (a.getAttribute('href') || '').split('/').pop();
        const spans = a.querySelectorAll('span');
        const name = spans[1]?.textContent.trim();
        if (id && name && !channelNames.has(id)) channelNames.set(id, name);
      });

  let dropdown = null;
  let debounce = null;
  let activeIndex = -1;
  let inflight = 0; // monotonic request id — drop stale responses

  const close = () => {
    // Cancel a pending debounced search and invalidate any in-flight response, so a query
    // scheduled just before Escape / outside-click can't fire and reopen the dropdown.
    clearTimeout(debounce);
    inflight++;
    dropdown?.remove();
    dropdown = null;
    activeIndex = -1;
  };

  const position = () => {
    if (!dropdown) return;
    const r = input.getBoundingClientRect();
    dropdown.style.top = (r.bottom + 4) + 'px';
    if (anchorRight) {
      dropdown.style.right = '10px';
      dropdown.style.left = 'auto';
    } else {
      dropdown.style.left = r.left + 'px';
      dropdown.style.right = 'auto';
    }
    dropdown.style.minWidth = Math.max(r.width, 320) + 'px';
  };

  const highlight = () => {
    if (!dropdown) return;
    const rows = dropdown.querySelectorAll('.search-dropdown-row');
    rows.forEach((row, i) => row.classList.toggle('active', i === activeIndex));
    if (activeIndex >= 0) rows[activeIndex].scrollIntoView({ block: 'nearest' });
  };

  const navigate = (url) => {
    close();
    window.location.href = url;
  };

  // ---------- Clear button ----------
  // Sits inside the field rather than beside it: it belongs to the text, and a control outside
  // the box reads as a separate action on the page. Hidden while the field is empty, because a
  // clear button with nothing to clear is a target that does nothing.
  const clearBtn = input.parentElement?.querySelector('.search-clear') || null;
  const syncClear = () => {
    if (clearBtn) clearBtn.hidden = input.value.length === 0;
  };
  clearBtn?.addEventListener('click', () => {
    input.value = '';
    syncClear();
    close();
    input.focus();
    // Let anything else watching the field (the sidebar channel filter shares this shape)
    // react to the box becoming empty.
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  syncClear();

  const render = (items) => {
    close();
    dropdown = document.createElement('div');
    dropdown.className = 'search-dropdown';
    if (!items.length) {
      const empty = document.createElement('div');
      empty.className = 'search-dropdown-empty';
      empty.textContent = 'No matches.';
      dropdown.appendChild(empty);
    } else {
      items.forEach((m, i) => {
        const row = document.createElement('button');
        row.type = 'button';
        row.className = 'search-dropdown-row';
        // The server precomputes the link, because a hit is either a channel message or a
        // conversation message and only it knows which.
        row.dataset.url = m.url;
        row.dataset.index = String(i);
        const label = m.scope === 'conversation'
            ? (m.conversationTitle || (m.conversationType === 'DIRECT' ? 'Direct message' : 'Group'))
            : (() => {
                const n = channelNames.get(String(m.channelId)) || m.channelName;
                return n ? '#' + n : '';
              })();
        row.innerHTML =
            '<div class="search-dropdown-meta">' +
              '<span class="search-dropdown-author"></span>' +
              '<span class="search-dropdown-channel"></span>' +
              '<time class="search-dropdown-time"></time>' +
            '</div>' +
            '<div class="search-dropdown-snippet"></div>';
        row.querySelector('.search-dropdown-author').textContent =
            m.authorDisplayName || m.authorUsername;
        row.querySelector('.search-dropdown-channel').textContent = label;
        row.querySelector('.search-dropdown-time').textContent =
            new Date(m.createdAt).toLocaleString();
        // bodySnippet is the Lucene-highlighted excerpt with <mark>-wrapped match terms
        // (HTML-escaped before highlighting, so innerHTML is safe). Falls back to bodyHtml —
        // the server-rendered, jsoup-sanitized body — when there is no snippet.
        row.querySelector('.search-dropdown-snippet').innerHTML = m.bodySnippet || m.bodyHtml || '';
        // mousedown so the input doesn't blur (and close us) before the click fires.
        row.addEventListener('mousedown', (ev) => {
          ev.preventDefault();
          navigate(row.dataset.url);
        });
        row.addEventListener('mouseenter', () => { activeIndex = i; highlight(); });
        dropdown.appendChild(row);
      });
    }
    document.body.appendChild(dropdown);
    position();
    activeIndex = -1;
  };

  async function fetchResults(q) {
    const params = new URLSearchParams({ q });
    const cid = scopeChannelIdFn ? scopeChannelIdFn() : null;
    if (cid) params.set('channelId', cid);
    params.set('limit', '10');
    const res = await fetch('/api/search?' + params.toString());
    if (!res.ok) return [];
    return await res.json();
  }

  input.addEventListener('input', () => {
    syncClear();
    clearTimeout(debounce);
    const q = input.value.trim();
    if (q.length < 2) { close(); return; }
    const myReq = ++inflight;
    debounce = setTimeout(async () => {
      const items = await fetchResults(q);
      if (myReq !== inflight) return;   // a newer request started; drop this response
      render(items);
    }, 220);
  });

  input.addEventListener('focus', () => {
    const q = input.value.trim();
    if (q.length >= 2 && !dropdown) input.dispatchEvent(new Event('input'));
  });

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !dropdown && input.value) {
      input.value = '';
      syncClear();
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return;
    }
    if (!dropdown) return;
    const rows = dropdown.querySelectorAll('.search-dropdown-row');
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      activeIndex = rows.length === 0 ? -1 : Math.min(activeIndex + 1, rows.length - 1);
      highlight();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      activeIndex = Math.max(activeIndex - 1, 0);
      highlight();
    } else if (e.key === 'Enter') {
      if (activeIndex >= 0 && rows[activeIndex]) {
        e.preventDefault();
        navigate(rows[activeIndex].dataset.url);
      } else if (rows.length > 0) {
        e.preventDefault();
        navigate(rows[0].dataset.url);
      }
    } else if (e.key === 'Escape') {
      close();
    }
  });

  document.addEventListener('mousedown', (e) => {
    if (!dropdown) return;
    if (dropdown.contains(e.target) || input.contains(e.target)) return;
    close();
  });
  window.addEventListener('resize', position);
  window.addEventListener('scroll', position, true);
}
