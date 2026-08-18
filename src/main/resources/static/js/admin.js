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

/**
 * Admin console behaviours.
 *
 * 1. Live preview of a selected logo file before upload — rendered at the same height the
 *    topbar uses, via a blob: URL (allowed by the CSP's img-src). Warns early when the file
 *    exceeds the server's 256 KB cap.
 * 2. The channel table's Delete forms: the button stays disabled until the typed name matches
 *    the row's channel (trimmed, case-insensitive — the same comparison the server makes). The
 *    server enforces the name regardless; this only stops a click that was going to bounce.
 */
(function () {
  document.querySelectorAll('form.admin-channel-delete').forEach((form) => {
    const input = form.querySelector('input[name="name"]');
    const button = form.querySelector('button[type="submit"]');
    const expected = (form.dataset.channelName || '').trim().toLowerCase();
    if (!input || !button || !expected) return;
    const sync = () => {
      button.disabled = input.value.trim().toLowerCase() !== expected;
    };
    input.addEventListener('input', sync);
    sync();
  });
})();

(function () {
  const input = document.querySelector('form[action$="/admin/logo"] input[type="file"]');
  const wrap = document.getElementById('logo-preview-wrap');
  const img = document.getElementById('logo-preview');
  const note = document.getElementById('logo-preview-note');
  if (!input || !wrap || !img) return;

  const MAX_BYTES = 256 * 1024;
  let objectUrl = null;

  input.addEventListener('change', () => {
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl);
      objectUrl = null;
    }
    const file = input.files && input.files[0];
    if (!file) {
      wrap.hidden = true;
      return;
    }
    objectUrl = URL.createObjectURL(file);
    img.src = objectUrl;
    wrap.hidden = false;
    if (note) {
      if (file.size > MAX_BYTES) {
        note.textContent = 'Too large: ' + Math.round(file.size / 1024) + ' KB (max 256 KB) — upload will be rejected.';
        note.className = 'admin-logo-note error';
      } else {
        note.textContent = Math.max(1, Math.round(file.size / 1024)) + ' KB';
        note.className = 'admin-logo-note';
      }
    }
  });
})();
