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

add('a message body carries no player, and mentions keep their data-*', () => {
    // The body used to carry a YouTube <iframe>, which called Google on every render and made the
    // body impossible to run through Element.setHTML. The player is now a click-to-play facade on
    // the link-preview card; the body has the plain link and nothing else.
    const el = window.ChatKit.buildMessageBodyEl(
        '<p><a href="https://youtu.be/x">https://youtu.be/x</a></p>'
        + '<span class="mention" data-username="alice">@alice</span>');
    if (el.querySelector('iframe')) {
        throw new Error('a player was rendered inside the message body');
    }
    if (!el.querySelector('.mention[data-username="alice"]')) {
        throw new Error('data-username was stripped off a mention — is the seam using setHTML?');
    }
});

add('an attachment chip offers a preview only when the server said it can', () => {
    // previewUrl / previewKind come from the server (PreviewableAttachments); the chip must not
    // decide from a filename, or it offers previews the endpoints then refuse.
    const plain = window.ChatKit.buildAttachmentEl(
        { filename: 'report.pdf', contentType: 'application/pdf', sizeBytes: 10, downloadUrl: '/d/1' });
    if (plain.querySelector('.attachment-preview')) {
        throw new Error('a file with no previewUrl was given a preview button');
    }
    const doc = window.ChatKit.buildAttachmentEl({
        filename: 'notes.md', contentType: 'text/markdown', sizeBytes: 10,
        downloadUrl: '/d/2', previewUrl: '/api/attachments/2/markdown', previewKind: 'markdown',
    });
    const button = doc.querySelector('.attachment-preview');
    if (!button) throw new Error('a markdown attachment got no preview button');
    if (button.dataset.previewKind !== 'markdown' || !button.dataset.previewUrl) {
        throw new Error('the preview button carries nothing for the click delegate to read');
    }
    // The chip itself stays a plain download link: a <button> nested inside an <a> is neither
    // valid markup nor operable by a keyboard, which is why the button is its sibling.
    const chip = doc.querySelector('a.attachment');
    if (!chip || chip.querySelector('button')) {
        throw new Error('the preview button was nested inside the download link');
    }
});

add('a video card is a facade until it is clicked', () => {
    const card = window.ChatKit.buildVideoFacadeEl({
        url: 'https://youtu.be/dQw4w9WgXcQ',
        title: 'Never Gonna Give You Up',
        imageUrl: '/api/link-previews/images/abc',
        video: {
            provider: 'YouTube',
            embedUrl: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ',
            orientation: null,
        },
    });
    if (card.querySelector('iframe')) {
        throw new Error('the card built an iframe before anyone clicked it — that is the leak');
    }
    const button = card.querySelector('.video-facade');
    if (!button || !button.dataset.embedUrl) {
        throw new Error('no play button, or no embed URL on it');
    }
    // The poster must be our own copy, never the third party's picture.
    const poster = card.querySelector('.video-facade-poster');
    if (!poster || !poster.getAttribute('src').startsWith('/api/link-previews/images/')) {
        throw new Error('the poster is not served from this origin: ' + poster?.getAttribute('src'));
    }
});
