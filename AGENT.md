# IntelliStream Chat — agent and contributor guide

IntelliStream Chat — Spring Boot 4 chat application (Slack/Mattermost-style). Read this before making
changes; it captures the conventions that aren't obvious from the code.

This file is deliberately tool agnostic. Coding agents look for a project guide under various names
(`AGENT.md`, `AGENTS.md`, `CLAUDE.md`); this is the canonical one, and if your tool wants a different
filename, point it here rather than forking the content. A second copy is a second source of truth,
and the stale one always wins an argument eventually.

Quick starts: `QUICKSTART-COMPOSE.md` (containers) · `QUICKSTART-MANUAL.md` (native + systemd).
Production proxy (nginx/haproxy, sizing, the SameSite gotcha): `frontend.md`.

## Stack

- **Java 25** (toolchain), **Spring Boot 4.1.0**, **Gradle Kotlin DSL**.
- **Postgres 18** for storage, **Flyway** for migrations.
- **Keycloak 26** for OAuth2/OIDC.
- **Container runtime: Podman.** Docker is **not** installed. Use `podman compose` (or `podman-compose`) for the local stack. Testcontainers needs `DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock` (start the user socket with `systemctl --user enable --now podman.socket`).
- **STOMP over native WebSocket** (`/ws`) for real-time messages. No SockJS fallback — its `iframe`/`htmlfile`/`jsonp-polling` transports inject inline `<script>` and break the strict CSP.
- **Thymeleaf + vanilla JS only.** No React/Vue/Svelte, no npm bundler. StompJS and highlight.js are vendored under `static/js/vendor/`; everything else is hand-written.
- **UI font: Figtree** (SIL OFL), self-hosted as variable woff2 under `static/fonts/` — the CSP (`font-src 'self'`) bans font CDNs, so never link fonts.googleapis.com; vendor new fonts the same way.
- **CommonMark + jsoup** for Markdown rendering and sanitization.
- **Uploads are raw request bodies, not multipart.** The file is the body; filename/caption are percent-encoded `X-Upload-*` headers (`RawUpload`). Multipart's boundary scan capped throughput well below line rate, so `commons-fileupload` is gone — don't reintroduce it. Spring's own multipart support is still used for the admin branding form.
- **Embedded Apache Lucene** for full-text search, on disk at `./data/lucene`. No ILIKE, no Postgres `tsvector`.

## Common commands

```bash
# Local infra (Postgres + Keycloak with pre-imported realm)
podman compose up -d
podman compose down

# Build / run
./gradlew assemble           # compile + bootJar (also builds the JS/CSS bundles)
./gradlew bootRun            # run the app on :8080
./gradlew buildAssets        # JS/CSS bundles + registry only (see ASSETS.md)
./gradlew test               # all tests (needs Docker for ITs)
./gradlew test --tests 'ai.intellistream.chat.service.*'   # unit tests only

# Wrapper bootstrap (one time, if gradlew is missing)
gradle wrapper --gradle-version 9.0.0
```

App: http://localhost:8080 · Keycloak: http://localhost:8081 · Test users: `alice/alice`, `bob/bob`.

## Layout

The tree below is a sketch — `service/` and `web/dto/` keep growing; treat the directory listing
as authoritative and AGENT.md as a starting orientation.

```
src/main/java/ai/intellistream/chat/
├── ChatApplication.java
├── attachments/   AttachmentBytes (per-user upload cap resolution)
├── config/        SecurityConfig (two filter chains), WebSocketConfig,
│                  StompAuthorizationConfig, MultipartConfig, VaultEnvironmentPostProcessor,
│                  RegistrationAuthorizationRequestResolver
├── domain/        JPA entities — User, Channel, Message, Conversation,
│                  Attachment / ConversationAttachment, MessageReaction / ConversationReaction,
│                  Poll / PollOption / PollVote, Reminder, MessageMention,
│                  AppSettings, UserPresence, ChannelRead, ChannelType / ChannelRole
├── repository/    Spring Data JPA repos (one per entity)
├── search/        MessageIndexService (embedded Lucene), LuceneBootstrap, LuceneConfig
├── service/       ChannelService, MessageService, ConversationService, SearchService,
│                  SidebarService, MarkdownRenderer, UserService, AvatarService,
│                  AttachmentService, ConversationAttachmentService, ReactionService,
│                  ConversationReactionService, ReadStateService, MentionService,
│                  PollService, PresenceService / PresenceTracker, AppSettingsService
├── slash/         SlashCommandService + commands (PollCommand, RemindCommand, ReminderScheduler)
├── security/      CurrentUser, KeycloakRolesConverter, RateLimiter,
│                  RateLimitExceededException, UploadTooLargeException,
│                  PublicBadRequestException, ResourceNotFoundException
└── web/           REST + Thymeleaf controllers, ChatWebSocketController,
                   ConversationWebSocketController, PresenceEventListener,
                   ApiExceptionHandler, BrandingModelAdvice, UploadParts, dto/

src/main/resources/
├── application.yml
├── db/migration/V1__init.sql             Flyway — consolidated initial schema; add V2+ for future changes
├── META-INF/spring.factories             registers VaultEnvironmentPostProcessor
├── templates/                            landing, channels, conversation, profile, admin
└── static/{css/app.css, js/, img/}       chat/{index,shared,chrome,presence-menu}.js (ES modules),
                                          conversation.js, profile.js, presence.js + shared
```

