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
 * Theme picker behaviour for profile.html. Lives in a static file (not an inline <script>) so
 * the page can run under a strict CSP that disallows 'unsafe-inline' for script-src.
 */
(function () {
  const form = document.getElementById('theme-form');
  if (!form) return;
  const body = document.body;
  const feedback = document.getElementById('theme-feedback');
  let feedbackTimer = null;

  function showFeedback(text, isError) {
    if (!feedback) return;
    clearTimeout(feedbackTimer);
    feedback.textContent = text;
    feedback.className = 'profile-help' + (isError ? ' error' : '');
    feedback.hidden = false;
    if (!isError) feedbackTimer = setTimeout(() => { feedback.hidden = true; }, 2000);
  }

  // Selecting a theme IS the save — there is no Save button. URLSearchParams over the
  // form serializes both the theme radio and the Thymeleaf-injected _csrf field.
  async function saveTheme() {
    try {
      const res = await fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
      });
      if (res.ok || res.redirected) showFeedback('Theme saved.');
      else showFeedback('Could not save the theme (' + res.status + ').', true);
    } catch (err) {
      showFeedback('Could not save the theme: ' + (err?.message || err), true);
    }
  }

  form.addEventListener('change', (e) => {
    const target = e.target;
    if (target && target.name === 'theme') {
      body.setAttribute('data-theme', target.value);
      form.querySelectorAll('.theme-option').forEach((opt) => {
        opt.classList.toggle('selected', opt.dataset.themeValue === target.value);
      });
      saveTheme();
    }
  });

  // Also support clicks anywhere on the option tile (the label already does this
  // for the radio, but Safari occasionally swallows the change event on quick taps).
  form.querySelectorAll('.theme-option').forEach((opt) => {
    opt.addEventListener('click', () => {
      const value = opt.dataset.themeValue;
      const radio = opt.querySelector('input[type="radio"]');
      if (radio && !radio.checked) {
        radio.checked = true;
        radio.dispatchEvent(new Event('change', { bubbles: true }));
      }
    });
  });
})();

// ---------- Time zone ----------
// Same shape as the theme picker: choosing IS the save, and the Thymeleaf-injected _csrf field
// rides along in the form body. Kept a real <form> with a real action so it still works — with a
// page reload — if this script never loads.
(function () {
  const form = document.getElementById('timezone-form');
  const select = document.getElementById('timezone-select');
  const feedback = document.getElementById('timezone-feedback');
  if (!form || !select) return;

  let feedbackTimer = null;
  let saved = select.value;

  function show(text, isError) {
    if (!feedback) return;
    clearTimeout(feedbackTimer);
    feedback.textContent = text;
    feedback.className = 'profile-help' + (isError ? ' error' : '');
    feedback.hidden = false;
    if (!isError) feedbackTimer = setTimeout(() => { feedback.hidden = true; }, 2500);
  }

  form.addEventListener('submit', (e) => e.preventDefault());

  select.addEventListener('change', async () => {
    select.disabled = true;
    try {
      const res = await fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
      });
      if (!res.ok && !res.redirected) throw new Error('rejected');
      saved = select.value;
      // The "currently using" line beside the picker is server-rendered, so it is one reload
      // behind until the page is revisited. Say what was saved rather than leave the two
      // disagreeing silently.
      show(saved ? 'Time zone saved: ' + saved + '.' : 'Time zone saved: automatic.');
    } catch (err) {
      // Put the control back to what the server actually holds — a select showing a value that
      // was refused is the one state worse than an error message.
      select.value = saved;
      show('Could not save the time zone.', true);
    } finally {
      select.disabled = false;
    }
  });
})();

// ---------- Time zone: searchable combobox ----------
/*
 * Six hundred options in a native <select> is a list you scroll, not a list you search. Everyone
 * knows the name of their own zone and nobody wants to hunt for it between America/Nuuk and
 * Antarctica/Troll.
 *
 * Built as an *enhancement*, never a replacement: the real <select> stays in the DOM, keeps its
 * name and value, and is only hidden once this code has successfully put something better in front
 * of it. That was the stated reason the page shipped a plain select in the first place — a
 * searchable widget is the one control here that a user cannot fall back from if the script fails
 * to load — and hiding the fallback as the last step rather than in the template is what keeps that
 * promise. Selection writes back to the <select> and fires its `change` event, so the save path
 * below is exactly the same one the native control used; there is no second way to save a zone.
 */
