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
 * Boot for the /search results page.
 *
 * Everything on that page works without this file: the results, the count and the pager are
 * server-rendered, and the scope control is a plain GET form with a submit button. What is here is
 * only the polish that would be silly to render on the server — a <select> that submits itself, and
 * the syntax examples in the empty state being clickable rather than something to retype.
 *
 * It also wires the top bar's own live dropdown, because the search box on this page is the same
 * search box as everywhere else and should behave the same way.
 */

import { initSearchBox } from './search-box.js';

initSearchBox('global-search-input');

// A scope control that needs a second click to take effect reads as a form field rather than as a
// control. The submit button stays for the no-JavaScript case, which is why this can be additive.
const scopeSelect = document.getElementById('search-scope-select');
scopeSelect?.addEventListener('change', () => {
  // Paging is scoped to a result set, so changing the scope starts a new one at page one. Leaving
  // ?page=3 on would land the user on an empty page and look like the new scope had no matches.
  const form = scopeSelect.form;
  if (!form) return;
  form.querySelectorAll('input[name="page"]').forEach((el) => el.remove());
  form.requestSubmit ? form.requestSubmit() : form.submit();
});

// The syntax help doubles as input. Clicking `from:` puts the caret after it, ready for a name;
// clicking a whole example replaces the query, since it is a complete thing to try.
const queryInput = document.querySelector('.search-scope-form input[name="q"]');
document.querySelectorAll('.search-help-example').forEach((button) => {
  button.addEventListener('click', () => {
    if (!queryInput) return;
    const example = button.dataset.example || '';
    // A bare modifier (ends in ':' or is just '@') is a prefix to build on, so append it to what
    // the user already typed. A full example is a replacement.
    const isPrefix = /[:@]$/.test(example) || example.endsWith('#');
    if (isPrefix) {
      const existing = queryInput.value.replace(/\s+$/, '');
      queryInput.value = existing ? existing + ' ' + example : example;
    } else {
      queryInput.value = example;
    }
    queryInput.focus();
    const end = queryInput.value.length;
    queryInput.setSelectionRange(end, end);
  });
});
