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
 * Shared utilities used by every chat/* module: read meta tags, build fetch headers
 * with the CSRF token attached, expose the active channel id. Loaded exactly once per
 * page (ES-module-cached on import), so the boot-time meta lookup also runs once.
 */

export const meta = (name) =>
    document.querySelector(`meta[name="${name}"]`)?.content || '';

export const csrfToken = meta('_csrf');
export const csrfHeader = meta('_csrf_header');
export const activeChannelId = meta('active-channel-id') || null;

export const headers = (extra) => {
    const h = Object.assign({ 'Content-Type': 'application/json' }, extra || {});
    if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
    return h;
};

/**
 * Lenient match for the sidebar channel filter. Returns true when:
 *   1. the query is empty, or
 *   2. the query is a substring of the target (cheap fast path), or
 *   3. every char of the query appears in the target in order (subsequence — handles
 *      "gen" matching "general", "deveng" matching "dev-engineering"), or
 *   4. Levenshtein similarity is at least {@code threshold}, defaulting to 50% so
 *      single-char typos and small transpositions still match.
 * Inputs are expected lower-cased by the caller; we don't lower-case again per call.
 */
export const fuzzyMatch = (query, target, threshold = 0.5) => {
    if (!query) return true;
    if (target.includes(query)) return true;
    let qi = 0;
    for (let i = 0; i < target.length && qi < query.length; i++) {
        if (target[i] === query[qi]) qi++;
    }
    if (qi === query.length) return true;
    const distance = levenshtein(query, target);
    const maxLen = Math.max(query.length, target.length);
    return maxLen === 0 ? true : (1 - distance / maxLen) >= threshold;
};

const levenshtein = (a, b) => {
    if (a === b) return 0;
    const m = a.length, n = b.length;
    if (!m) return n;
    if (!n) return m;
    let prev = new Array(n + 1);
    let cur = new Array(n + 1);
    for (let j = 0; j <= n; j++) prev[j] = j;
    for (let i = 1; i <= m; i++) {
        cur[0] = i;
        for (let j = 1; j <= n; j++) {
            const cost = a.charCodeAt(i - 1) === b.charCodeAt(j - 1) ? 0 : 1;
            cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
        }
        const tmp = prev; prev = cur; cur = tmp;
    }
    return prev[n];
};
