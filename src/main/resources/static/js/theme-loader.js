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

/*
 * Picks the highlight.js stylesheet that matches the active theme. Loaded by channels.html
 * before chat.js so the theme is in place by the time messages render. Lives in a static
 * file (instead of an inline <script>) so the page can run under a strict CSP that
 * disallows 'unsafe-inline' for script-src.
 */
(function () {
  function injectHljsStylesheet() {
    var theme = (document.body && document.body.dataset && document.body.dataset.theme) || 'default';
    var dark = theme === 'dark';
    var link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = dark
        ? '/css/vendor/highlight-github-dark.min.css'
        : '/css/vendor/highlight-github.min.css';
    link.onerror = function () {
      console.warn('[hljs] stylesheet failed to load:', link.href);
    };
    document.head.appendChild(link);
  }
  if (document.body) {
    injectHljsStylesheet();
  } else {
    document.addEventListener('DOMContentLoaded', injectHljsStylesheet, { once: true });
  }
})();
