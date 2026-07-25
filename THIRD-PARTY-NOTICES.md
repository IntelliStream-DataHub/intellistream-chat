# Third-Party Notices

IntelliStream Chat bundles or vendors the third-party components below. Each is the property of its
respective copyright holders and is used under the license noted. IntelliStream Chat itself is licensed
under Apache-2.0 (see [`LICENSE`](LICENSE)).

## Vendored front-end assets (shipped in the repo)

These files are checked into `src/main/resources/static/` and served to browsers as-is.

### StompJS
- File: `static/js/vendor/stomp.umd.min.js`
- Project: https://github.com/stomp-js/stompjs
- License: **Apache License 2.0** — https://www.apache.org/licenses/LICENSE-2.0
- Copyright © the StompJS authors.

### highlight.js
- File: `static/js/vendor/highlight.min.js`
- Project: https://github.com/highlightjs/highlight.js
- License: **BSD 3-Clause** — https://github.com/highlightjs/highlight.js/blob/main/LICENSE
- Copyright © 2006, Ivan Sagalaev.

### highlight.js GitHub themes
- Files: `static/css/vendor/highlight-github.min.css`, `static/css/vendor/highlight-github-dark.min.css`
- Part of the highlight.js styles collection.
- License: **BSD 3-Clause** (same as highlight.js above).

### Figtree (font)
- Files: `static/fonts/figtree-*.woff2`, and the upright subsets copied into the Keycloak login
  theme at `keycloak/themes/intellistream/login/resources/fonts/figtree-*.woff2` (the sign-in page
  is served by Keycloak from its own themes directory, so it cannot reach the app's copy)
- Project: https://github.com/erikdkennedy/figtree · https://fonts.google.com/specimen/Figtree
- License: **SIL Open Font License 1.1** — full text in
  [`static/fonts/OFL-Figtree.txt`](src/main/resources/static/fonts/OFL-Figtree.txt), copied
  alongside the theme's fonts as required when the font is redistributed
- Copyright © The Figtree Project Authors.

### Heroicons (UI icon paths)
- File: `templates/fragments/icon-sprite.html` — the `<symbol>` path data.
- Project: https://github.com/tailwindlabs/heroicons
- License: **MIT** — https://github.com/tailwindlabs/heroicons/blob/master/LICENSE
- Copyright © Tailwind Labs, Inc.

  The sprite is hand-assembled rather than imported: each symbol is a Heroicons outline/solid
  path pasted into a shared 24×24 `viewBox` so the whole set scales identically and inherits
  `currentColor`. Some paths are lightly adapted. The MIT notice above covers them.

  MIT text: *Permission is hereby granted, free of charge, to any person obtaining a copy of this
  software and associated documentation files (the "Software"), to deal in the Software without
  restriction, including without limitation the rights to use, copy, modify, merge, publish,
  distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
  Software is furnished to do so, subject to the following conditions: The above copyright notice
  and this permission notice shall be included in all copies or substantial portions of the
  Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
  INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
  AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.*

## Build-time only (Gradle dependencies — not shipped in the browser)

Resolved by Gradle at build time; their jars are on the server classpath, not sent to clients.
Each retains its own license; this is a non-exhaustive summary of the primary runtime libraries.

| Component | License |
|-----------|---------|
| Spring Boot / Spring Framework | Apache-2.0 |
| CommonMark-java (+ gfm-tables, autolink) | BSD 2-Clause |
| jsoup | MIT |
| Apache Lucene | Apache-2.0 |
| Apache Tika (tika-core) | Apache-2.0 |
| Flyway | Apache-2.0 |
| PostgreSQL JDBC Driver | BSD 2-Clause |
| Project Lombok (compile-only) | MIT |
| Google Closure Compiler (build-only, JS bundling) | Apache-2.0 |

For the authoritative, complete dependency license set, run a license report against the Gradle
build (e.g. the `com.github.jk1.dependency-license-report` plugin) — this table is a summary for
convenience.
