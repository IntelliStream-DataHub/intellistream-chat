# Third-Party Notices

ThreadOrbit bundles or vendors the third-party components below. Each is the property of its
respective copyright holders and is used under the license noted. ThreadOrbit itself is licensed
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
- Files: `static/fonts/figtree-*.woff2`
- Project: https://github.com/erikdkennedy/figtree · https://fonts.google.com/specimen/Figtree
- License: **SIL Open Font License 1.1** — full text in
  [`static/fonts/OFL-Figtree.txt`](src/main/resources/static/fonts/OFL-Figtree.txt)
- Copyright © The Figtree Project Authors.

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
| Apache Commons FileUpload | Apache-2.0 |
| Flyway | Apache-2.0 |
| PostgreSQL JDBC Driver | BSD 2-Clause |
| Project Lombok (compile-only) | MIT |
| Google Closure Compiler (build-only, JS bundling) | Apache-2.0 |

For the authoritative, complete dependency license set, run a license report against the Gradle
build (e.g. the `com.github.jk1.dependency-license-report` plugin) — this table is a summary for
convenience.
