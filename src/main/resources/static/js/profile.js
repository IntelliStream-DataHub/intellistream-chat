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
})();
