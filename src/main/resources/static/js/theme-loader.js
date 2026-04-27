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
