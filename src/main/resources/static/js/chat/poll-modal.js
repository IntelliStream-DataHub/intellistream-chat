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
 * Poll builder: a question, a list of options, and a submit.
 *
 * The slash command still works and is unchanged. This is a second door to the same room, for
 * the majority of people who will never learn that `/poll Question | A | B` exists — a feature
 * only reachable by typed syntax is a feature most of a workspace does not have.
 *
 * It produces the command rather than a new API shape. Creating goes through the composer, so
 * the message travels the same WebSocket send path with the same slash dispatch, rate limit and
 * broadcast as a typed one; editing PATCHes the same `/poll` body the edit route already parses.
 * Nothing here is a second implementation of what a poll is.
 */

const MIN_OPTIONS = 2;
const MAX_OPTIONS = 10;   // mirrors PollService.MAX_OPTIONS

let modalEl = null;

const escapeLabel = (s) => String(s).replace(/\|/g, '\\|');

/** The command this builder describes — the single output of the whole dialog. */
export function commandFrom(question, options) {
  return '/poll ' + [question, ...options].map(escapeLabel).join(' | ');
}

export function closePollModal() {
  if (!modalEl) return;
  document.removeEventListener('keydown', onKeydown);
  modalEl.remove();
  modalEl = null;
}

function onKeydown(e) {
  if (e.key === 'Escape') closePollModal();
}

/**
 * @param opts.poll        existing PollDto to edit; omit to create
 * @param opts.lockOptions true when the poll already has votes — the options are read-only and
 *                         the reason is shown, because the server will refuse the change and
 *                         letting someone type it first is a worse way to learn that
 * @param opts.onSubmit    async (command) => void; the dialog stays open if it throws
 */
export function openPollModal(opts = {}) {
  closePollModal();
  const poll = opts.poll || null;
  const editing = !!poll;
  const lockOptions = !!opts.lockOptions;

  modalEl = document.createElement('div');
  modalEl.className = 'poll-modal-backdrop';
  modalEl.innerHTML =
      '<div class="poll-modal" role="dialog" aria-modal="true" aria-labelledby="poll-modal-title">' +
        '<header class="poll-modal-head">' +
          '<h2 id="poll-modal-title"></h2>' +
          '<button type="button" class="icon-btn poll-modal-close" aria-label="Close">' +
            '<svg class="icon"><use href="#icon-close"/></svg>' +
          '</button>' +
        '</header>' +
        '<form class="poll-modal-body">' +
          '<label class="poll-field">Question' +
            '<input type="text" class="poll-question-input" maxlength="300" required ' +
                   'placeholder="What should we do?" autocomplete="off"/>' +
          '</label>' +
          '<div class="poll-options-label">Options</div>' +
          '<div class="poll-option-rows"></div>' +
          '<p class="poll-locked-note" hidden>' +
            'People have already voted, so the options are fixed — their votes were cast on ' +
            'these. You can still edit the question.' +
          '</p>' +
          '<button type="button" class="poll-add-option">' +
            '<svg class="icon icon-sm" aria-hidden="true"><use href="#icon-plus"/></svg> Add option' +
          '</button>' +
          '<p class="poll-modal-error" hidden></p>' +
          '<div class="poll-modal-actions">' +
            '<button type="button" class="poll-modal-cancel">Cancel</button>' +
            '<button type="submit" class="poll-modal-submit"></button>' +
          '</div>' +
        '</form>' +
      '</div>';
  document.body.appendChild(modalEl);

  const dialog = modalEl.querySelector('.poll-modal');
  const form = modalEl.querySelector('.poll-modal-body');
  const rows = modalEl.querySelector('.poll-option-rows');
  const questionInput = modalEl.querySelector('.poll-question-input');
  const addBtn = modalEl.querySelector('.poll-add-option');
  const errorEl = modalEl.querySelector('.poll-modal-error');
  const submitBtn = modalEl.querySelector('.poll-modal-submit');

  modalEl.querySelector('#poll-modal-title').textContent = editing ? 'Edit poll' : 'New poll';
  submitBtn.textContent = editing ? 'Save poll' : 'Create poll';

  const optionInputs = () => [...rows.querySelectorAll('.poll-option-input')];

  const syncRemoveButtons = () => {
    const n = optionInputs().length;
    rows.querySelectorAll('.poll-option-remove').forEach((b) => {
      // Below the minimum there is nothing to remove; hiding beats a button that refuses.
      b.hidden = n <= MIN_OPTIONS || lockOptions;
    });
    addBtn.hidden = n >= MAX_OPTIONS || lockOptions;
  };

  const addRow = (value = '') => {
    if (optionInputs().length >= MAX_OPTIONS) return;
    const row = document.createElement('div');
    row.className = 'poll-option-row';
    row.innerHTML =
        '<input type="text" class="poll-option-input" maxlength="120" autocomplete="off"/>' +
        '<button type="button" class="icon-btn poll-option-remove" aria-label="Remove option">' +
          '<svg class="icon icon-sm"><use href="#icon-close"/></svg>' +
        '</button>';
    const input = row.querySelector('.poll-option-input');
    input.value = value;
    input.placeholder = 'Option ' + (optionInputs().length + 1);
    input.disabled = lockOptions;
    row.querySelector('.poll-option-remove').addEventListener('click', () => {
      row.remove();
      syncRemoveButtons();
    });
    rows.appendChild(row);
    syncRemoveButtons();
    return input;
  };

  if (editing) {
    questionInput.value = poll.question || '';
    const labels = (poll.options || []).map((o) => o.label);
    (labels.length ? labels : ['', '']).forEach((l) => addRow(l));
    modalEl.querySelector('.poll-locked-note').hidden = !lockOptions;
  } else {
    addRow();
    addRow();
  }
  syncRemoveButtons();

  addBtn.addEventListener('click', () => addRow()?.focus());
  modalEl.querySelector('.poll-modal-close').addEventListener('click', closePollModal);
  modalEl.querySelector('.poll-modal-cancel').addEventListener('click', closePollModal);
  modalEl.addEventListener('click', (e) => { if (e.target === modalEl) closePollModal(); });
  document.addEventListener('keydown', onKeydown);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    errorEl.hidden = true;
    const question = questionInput.value.trim();
    const options = optionInputs().map((i) => i.value.trim()).filter(Boolean);
    if (!question) { fail('Give the poll a question.'); questionInput.focus(); return; }
    if (options.length < MIN_OPTIONS) { fail('A poll needs at least two options.'); return; }

    submitBtn.disabled = true;
    try {
      await opts.onSubmit(commandFrom(question, options));
      closePollModal();
    } catch (err) {
      // Kept open with the server's own words: a refused option change explains itself, and
      // reopening the dialog to retype everything would be its own punishment.
      fail((err && err.message) || 'Could not save that poll.');
      submitBtn.disabled = false;
    }
  });

  function fail(msg) {
    errorEl.textContent = msg;
    errorEl.hidden = false;
  }

  dialog.querySelector('.poll-question-input').focus();
}