## Spring Boot 4 module split — gotchas

Several autoconfigurations that lived inside `spring-boot-autoconfigure` in 3.x have moved to dedicated modules in 4.x. **You add them via Boot artifacts, not the underlying library starter.**

- **Flyway:** add `org.springframework.boot:spring-boot-flyway` (in addition to `flyway-core` + `flyway-database-postgresql`). Without it Flyway is silent at startup, JPA's `ddl-auto=validate` then fails with `Schema validation: missing table`.
- **OAuth2 client / resource server:** autoconfig FQNs are `org.springframework.boot.security.oauth2.{client,server.resource}.autoconfigure.*`. The starters resolve them transitively, but if you exclude autoconfigs in test contexts, use these new FQNs. As of Boot 4.1, `OAuth2ResourceServerAutoConfiguration` moved up a package (dropped the `.servlet` suffix — it now lives directly under `...resource.autoconfigure`) and its servlet security filter chain wiring split out into a new `OAuth2ResourceServerWebSecurityAutoConfiguration` under `...resource.autoconfigure.web`, mirroring the client side's existing `OAuth2ClientAutoConfiguration` / `OAuth2ClientWebSecurityAutoConfiguration` split. `IntegrationTestApplication` excludes both.
- **`@EntityScan`:** moved to `org.springframework.boot.persistence.autoconfigure`.
- **Slice tests:** `@DataJpaTest`, `@AutoConfigureTestDatabase`, etc. were removed. Use `@SpringBootTest(classes = ...)` against a slim test app (see `IntegrationTestApplication`).

## Conventions worth knowing

