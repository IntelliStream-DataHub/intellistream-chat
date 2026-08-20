# Asset pipeline (chat)

Bundles and minifies front-end JS/CSS at build time — JVM only, **no Node**. Ported from
datahub-console's pipeline. A bundle's contents are listed in a *manifest* with `//= require`
directives; the build turns that into one minified file served from the site root
(`/js/...`, `/css/...`).

## How it works

- **Declare** a bundle once in [`assets.gradle`](assets.gradle) (`name`, `type`, `manifest`,
  `output`). JS compiles with **Closure Compiler** (`SIMPLE`); CSS is concatenated with
  comments/whitespace stripped (see "Why not Closure Stylesheets" below).
- **Build** it: `build` / `bootRun` / `bootJar` / `assemble` / `test` all run `buildAssets`,
  producing the bundles and a small `asset-bundles.properties` registry (under `build/`,
  git-ignored).
- **Include** it in a template via the fragment — never a raw `<script>`/`<link>`:
  ```html
  <th:block th:replace="~{fragments/assets :: js('chat')}"/>
  <th:block th:replace="~{fragments/assets :: css('app')}"/>
  ```
- **Dev vs prod:** `AssetService` reads the registry. Prod serves the one minified bundle at a
  **content-versioned URL** (`/js/chat.bundle.min.js?v=<sha256-prefix>`) so browsers can cache
  it indefinitely yet always fetch fresh code after a deploy. Dev
  (`ichat.assets.unbundled=true`, set in `application-dev.properties`) serves the original
  source files, so edits show on refresh with **no rebuild**.

## Current bundles

| name | type | pages | contents (in load order) |
|------|------|-------|--------------------------|
| `chat` | js | channels.html | theme-loader, time-format, session-watch, favicon-alert, emoji-data, chat-kit, hovercard, notifications, mention-inbox, presence, idle-logout, call-transport, calls |
| `conversation` | js | conversation.html | time-format, session-watch, favicon-alert, emoji-data, chat-kit, hovercard, mention-inbox, presence, idle-logout, notifications, call-transport, calls, conversation |
| `profile` | js | profile.html | time-format, session-watch, presence, idle-logout, notifications, profile |
| `admin` | js | admin.html | time-format, session-watch, idle-logout, admin |
| `files` | js | files.html | time-format, session-watch, chat-kit, presence, idle-logout, files |
| `channel-files` | js | channel-files.html | time-format, session-watch, chat-kit, presence, idle-logout, channel-files |
| `saved` | js | saved.html | time-format, session-watch, chat-kit, presence, idle-logout, saved |
| `search` | js | search.html | theme-loader, time-format, session-watch, hovercard, mention-inbox, presence, idle-logout |
| `app` | css | every page | app |

**Not bundled:** the vendored libraries (`js/vendor/*` — pre-minified upstream) and the
`js/chat/` ES-module graph (`index.js` + shared/chrome/presence-menu and the rest), which loads as
a native `<script type="module">` tag. The vendor highlight.js stylesheets also stay separate —
`theme-loader.js` picks one at runtime based on the user's theme.

**Unbundled means unversioned, and that has a caching consequence.** Only the bundles above carry a
`?v=<hash>`; the module graph and the vendor files are ~420 KB served at fixed paths that are
identical before and after a deploy. `StaticAssetCacheConfig` is what keeps that safe: a URL with a
`v` parameter gets `max-age=31536000, immutable`, everything else under `/js/`, `/css/`, `/img/`
and `/fonts/` gets `max-age=60, must-revalidate`. **Do not put a directory-wide `Cache-Control` in
front of the app** — `frontend.md` used to, and `immutable` on the whole of `/js/` meant a changed
`chat/index.js` was served stale for up to thirty days with nothing to indicate it.

## Add a bundle

1. Create a manifest, e.g. `static/js/foo.manifest.js`, listing sources in load order:
   ```js
   //= require foo/base.js
   //= require foo/extra.js
   ```
2. Declare it once in `assets.gradle` → `assetBundles`:
   ```groovy
   [name: 'foo', type: 'js', manifest: 'js/foo.manifest.js', output: 'js/foo.bundle.min.js'],
   ```
3. Include it in the template: `~{fragments/assets :: js('foo')}`.

`assets.gradle` is the only place bundles are declared and manifests are parsed; the Java side
never changes.

## Good to know

- **JS uses Closure `SIMPLE`, not `ADVANCED`** — the scripts publish globals
  (`window.ChatKit`, `window.EMOJI_DATA`, …) that later scripts and the ES-module graph
  reference by name; ADVANCED renames and would break them.
- **Why not Closure Stylesheets for CSS?** It was tried (matching datahub-console's css
  branch) and hard-fails: its GSS grammar predates modern CSS — it cannot parse
  `color-mix(in srgb, var(--accent) 6%, transparent)` and the project is unmaintained. The
  CSS task therefore only strips comments/indentation (~24% smaller), which is safe for any
  future syntax.
- The bundle `<script>` tags are emitted with `defer`: they download in parallel, run in
  document order after parsing, before `DOMContentLoaded` — and spec-deferred module scripts
  (chat/index.js) still run after them.
- Running the app from an IDE without Gradle? Run `./gradlew buildAssets` once first, or
  `AssetService` fails at startup because the registry is missing from the classpath.

## Build manually

```bash
./gradlew buildAssets   # build all bundles + the registry
```
