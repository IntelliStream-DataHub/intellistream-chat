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

/** REST round-trips: known endpoints respond 200 with the JSON shape we expect. */

import { add } from './registry.js';

async function expectJson(path, predicate) {
    const res = await fetch(path, { credentials: 'same-origin' });
    if (!res.ok) throw new Error(path + ' returned ' + res.status + ' ' + res.statusText);
    const body = await res.json();
    if (predicate && !predicate(body)) {
        throw new Error(path + ' body did not match predicate; got: ' + JSON.stringify(body).slice(0, 200));
    }
    return body;
}

add('GET /actuator/health is UP', async () => {
    await expectJson('/actuator/health', (b) => b.status === 'UP');
});

add('GET /api/channels returns an array', async () => {
    await expectJson('/api/channels', (b) => Array.isArray(b));
});

add('GET /api/users/{me} returns the logged-in user', async () => {
    const me = document.querySelector('meta[name="me-username"]')?.content;
    if (!me) throw new Error('me-username meta missing');
    const dto = await expectJson('/api/users/' + encodeURIComponent(me));
    if (dto.username?.toLowerCase() !== me.toLowerCase()) {
        throw new Error('expected username ' + me + ', got ' + dto.username);
    }
});

add('GET /api/presence returns the kind field', async () => {
    const me = document.querySelector('meta[name="me-username"]')?.content;
    if (!me) throw new Error('me-username meta missing');
    const list = await expectJson('/api/presence?usernames=' + encodeURIComponent(me),
            (b) => Array.isArray(b) && b.length === 1);
    if (!list[0].kind) {
        throw new Error('PresenceDto.kind missing; got: ' + JSON.stringify(list[0]));
    }
});