- **Two security filter chains.** `apiFilterChain` (order 1) handles `/api/**` + `/ws/**` with bearer JWT (stateless, CSRF off). `webFilterChain` (order 2) handles browser pages with oauth2Login (stateful, cookie-based CSRF). Don't merge them. The browser uses the *web* chain for page loads and the *API* chain for `/api/**` calls — those calls therefore need a Bearer token; the JS clients today rely on the OIDC session having seeded one. If you add a new programmatic API client, give it a JWT directly from Keycloak.
- **Two access levels on channels.** `ChannelService.requireMember(channel, user)` is the **read** check — it short-circuits for `PUBLIC` channels so any authenticated user can read messages, search, and download attachments. `ChannelService.requireWriteAccess(channel, user)` is the **write** check — it always requires actual membership, regardless of channel type. Posting, editing, replying, reacting, attaching, and inviting all go through `requireWriteAccess`. PRIVATE channels behave the same way under both methods. If you add a new write endpoint, call `requireWriteAccess`, not `requireMember`.
- **Per-user rate limits.** `RateLimiter` is an in-memory sliding-window limiter scoped per (username, action). Today: 30 messages/minute, 60 typing pings/minute, 10 attachment uploads/minute. Replace with a distributed limiter (Bucket4j-with-Hazelcast or Redis) before going multi-instance.
- **Security headers + CSP.** `SecurityConfig` sets a strict CSP (`script-src 'self'`, no inline JS), `X-Content-Type-Options: nosniff`, `frame-ancestors 'none'`, `Referrer-Policy: strict-origin-when-cross-origin`, and HSTS. The CSRF cookie carries `SameSite=Strict`; the JSESSIONID cookie too via `CookieSameSiteSupplier`. Don't reintroduce inline `<script>` blocks — extract to `static/js/` instead.
- **Size the broker's destination cache to your channel count.** `DefaultSubscriptionRegistry` caches destination→subscribers with a default limit of **1024**; past that, every broadcast rescans all subscriptions and delivery collapses (47% of server CPU at 2,000 rooms / 100k connections, half the traffic dropped). `BrokerSubscriptionCacheConfig` raises it — `ichat.ws.subscription-cache-limit`, default 16384. The failure looks exactly like an under-provisioned box, so check this before blaming hardware.
- **STOMP SUBSCRIBE is authorised.** `StompAuthorizationConfig` rejects `SUBSCRIBE` frames whose destination is `/topic/channels/{id}` (or `/typing`) when the user isn't a member of that channel. Without this, a connected client could snoop on private channels.
- **`CurrentUser`** is the single bridge between Spring Security principals and the domain `User`. It provisions/upserts a `User` row from the OIDC subject the first time it sees a principal. Always go through it; don't read JWT/OidcUser claims in controllers.
- **Channel types.** `PUBLIC` channels are joinable by anyone via `ChannelService.join`. `PRIVATE` channels require `ChannelService.invite` by an admin. The creator becomes the first `ADMIN` member automatically.
- **Slug rule.** `Channel.slug` is generated from the name in `ChannelService.create`: lowercased, non-alphanumerics collapsed to `-`, trimmed to 80 chars. A name with no alphanumerics is rejected.
- **UI icons come from the SVG sprite, never from emoji.** `templates/fragments/icon-sprite.html` holds every symbol; use it as `<svg class="icon"><use href="#icon-name"/></svg>` (`.icon` = 20px buttons, `.icon-sm` = 14px inline markers), and add a new 24×24 symbol rather than reaching for a glyph. Emoji as icons look wrong for three reasons: they render in the font's own colours so they ignore the theme and can't be dimmed or turned red for a destructive action, they're drawn differently on every platform, and they vanish entirely on hosts with no emoji font. Real emoji stay emoji — reactions, the picker, and custom status are content. Letterforms (`B`, `I`, `S`, `{ }`) in the composer toolbar are labels, not icons; leave them. Note that a message's action row is rendered in **two** places, server-side in the template and client-side in JS for live updates — change both.
- **JS/CSS ship as build-time bundles** (Closure Compiler for JS; see `assets.gradle` + `ASSETS.md`). Templates include them via `~{fragments/assets :: js('<name>')}` / `css('app')` — never raw `<script>`/`<link>` tags for bundled files. Prod URLs are content-versioned (`?v=<hash>`); the dev profile (`ichat.assets.unbundled=true`) serves the original sources so edits show on refresh. The `js/chat/` ES-module graph and `js/vendor/*` are deliberately not bundled.
- **Markdown is rendered server-side** (`MarkdownRenderer`) and sanitized with jsoup `Safelist.basic` plus a small allowlist for headings/tables/code. Clients receive both `bodyMarkdown` and `bodyHtml` — render `bodyHtml` directly with `innerHTML`.
- **Search runs against an embedded Lucene index** at `./data/lucene` (`MessageIndexService`). Writes are pushed by `MessageService` after the surrounding JPA transaction commits, so the index never holds rolled-back data. On startup, `LuceneBootstrap` rebuilds the index from `messages` if the directory is empty (fresh deploy / wiped data dir / cutover from `tsvector`). Queries shorter than 2 chars are treated as empty. The configurable property is `ichat.search.lucene-dir`.
- **Search authorization tiers.** `SearchService.searchChannel` requires the standard channel read rules. `searchAllJoined` only spans the viewer's joined channels. `searchEverywhere` (HTTP: `GET /api/search?scope=all`) reads every channel and is gated on Spring authority `ROLE_ADMIN` (Keycloak realm role `ichat-admin`).
- **Sidebar.** `SidebarService.sidebarFor(user)` returns public channels ∪ joined channels, sorted joined-first then by name. Each entry carries `joined` and `admin` flags. The right-side `<aside class="sidebar">` in `channels.html` renders this.
- **WebSocket destinations.** Send to `/app/channels/{id}/send`, subscribe to `/topic/channels/{id}`. The server persists, renders Markdown, then broadcasts the `MessageDto`.
- **Open-in-view is off** (`spring.jpa.open-in-view=false`). Touch lazy associations inside `@Transactional` boundaries on the service, never in the controller.
- **The STOMP channel executors are load-bearing.** `WebSocketConfig` sets them *unconditionally* via `registration.executor(...)`, with sizes constructor-injected (not `@Value` fields — those can be unset when the configurer callback runs). A missing inbound executor silently lands every `@MessageMapping` on the single-threaded heartbeat scheduler, which caps the whole server at one message in flight. `StompChannelDiagnostics` logs the resolved executors at startup; check that line before trusting any throughput number. See `scalability.md`.
- **The message send path is the hot path** and is deliberately query-free: the domain `User` comes from the STOMP session (cached at CONNECT by `StompAuthorizationConfig`), the channel and write-access decision from `ChannelAccessCache`, and mentions from what `syncMentions` already resolved. If you add work to `ChatWebSocketController.send` or `MessageService.postWithMentions`, check `benchmark/write-stages.sh` afterwards.
- **`ChannelAccessCache` rests on two invariants.** Only *positive* access decisions are cached, and membership is add-only (nothing removes a member short of deleting the channel, which evicts). `Channel` is immutable — no setters, enforced by `ChannelImmutabilityTest`, because the cached instance is what authorizes STOMP SUBSCRIBE and `requireMember` short-circuits to allowed for PUBLIC channels. **Don't add a bare setter to `Channel`**; route a change through `ChannelService` and call `evictChannel`, and call `evictMember` from any leave/kick path.
- **Write-behind INSERT batching is on by default** (`MessageWriteBehind`, `ichat.write-behind.enabled`). Ids are pre-allocated from `messages_id_seq`; rows are batch-inserted a few ms later by flushers **sharded by channel** (so per-channel order holds). A message is broadcast and indexed **only after its batch commits** — never publish from the accept path, or a failed INSERT becomes a message everyone saw and nobody has. Register post-commit work via `Posted.whenDurable`. Bodies containing `@` take the transactional path (mention rows need the FK).
- **`post`/`postWithMentions` are durable on return; `postBuffered` is not.** Anything that inserts a row referencing the message (attachments, polls, reminders) must use the former. `postBuffered` is the WebSocket send path only.
- **The composer renders sent messages optimistically.** Since broadcast waits for the commit, the sender's own message is drawn immediately in a `sending` state and reconciled when the broadcast returns, matched on a `clientId` round-tripped through `SendMessageRequest` → `MessageEvent`. Don't match echoes on body text — duplicates break it.

