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
    if (!block) {
        throw new Error('buildMessageBodyEl dropped the code block entirely');
    }
    // The language hint has to survive however the body was set. hljs falls back to
    // auto-detection when it is gone, so highlighting still "works" while quietly guessing the
    // language of every block — a failure worth naming rather than eyeballing.
    if (!block.className.includes('language-java')) {
        throw new Error('the language class was stripped from a code block: ' + block.className);
    }
    if (window.hljs && block.dataset.highlighted !== 'yes') {
        throw new Error('buildMessageBodyEl rendered a code block without highlighting it');
    }
});

add('Element.setHTML is available (polyfilled where the browser lacks it)', () => {
    // search-box.js calls it with no feature test, so it has to exist. Safari only shipped it in
    // 26; js/vendor/html-setters-polyfill.min.js covers everything older.
    if (typeof document.createElement('div').setHTML !== 'function') {
        throw new Error('Element.setHTML missing — the search dropdown will throw on every row');
    }
});

add('a message body keeps its video embed and data-* attributes', () => {
    // The reason renderMessageBody uses innerHTML rather than setHTML: the browser sanitizer
    // removes <iframe> unconditionally and strips data-*, and a body legitimately carries both.
    // If someone "hardens" the seam with setHTML, every video embed in the app disappears — this
    // is the check that says so out loud.
    const el = window.ChatKit.buildMessageBodyEl(
        '<div class="video-embed-wrapper" data-orientation="vertical">'
        + '<iframe class="video-embed" src="https://www.youtube-nocookie.com/embed/x"></iframe></div>'
        + '<span class="mention" data-username="alice">@alice</span>');
    if (!el.querySelector('iframe.video-embed')) {
        throw new Error('the embed iframe was stripped — renderMessageBody must not use setHTML');
    }
    if (!el.querySelector('[data-orientation="vertical"]')) {
        throw new Error('data-orientation was stripped — the Shorts frame will render landscape');
    }
    if (!el.querySelector('.mention[data-username="alice"]')) {
        throw new Error('data-username was stripped off a mention');
    }
});
