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