## Testing

- **Unit tests** (`src/test/java/.../service/`, `.../security/`): pure JUnit 5 + Mockito. No Spring context. Cover slug rules, markdown rendering, search input validation, role conversion.
- **Integration tests** (`src/test/java/.../integration/*IT.java`): each feature area has a sibling IT (≈24 of them — `ChannelFlowIT`, `SearchFlowIT`, `MentionInboxIT`, `PresenceFlowIT`, etc.). They `@SpringBootTest(classes = IntegrationTestApplication.class)` against a Testcontainers Postgres. **`IntegrationTestApplication` deliberately excludes** `SecurityAutoConfiguration`, `ServletWebSecurityAutoConfiguration`, `OAuth2ClientAutoConfiguration`, `OAuth2ClientWebSecurityAutoConfiguration`, `OAuth2ResourceServerAutoConfiguration`, and only scans `service` + `repository` + `search` + `moderation`, so tests don't need a live Keycloak. **Don't widen the scan** means don't add `web` or `config` — those drag in the controllers and the security autoconfiguration this class excludes on purpose. Another *service-layer* package is fine.
- **No H2.** Tests must use the real Postgres via Testcontainers; H2 won't accept the production schema. The Lucene index in tests is wired via a `@Bean MessageIndexService` in `IntegrationTestApplication` that points at a fresh `Files.createTempDirectory(...)` per Spring context.
- **Testcontainers + Podman.** Before running `./gradlew test`, expose the Podman socket: `systemctl --user enable --now podman.socket`, then `export DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock`. If Ryuk misbehaves, fall back to `TESTCONTAINERS_RYUK_DISABLED=true`.
- When adding a feature, default to: a unit test for any pure-logic branch + a new IT under `integration/` (or an addition to a sibling IT) for anything DB-shaped.

## When you're tempted to…

- **Add an SPA framework.** Don't. The user has explicitly chosen Thymeleaf + vanilla JS. Render new UI server-side; reach for plain `fetch` + DOM updates in `static/js/`.
- **Switch search to ILIKE.** Don't. The user requested `tsvector` first. Stick with the existing native query and the generated column.
- **Bypass `CurrentUser` and read claims yourself.** Don't. Controllers should call `currentUser.resolve(principal)` and operate on the domain `User`.
- **Add an in-memory test DB.** Don't. Use Testcontainers; H2 will silently accept invalid SQL for `tsvector`.
- **Skip migrations and use `ddl-auto=update`.** Don't. `ddl-auto=validate` is intentional — schema changes must go through Flyway.

## Things still missing (roadmap, not blockers)

- Highlighted snippets in search results.
- Permission UI for promoting/demoting admins (the service supports it; no controller wired yet).
- E2E test with a real Keycloak (Testcontainers Keycloak module).
- OWASP `dependency-check` Gradle plugin for CVE scanning — opt in when you add the build-side dep policy.
- MIME sniffing on upload goes through Apache Tika (`AttachmentBytes.sniffContentType`). Filename hints are passed so Tika can disambiguate ZIP-based formats (docx vs xlsx vs odt). The previous `URLConnection.guessContentTypeFromStream` only knew ~10 families and mis-typed common formats.
- Distributed rate limiting (current `RateLimiter` is per-process).