(function () {
  const select = document.getElementById('timezone-select');
  if (!select || !select.options.length) return;

  // A long list needs a ceiling or every keystroke re-renders six hundred rows. The count of what
  // was cut is shown rather than dropped in silence — a filtered list that quietly stops at 60 is
  // a list that lies about whether your zone exists.
  const MAX_ROWS = 60;

  const offsets = new Map();

  /**
   * "UTC+02:00" for a zone, computed once per zone and only for rows actually rendered.
   *
   * Worth showing: it is how people sanity-check a name they half-recognise, and it is the only
   * thing that distinguishes the several plausible-looking answers a search for "america" returns.
   */
  function offsetOf(zone) {
    if (!zone) return '';
    if (offsets.has(zone)) return offsets.get(zone);
    let label = '';
    try {
      const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'shortOffset' })
          .formatToParts(new Date());
      label = (parts.find((p) => p.type === 'timeZoneName')?.value || '').replace('GMT', 'UTC');
      if (label === 'UTC') label = 'UTC+00:00';
    } catch (unknownZone) {
      label = '';
    }
    offsets.set(zone, label);
    return label;
  }

  // Region and city as separate words, so "oslo" finds Europe/Oslo and "new york" finds
  // America/New_York without the user having to reproduce the underscore.
  const haystack = (opt) =>
      (opt.value + ' ' + opt.textContent).toLowerCase().replace(/[_/]/g, ' ');

  const items = Array.from(select.options).map((opt) => ({
    value: opt.value,
    label: opt.textContent.trim(),
    search: haystack(opt),
  }));

  const wrap = document.createElement('div');
  wrap.className = 'tz-combo';

  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'tz-combo-input';
  input.setAttribute('role', 'combobox');
  input.setAttribute('aria-expanded', 'false');
  input.setAttribute('aria-autocomplete', 'list');
  input.setAttribute('aria-controls', 'tz-combo-list');
  input.setAttribute('autocomplete', 'off');
  input.setAttribute('spellcheck', 'false');
  input.placeholder = 'Search time zones…';
  input.id = 'timezone-search';

  const list = document.createElement('ul');
  list.className = 'tz-combo-list';
  list.id = 'tz-combo-list';
  list.setAttribute('role', 'listbox');
  list.hidden = true;

  const note = document.createElement('li');
  note.className = 'tz-combo-note';
  note.setAttribute('aria-hidden', 'true');

  wrap.append(input, list);

  let matches = [];
  let activeIndex = -1;

  const labelFor = (value) =>
      items.find((i) => i.value === value)?.label || value;

  const showSelection = () => { input.value = labelFor(select.value); };

  function render(query) {
    const tokens = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
    matches = tokens.length
        ? items.filter((i) => tokens.every((t) => i.search.includes(t)))
        : items.slice();
    const shown = matches.slice(0, MAX_ROWS);

    list.textContent = '';
    shown.forEach((item, index) => {
      const li = document.createElement('li');
      li.className = 'tz-combo-option';
      li.id = 'tz-opt-' + index;
      li.setAttribute('role', 'option');
      li.setAttribute('aria-selected', String(item.value === select.value));
      li.dataset.value = item.value;

      const name = document.createElement('span');
      name.className = 'tz-combo-name';
      name.textContent = item.label;
      li.appendChild(name);

      const off = offsetOf(item.value);
      if (off) {
        const badge = document.createElement('span');
        badge.className = 'tz-combo-offset';
        badge.textContent = off;
        li.appendChild(badge);
      }
      li.addEventListener('mousedown', (e) => {
        // mousedown, not click: the input's blur would close the list before a click landed.
        e.preventDefault();
        choose(item.value);
      });
      list.appendChild(li);
    });

    if (!shown.length) {
      note.textContent = 'No time zone matches “' + query.trim() + '”.';
      list.appendChild(note);
    } else if (matches.length > shown.length) {
      note.textContent = (matches.length - shown.length) + ' more — keep typing to narrow it down.';
      list.appendChild(note);
    }
    setActive(shown.findIndex((i) => i.value === select.value));
  }

  function setActive(index) {
    const options = list.querySelectorAll('.tz-combo-option');
    activeIndex = Math.max(-1, Math.min(index, options.length - 1));
    options.forEach((el, i) => el.classList.toggle('is-active', i === activeIndex));
    if (activeIndex >= 0) {
      input.setAttribute('aria-activedescendant', 'tz-opt-' + activeIndex);
      options[activeIndex].scrollIntoView({ block: 'nearest' });
    } else {
      input.removeAttribute('aria-activedescendant');
    }
  }

  function open(query) {
    render(query === undefined ? '' : query);
    list.hidden = false;
    input.setAttribute('aria-expanded', 'true');
  }

  function close() {
    list.hidden = true;
    input.setAttribute('aria-expanded', 'false');
    input.removeAttribute('aria-activedescendant');
    showSelection();
  }

  function choose(value) {
    select.value = value;
    close();
    // The one save path. Dispatching the native event means this control saves through exactly the
    // handler the <select> already had, rather than growing a duplicate of it.
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }

  input.addEventListener('focus', () => { input.select(); open(''); });
  input.addEventListener('input', () => open(input.value));
  input.addEventListener('blur', () => setTimeout(close, 0));

  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (list.hidden) { open(''); return; }
      setActive(activeIndex + (e.key === 'ArrowDown' ? 1 : -1));
    } else if (e.key === 'Home' && !list.hidden) {
      e.preventDefault(); setActive(0);
    } else if (e.key === 'End' && !list.hidden) {
      e.preventDefault(); setActive(list.querySelectorAll('.tz-combo-option').length - 1);
    } else if (e.key === 'Enter') {
      const options = list.querySelectorAll('.tz-combo-option');
      if (!list.hidden && activeIndex >= 0 && options[activeIndex]) {
        e.preventDefault();
        choose(options[activeIndex].dataset.value);
      }
    } else if (e.key === 'Escape') {
      if (!list.hidden) { e.stopPropagation(); close(); }
    }
  });

  // Last: the fallback is only given up once the replacement is definitely on the page.
  select.parentNode.insertBefore(wrap, select);
  // The section's visible label was pointing at the control we are about to hide, which would
  // leave the input people actually type into with no accessible name at all.
  document.querySelector('label[for="timezone-select"]')?.setAttribute('for', 'timezone-search');
  select.hidden = true;
  select.setAttribute('tabindex', '-1');
  select.setAttribute('aria-hidden', 'true');
  showSelection();
})();

