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

/**
 * In-browser smoke runner. Loaded only when ichat.dev-tools.enabled=true (auto in
 * the dev Spring profile, never in prod). Type `runTests()` in the browser console
 * to execute every registered check against the LIVE page and deployment.
 *
 * Three buckets:
 *   - DOM:    critical selectors and form-wiring exist. Catches "I broke the template".
 *   - API:    REST round-trips return 200 with the shape the JS expects. Catches "I
 *             broke the wire format".
 *   - STOMP:  WebSocket handshake + STOMP CONNECT succeed. Catches "I broke realtime".
 *   - NOTIFY: the Do Not Disturb gate is still wired between presence.js and notifications.js.
 *             Catches "I broke the one feature whose failure mode is silence".
 *
 * Each bucket lives in its own file and registers via the runner. The runner itself
 * is ~30 lines — no framework, the registry is just an array.
 *
 * Tests are observation-only: no posting messages, no creating channels, no DB
 * mutation. The integration suite (./gradlew test) covers state-changing flows; this
 * is for "is the JS layer alive in this browser, against this deployment".
 */

// The registry lives in its own dependency-free module (registry.js). It must
// not live here: static imports are hoisted, so the test files would run their
// add() calls before this module's body initialised the array (TDZ crash).
import { tests } from './registry.js';

// Each .test.js file imports `add` from registry.js and calls it on import.
import './dom.test.js';
import './api.test.js';
import './stomp.test.js';
import './notifications.test.js';
import './emoji.test.js';

window.runTests = async function runTests() {
    let pass = 0;
    let fail = 0;
    const failures = [];
    console.group(
        '%cIntelliStream Chat smoke tests · ' + tests.length + ' checks',
        'font-weight:bold;color:#4af;font-size:14px'
    );
    for (const t of tests) {
        const t0 = performance.now();
        try {
            await Promise.resolve(t.fn());
            const ms = Math.round(performance.now() - t0);
            console.log(
                '%c✓ %c' + t.name + ' %c(' + ms + 'ms)',
                'color:#4af',
                '',
                'color:gray'
            );
            pass++;
        } catch (e) {
            console.error('%c✗ %c' + t.name, 'color:#e44;font-weight:bold', '', e);
            fail++;
            failures.push({ name: t.name, error: e });
        }
    }
    console.groupEnd();
    const summary = pass + ' passed, ' + fail + ' failed';
    console.log(
        '%c' + summary,
        fail
            ? 'color:#e44;font-weight:bold;font-size:14px'
            : 'color:#4af;font-weight:bold;font-size:14px'
    );
    return { pass, fail, failures };
};

console.log(
    '%cIntelliStream Chat dev-tools loaded · type runTests() to run ' + tests.length + ' smoke tests',
    'color:#4af'
);

// Re-export `add` for compatibility — test files themselves now import it
// straight from registry.js to keep the module graph acyclic.
export { add } from './registry.js';
