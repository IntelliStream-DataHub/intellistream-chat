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
