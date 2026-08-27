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
 * Paste-as-Markdown: the client-side guard and the length clamp.
 *
 * The full conversion is server-side (HtmlToMarkdownConverterTest covers it in CI); what
 * lives in the browser is the pre-guard deciding whether a paste is worth a round trip,
 * and the clamp that keeps a converted insert inside the 8000-char body limit. Both are
 * pure enough to check here, and both fail silently in production — a broken guard just
 * makes every paste plain, a broken clamp makes the server reject big pastes.
 */

import { add } from './registry.js';

add('paste guard accepts rich clipboard HTML, rejects bare text wrappers', () => {
    const kit = window.ChatKit;
    if (!kit || !kit.pasteWorthConverting) throw new Error('ChatKit.pasteWorthConverting missing — chat-kit.js is stale');
    const docsShaped = '<b id="docs-internal-guid-abc" style="font-weight:normal">'
        + '<p><span style="font-weight:700">Bold</span></p></b>';
    if (!kit.pasteWorthConverting(docsShaped)) throw new Error('a Docs-shaped paste should convert');
    if (!kit.pasteWorthConverting('<p class=MsoNormal style="mso-list:l0 level1">x</p>')) {
        throw new Error('a Word-shaped paste should convert');
    }
    if (kit.pasteWorthConverting('<div>just plain text in a div</div>')) {
        throw new Error('a bare text wrapper must stay a native paste');
    }
    if (kit.pasteWorthConverting('')) throw new Error('empty HTML must stay a native paste');
});

add('paste clamp keeps a converted insert inside maxlength', () => {
    const kit = window.ChatKit;
    if (!kit || !kit.insertPasteText) throw new Error('ChatKit.insertPasteText missing — chat-kit.js is stale');
    const ta = document.createElement('textarea');
    ta.setAttribute('maxlength', '40');
    // Detached textarea: the notice helper finds no form and stays quiet, which is fine —
    // this asserts the clamp arithmetic, not the banner.
    ta.value = 'seed ';
    ta.selectionStart = ta.selectionEnd = ta.value.length;
    kit.insertPasteText(ta, 'x'.repeat(100));
    if (ta.value.length > 40) throw new Error('insert overshot maxlength: ' + ta.value.length);
    if (!ta.value.startsWith('seed ')) throw new Error('existing content was clobbered');
    if (!ta.value.endsWith('x')) throw new Error('nothing was inserted');
});