// ---------- Date and time format ----------
// Same shape again — choosing IS the save, real <form> with a real action so it degrades to a page
// reload if this script never runs. Two selects share one form and one endpoint because they are
// one decision ("how do I want times written"), and posting them together keeps the sample line
// below them describing a state that actually exists.
(function () {
  const form = document.getElementById('time-format-form');
  const feedback = document.getElementById('time-format-feedback');
  if (!form) return;

  const selects = Array.from(form.querySelectorAll('select'));
  let feedbackTimer = null;
  let saved = new Map(selects.map((s) => [s.name, s.value]));

  function show(text, isError) {
    if (!feedback) return;
    clearTimeout(feedbackTimer);
    feedback.textContent = text;
    feedback.className = 'profile-help' + (isError ? ' error' : '');
    feedback.hidden = false;
    if (!isError) feedbackTimer = setTimeout(() => { feedback.hidden = true; }, 2500);
  }

  form.addEventListener('submit', (e) => e.preventDefault());

  form.addEventListener('change', async (e) => {
    if (!(e.target instanceof HTMLSelectElement)) return;
    selects.forEach((s) => { s.disabled = true; });
    try {
      const res = await fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
      });
      if (!res.ok && !res.redirected) throw new Error('rejected');
      saved = new Map(selects.map((s) => [s.name, s.value]));
      // The sample line is server-rendered, so it is one reload behind. Reload rather than say so:
      // the whole point of this control is seeing the format, and a stale sample under a changed
      // picker is exactly the confusion it exists to remove.
      window.location.reload();
    } catch (err) {
      selects.forEach((s) => { s.value = saved.get(s.name); });
      show('Could not save the format.', true);
    } finally {
      selects.forEach((s) => { s.disabled = false; });
    }
  });
})();

