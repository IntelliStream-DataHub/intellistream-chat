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

/** STOMP / WebSocket health: the page-owned STOMP client is connected. */

import { add } from './registry.js';

add('StompJs library loaded', () => {
    if (typeof window.StompJs === 'undefined' || typeof window.StompJs.Client !== 'function') {
        throw new Error('StompJs.Client is not on window — vendor bundle did not load');
    }
});

add('Presence module loaded', () => {
    if (!window.Presence || typeof window.Presence.refreshAll !== 'function') {
        throw new Error('window.Presence.refreshAll is missing');
    }
});

add('Presence has live state for the current user', () => {
    const me = document.querySelector('meta[name="me-username"]')?.content;
    if (!me) throw new Error('me-username meta missing');
    const dto = window.Presence?.stateFor(me);
    if (!dto) {
        throw new Error('Presence has no entry for the current user — initial refreshAll may have failed');
    }
});
