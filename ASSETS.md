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
  (`intellistream.assets.unbundled=true`, set in `application-dev.properties`) serves the original
  source files, so edits show on refresh with **no rebuild**.

## Current bundles

| name           | type | pages                | contents |
|----------------|------|----------------------|----------|
| `chat`         | js   | channels.html        | theme-loader, emoji-data, chat-kit, hovercard, notifications, mention-inbox, presence, idle-logout |
| `conversation` | js   | conversation.html    | emoji-data, chat-kit, hovercard, mention-inbox, presence, idle-logout, conversation |
| `profile`      | js   | profile.html         | presence, idle-logout, profile |
| `admin`        | js   | admin.html           | idle-logout |
| `app`          | css  | every page           | app.css |

**Not bundled:** the vendored libraries (`js/vendor/*` — pre-minified upstream) and the
`js/chat/` ES-module graph (`index.js` + shared/chrome/presence-menu), which loads as a native
`<script type="module">` tag. The vendor highlight.js stylesheets also stay separate —
`theme-loader.js` picks one at runtime based on the user's theme.

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