// ---------- Profile picture upload ----------
(function () {
  const fileInput = document.getElementById('avatar-file');
  const uploadBtn = document.getElementById('avatar-upload-btn');
  const clearBtn = document.getElementById('avatar-clear-btn');
  const preview = document.getElementById('avatar-preview');
  const status = document.getElementById('avatar-status');
  if (!fileInput || !uploadBtn || !preview) return;

  const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content || '';
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header');
  function csrfHeaders() {
    const h = {};
    if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
    return h;
  }

  function setStatus(text, kind) {
    if (!status) return;
    if (!text) { status.hidden = true; status.textContent = ''; status.className = 'profile-help'; return; }
    status.hidden = false;
    status.textContent = text;
    status.className = 'profile-help' + (kind === 'error' ? ' avatar-status-error' : '');
  }

  let previewObjectUrl = null;

  function updatePreviewToFile(file) {
    // Revoke the prior blob URL first so we don't leak one per upload attempt.
    if (previewObjectUrl) { URL.revokeObjectURL(previewObjectUrl); previewObjectUrl = null; }
    previewObjectUrl = URL.createObjectURL(file);
    let img = preview.querySelector('.avatar-image');
    if (!img) {
      img = document.createElement('img');
      img.className = 'avatar-image';
      img.alt = '';
      preview.insertBefore(img, preview.firstChild);
    }
    img.src = previewObjectUrl;
    if (clearBtn) clearBtn.disabled = false;
  }

  function clearPreview() {
    preview.querySelector('.avatar-image')?.remove();
    if (previewObjectUrl) { URL.revokeObjectURL(previewObjectUrl); previewObjectUrl = null; }
    if (clearBtn) clearBtn.disabled = true;
  }

  uploadBtn.addEventListener('click', () => fileInput.click());

  fileInput.addEventListener('change', async () => {
    const file = fileInput.files && fileInput.files[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      setStatus('That file is over the 5 MiB cap.', 'error');
      fileInput.value = '';
      return;
    }
    setStatus('Uploading…');
    // Don't preview optimistically — a rejected upload (bad MIME, 413) would leave the new
    // picture showing beside the error, misrepresenting the saved state. Swap only on success.
    try {
      // Raw-body upload — the File is the request body (see chat/index.js for the rationale).
      const h = csrfHeaders();
      h['Content-Type'] = file.type || 'application/octet-stream';
      h['X-Upload-Filename'] = encodeURIComponent(file.name);
      const res = await fetch('/api/profile/avatar', {
        method: 'POST',
        headers: h,
        body: file,
      });
      if (!res.ok && res.status !== 204) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        let msg;
        if (res.status === 413 && typeof err.maxBytes === 'number') {
          const mib = (err.maxBytes / (1024 * 1024)).toFixed(0);
          msg = 'File too large — avatars are capped at ' + mib + ' MiB.';
        } else {
          msg = err.message || err.error || 'Upload failed.';
        }
        setStatus(msg, 'error');
      } else {
        updatePreviewToFile(file); // server accepted it — now reflect the new picture
        setStatus('Saved. New picture will appear across the app on next page load.');
      }
    } catch (e) {
      setStatus('Network error. Please try again.', 'error');
    } finally {
      fileInput.value = '';
    }
  });

  clearBtn?.addEventListener('click', async () => {
    if (!confirm('Remove your profile picture?')) return;
    setStatus('Removing…');
    try {
      const res = await fetch('/api/profile/avatar', {
        method: 'DELETE',
        headers: csrfHeaders(),
      });
      if (!res.ok && res.status !== 204) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        setStatus(err.message || err.error || 'Remove failed.', 'error');
        return;
      }
      clearPreview();
      setStatus('Removed.');
    } catch (e) {
      setStatus('Network error. Please try again.', 'error');
    }
  });
})();

