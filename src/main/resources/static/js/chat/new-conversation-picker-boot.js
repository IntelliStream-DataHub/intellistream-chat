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
 * Wires the "Find user…" button in the sidebar's new-conversation popover to the shared
 * find-user dialog, in pick mode: choosing people toggles their usernames in and out of the
 * form's members field, and the form itself stays the one thing that submits — so picking three
 * people and pressing "Create group" is exactly the same request as typing three names.
 *
 * A boot module rather than part of chat-kit.js (which wires the rest of this form) because the
 * dialog is an ES module and chat-kit is a plain bundled script; this file is the import seam,
 * the same shape as mention-autocomplete-boot.js, loaded by both pages that render the sidebar.
 */

import { openFindUserModal, closeFindUserModal } from './find-user-dialog.js';

const form = document.getElementById('new-conversation-form');
const trigger = document.getElementById('new-conversation-find-btn');

if (form && trigger) {
  const membersInput = form.querySelector('input[name="members"]');

  const names = () => (membersInput.value || '')
      .split(/[,\s]+/).map((s) => s.trim()).filter(Boolean);

  const has = (username) =>
      names().some((n) => n.toLowerCase() === username.toLowerCase());

  // Rewrites the field and pokes 'input' so chat-kit's direct-vs-group mode sync runs — the
  // field is the single source of truth, this dialog is just another way of typing into it.
  const setNames = (list) => {
    membersInput.value = list.join(', ');
    membersInput.dispatchEvent(new Event('input', { bubbles: true }));
  };

  trigger.addEventListener('click', () => {
    openFindUserModal({
      title: 'New message',
      isSelected: has,
      onToggle: (u) => {
        const rest = names().filter((n) => n.toLowerCase() !== u.username.toLowerCase());
        const picked = rest.length === names().length;
        setNames(picked ? [...rest, u.username] : rest);
        return picked;
      },
    });
  });

  // Submitting the form navigates away on success; close the dialog alongside it so a failed
  // submit (unknown typed name, group without a title) surfaces the form's own hint instead of
  // leaving it hidden behind the dialog.
  form.addEventListener('submit', () => closeFindUserModal());
}
