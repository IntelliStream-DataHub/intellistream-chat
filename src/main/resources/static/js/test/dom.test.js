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

/** DOM-contract checks: selectors and meta tags chat.js needs to find at boot. */

import { add } from './registry.js';

function require(selector, why) {
    const el = document.querySelector(selector);
    if (!el) throw new Error('missing selector ' + selector + (why ? ' (' + why + ')' : ''));
    return el;
}

add('topbar avatar present', () => {
    require('a.me .avatar', 'topbar status menu trigger');
});

add('CSRF token meta tag present', () => {
    const t = document.querySelector('meta[name="_csrf"]')?.content;
    if (!t || t.length < 10) throw new Error('CSRF token meta is missing or short: ' + t);
});

add('CSRF header name meta tag present', () => {
    const h = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (!h) throw new Error('_csrf_header meta missing');
});

add('me-username meta tag present', () => {
    const u = document.querySelector('meta[name="me-username"]')?.content;
    if (!u) throw new Error('me-username meta missing');
});

add('sidebar present', () => {
    require('aside.sidebar', 'channel list container');
});

add('composer textarea present', () => {
    const ta = document.querySelector('#composer textarea, .composer textarea');
    if (!ta) throw new Error('no composer textarea found');
});

add('no inline <script> elements (CSP would have blocked them)', () => {
    const inline = [...document.querySelectorAll('script')].filter((s) => !s.src);
    if (inline.length > 0) {
        throw new Error('found ' + inline.length + ' inline <script> blocks — strict CSP forbids these');
    }
});

// Message bodies: the seam every feed, panel and list renders through, and the one
// observable consequence of it. See MessageBodyRenderGuardTest for the CI-side guard —
// this is the half that can only be checked against a real page.
add('ChatKit exposes the shared message-body renderer', () => {
    const kit = window.ChatKit;
    if (typeof kit?.buildMessageBodyEl !== 'function' || typeof kit?.renderMessageBody !== 'function') {
        throw new Error('ChatKit.buildMessageBodyEl / renderMessageBody missing — pages will render '
            + 'bodies their own way again, and half of them will forget to highlight');
    }
    // Render a fenced block through the seam and check it came back highlighted. hljs marks what
    // it processed, so this asserts the pairing rather than the presence of the function.
    const el = kit.buildMessageBodyEl('<pre><code class="language-java">int x = 1;</code></pre>');
    const block = el.querySelector('pre code');
    if (window.hljs && block.dataset.highlighted !== 'yes') {
        throw new Error('buildMessageBodyEl rendered a code block without highlighting it');
    }
});

add('every code block on the page is highlighted', () => {
    // Catches the original bug directly: server-rendered history that no page script swept.
    // Skipped where the page has no code on it, which is most of the time.
    if (!window.hljs) return;
    const missed = [...document.querySelectorAll('.message-body pre code')]
        .filter((b) => b.dataset.highlighted !== 'yes');
    if (missed.length) {
        throw new Error(missed.length + ' code block(s) rendered unhighlighted — a body reached the '
            + 'DOM without going through ChatKit.renderMessageBody');
    }
});