// ---------- Custom status (emoji + text + auto-clear) ----------
(function () {
  const form = document.getElementById('status-form');
  if (!form) return;

  const emojiInput = document.getElementById('status-emoji');
  const textInput = document.getElementById('status-text');
  const clearAfterSelect = document.getElementById('status-clear-after');
  const clearBtn = document.getElementById('status-clear-btn');
  const feedback = document.getElementById('status-feedback');

  function csrfHeaders() {
    const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    if (token && header) headers[header] = token;
    return headers;
  }

  function setFeedback(message, kind) {
    if (!message) {
      feedback.hidden = true;
      feedback.textContent = '';
      return;
    }
    feedback.hidden = false;
    feedback.textContent = message;
    feedback.classList.toggle('error', kind === 'error');
  }

  function applyDtoToForm(dto) {
    emojiInput.value = dto?.statusEmoji || '';
    textInput.value = dto?.statusText || '';
    clearAfterSelect.value = '0';
  }

  // Prefill from server's current state — we don't render Thymeleaf-side because the form
  // also needs to reflect updates pushed via /topic/presence (other tab edited the status).
  async function prime() {
    const username = document.querySelector('meta[name="me-username"]')?.getAttribute('content')
      || document.querySelector('.me [data-author]')?.getAttribute('data-author');
    if (!username) return;
    try {
      const res = await fetch('/api/presence?usernames=' + encodeURIComponent(username),
          { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
      if (!res.ok) return;
      const list = await res.json();
      if (Array.isArray(list) && list.length > 0) applyDtoToForm(list[0]);
    } catch (e) { /* show form blank on error */ }
  }
  prime();

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const emoji = emojiInput.value.trim();
    const text = textInput.value.trim();
    const minutes = parseInt(clearAfterSelect.value, 10) || 0;
    const clearAt = minutes > 0 ? new Date(Date.now() + minutes * 60_000).toISOString() : null;
    setFeedback('Saving…');
    try {
      const res = await fetch('/api/presence/status', {
        method: 'POST', credentials: 'same-origin',
        headers: csrfHeaders(),
        body: JSON.stringify({ emoji, text, clearAt }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        setFeedback(err.message || err.error || 'Could not save status.', 'error');
        return;
      }
      const dto = await res.json();
      applyDtoToForm(dto);
      setFeedback(dto.statusEmoji || dto.statusText ? 'Status updated.' : 'Status cleared.');
    } catch (err) {
      setFeedback('Network error. Please try again.', 'error');
    }
  });

  clearBtn.addEventListener('click', async () => {
    setFeedback('Clearing…');
    try {
      const res = await fetch('/api/presence/status', {
        method: 'DELETE', credentials: 'same-origin',
        headers: csrfHeaders(),
      });
      if (!res.ok) {
        setFeedback('Could not clear status.', 'error');
        return;
      }
      const dto = await res.json();
      applyDtoToForm(dto);
      setFeedback('Status cleared.');
    } catch (e) {
      setFeedback('Network error. Please try again.', 'error');
    }
  });

  // ---------- Notification defaults ----------
  // Account-level, so they are saved to the server rather than localStorage: what interrupts you is
  // about you and should follow you between devices. Which sound it makes is about the room you
  // are sitting in and stays local — see below.
  //
  // Two of them, one for channels and one for conversations, because "mentions only" is a sensible
  // way to follow a channel and a broken way to receive a message sent to you alone. Same wiring
  // for both; only the element and the endpoint differ.
  const wireNotifyDefault = (elementId, path, label) => {
    const picker = document.getElementById(elementId);
    if (!picker) return;
    picker.addEventListener('change', async () => {
      const previous = picker.dataset.current;
      picker.disabled = true;
      try {
        const res = await fetch(path, {
          method: 'PUT', headers: csrfHeaders(),   // already sets Content-Type: application/json
          body: JSON.stringify({ level: picker.value }),
        });
        if (!res.ok) throw new Error('rejected');
        picker.dataset.current = picker.value;
        setFeedback(label + ' saved.');
      } catch (e) {
        picker.value = previous;
        setFeedback('Could not save that.', 'error');
      } finally {
        picker.disabled = false;
      }
    });
  };
  wireNotifyDefault('notify-default-level', '/api/profile/notify-default',
                    'Notification default');
  wireNotifyDefault('notify-dm-default-level', '/api/profile/notify-dm-default',
                    'Direct-message notification default');

  // ---------- Notification sound ----------
  // One row per kind: whether it makes a sound, and which one. State lives in localStorage
  // (notifications.js owns the keys), so the controls are corrected here rather than rendered
  // by the server, which cannot know the answer.
  const SOUND_ROWS = [
    { kind: 'direct',  toggle: 'notification-sound-dm',      select: 'notification-voice-dm' },
    { kind: 'mention', toggle: 'notification-sound-mention', select: 'notification-voice-mention' },
  ];
  const notifications = window.MentionNotifications;
  if (notifications) {
    const voices = notifications.soundVoices();
    for (const { kind, toggle, select } of SOUND_ROWS) {
      const box = document.getElementById(toggle);
      const picker = document.getElementById(select);
      if (!box || !picker) continue;

      box.checked = notifications.soundEnabled(kind);
      for (const { name, label } of voices) {
        const opt = document.createElement('option');
        opt.value = name;
        opt.textContent = label;
        picker.appendChild(opt);
      }
      picker.value = notifications.soundVoice(kind);
      picker.disabled = !box.checked;

      box.addEventListener('change', () => {
        notifications.setSoundEnabled(kind, box.checked);
        // A picker for a sound that will never play is a control that does nothing.
        picker.disabled = !box.checked;
        // Play on enable so "did that work" is answered at once — and the click that ticked the
        // box is also the gesture that unlocks audio, so it is the first moment it can be heard.
        if (box.checked) notifications.playVoice(picker.value);
      });
      picker.addEventListener('change', () => {
        notifications.setSoundVoice(kind, picker.value);
        // Choosing a sound plays it. Picking one from a list of names without hearing it is
        // guessing, and the whole point of five is that they differ.
        notifications.playVoice(picker.value);
      });
    }
  }
})();
