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

/**
 * Test registry — deliberately dependency-free. The *.test.js files register on
 * import and the runner (index.js) iterates; if either owned this array the two
 * would form an import cycle (add() would run before the registry initialises,
 * or a top-level-await workaround would deadlock the module graph).
 */
export const tests = [];

export function add(name, fn) {
    tests.push({ name, fn });
}
