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
 * Do Not Disturb: that the switch is wired to something.
 *
 * The gate is two modules deep — presence.js works out whether the signed-in user is in DND,
 * notifications.js refuses to interrupt when they are — and either half can come loose without
 * anything visible breaking. The failure is silent by construction: a broken gate means the
 * notification you would have got anyway, and a broken presence lookup means DND quietly stops
 * working for everyone. That is exactly the shape of thing worth a smoke test.
 *
 * Observation-only, like the rest of the runner. It does not PUT a presence kind — that would
 * change what the account looks like to every other user in the workspace, and leave it changed
 * if a check threw halfway. Instead it stands in for Presence.isDnd for the length of one
 * synchronous call and puts it back in a finally.
 */

import { add } from './registry.js';

add('presence knows which user is signed in', () => {
    const meta = document.querySelector('meta[name="me-username"]')?.content;
    if (!meta) throw new Error('me-username meta missing');
    if (!window.Presence) throw new Error('window.Presence missing — presence.js did not load');
    const self = window.Presence.me();
    if (!self) {
        throw new Error(
            'Presence.me() is null. The topbar avatar has lost its data-author, or /api/presence '
            + 'answered under a different name — either way the Do Not Disturb gate is a no-op.');
    }
    if (self.username.toLowerCase() !== meta.toLowerCase()) {
        throw new Error('Presence.me() is ' + self.username + ', expected ' + meta);
    }
    if (!self.kind) throw new Error('PresenceDto for the signed-in user carries no kind');
});

add('notifications read Do Not Disturb from presence', () => {
    const n = window.MentionNotifications;
    if (!n || typeof n.dndActive !== 'function') {
        throw new Error('MentionNotifications.dndActive missing — notifications.js is stale');
    }
    const real = window.Presence.isDnd;
    try {
        window.Presence.isDnd = () => true;
        if (n.dndActive() !== true) throw new Error('DND on, but notifications think it is off');
        window.Presence.isDnd = () => false;
        if (n.dndActive() !== false) throw new Error('DND off, but notifications think it is on');
    } finally {
        window.Presence.isDnd = real;
    }
});

add('Do Not Disturb suppresses the toast', () => {
    const n = window.MentionNotifications;
    const real = window.Presence.isDnd;
    const before = document.querySelectorAll('.notification-toast').length;
    try {
        window.Presence.isDnd = () => true;
        n.show({
            author: 'smoke-test', channel: 'smoke-test', kind: 'direct',
            snippet: 'this must not appear', url: '',
        });
        const after = document.querySelectorAll('.notification-toast').length;
        if (after !== before) {
            throw new Error('a toast was drawn while in Do Not Disturb');
        }
    } finally {
        window.Presence.isDnd = real;
    }
    // Only the suppressing direction is exercised. The other one — that show() draws a toast at
    // all — is what every real mention already demonstrates, and asserting it here would mean
    // firing a chime and a desktop banner at whoever typed runTests().
});
