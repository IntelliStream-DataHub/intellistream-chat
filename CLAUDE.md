# Chat — Claude Code project guide

Spring Boot 4 chat application (Slack/Mattermost-style). Read this before making changes; it captures conventions that aren't obvious from the code.

## Stack

- **Java 25** (toolchain), **Spring Boot 4.0.5**, **Gradle Kotlin DSL**.
- **Postgres 18** for storage, **Flyway** for migrations.
- **Keycloak 26** for OAuth2/OIDC.
- **Container runtime: Podman.** Docker is **not** installed. Use `podman compose` (or `podman-compose`) for the local stack. Testcontainers needs `DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock` (start the user socket with `systemctl --user enable --now podman.socket`).
- **STOMP over native WebSocket** (`/ws`) for real-time messages. No SockJS fallback — its `iframe`/`htmlfile`/`jsonp-polling` transports inject inline `<script>` and break the strict CSP.
- **Thymeleaf + vanilla JS only.** No React/Vue/Svelte, no npm bundler. StompJS and highlight.js are vendored under `static/js/vendor/`; everything else is hand-written.
- **CommonMark + jsoup** for Markdown rendering and sanitization.
- **Embedded Apache Lucene** for full-text search, on disk at `./data/lucene`. No ILIKE, no Postgres `tsvector`.

## Common commands

```bash
# Local infra (Postgres + Keycloak with pre-imported realm)
podman compose up -d
podman compose down

# Build / run
./gradlew assemble           # compile + bootJar
./gradlew bootRun            # run the app on :8080
./gradlew test               # all tests (needs Docker for ITs)
./gradlew test --tests 'ai.intellistream.radiance.service.*'   # unit tests only

# Wrapper bootstrap (one time, if gradlew is missing)
gradle wrapper --gradle-version 9.0.0
```

App: http://localhost:8080 · Keycloak: http://localhost:8081 · Test users: `alice/alice`, `bob/bob`.

## Layout

The tree below is a sketch — `service/` and `web/dto/` keep growing; treat the directory listing
as authoritative and CLAUDE.md as a starting orientation.

```
src/main/java/ai/intellistream/radiance/
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
├── db/migration/V1__…V13__…sql           Flyway migrations (channels → DMs → attachments → reactions → ...)
├── META-INF/spring.factories             registers VaultEnvironmentPostProcessor
├── templates/                            landing, channels, conversation, profile, admin
└── static/{css/app.css, js/, img/}       chat.js, conversation.js, profile.js + shared modules
```

## Spring Boot 4 module split — gotchas

Several autoconfigurations that lived inside `spring-boot-autoconfigure` in 3.x have moved to dedicated modules in 4.x. **You add them via Boot artifacts, not the underlying library starter.**

- **Flyway:** add `org.springframework.boot:spring-boot-flyway` (in addition to `flyway-core` + `flyway-database-postgresql`). Without it Flyway is silent at startup, JPA's `ddl-auto=validate` then fails with `Schema validation: missing table`.
- **OAuth2 client / resource server:** autoconfig FQNs are `org.springframework.boot.security.oauth2.{client,server.resource}.autoconfigure.*`. The starters resolve them transitively, but if you exclude autoconfigs in test contexts, use these new FQNs.
- **`@EntityScan`:** moved to `org.springframework.boot.persistence.autoconfigure`.
- **Slice tests:** `@DataJpaTest`, `@AutoConfigureTestDatabase`, etc. were removed. Use `@SpringBootTest(classes = ...)` against a slim test app (see `IntegrationTestApplication`).

## Conventions worth knowing

- **Two security filter chains.** `apiFilterChain` (order 1) handles `/api/**` + `/ws/**` with bearer JWT (stateless, CSRF off). `webFilterChain` (order 2) handles browser pages with oauth2Login (stateful, cookie-based CSRF). Don't merge them. The browser uses the *web* chain for page loads and the *API* chain for `/api/**` calls — those calls therefore need a Bearer token; the JS clients today rely on the OIDC session having seeded one. If you add a new programmatic API client, give it a JWT directly from Keycloak.
- **Public-channel read posture is "anyone authenticated".** `ChannelService.requireMember` short-circuits for `PUBLIC` channels — any logged-in user can read messages, search, download attachments, and react in a public channel without joining it. Joining only matters for write actions (post / edit) and for sidebar grouping. Tighten in `ChannelService.requireMember` if the product calls for member-only reads.
- **Per-user rate limits.** `RateLimiter` is an in-memory sliding-window limiter scoped per (username, action). Today: 30 messages/minute, 60 typing pings/minute, 10 attachment uploads/minute. Replace with a distributed limiter (Bucket4j-with-Hazelcast or Redis) before going multi-instance.
- **Security headers + CSP.** `SecurityConfig` sets a strict CSP (`script-src 'self'`, no inline JS), `X-Content-Type-Options: nosniff`, `frame-ancestors 'none'`, `Referrer-Policy: strict-origin-when-cross-origin`, and HSTS. The CSRF cookie carries `SameSite=Strict`; the JSESSIONID cookie too via `CookieSameSiteSupplier`. Don't reintroduce inline `<script>` blocks — extract to `static/js/` instead.
- **STOMP SUBSCRIBE is authorised.** `StompAuthorizationConfig` rejects `SUBSCRIBE` frames whose destination is `/topic/channels/{id}` (or `/typing`) when the user isn't a member of that channel. Without this, a connected client could snoop on private channels.
- **`CurrentUser`** is the single bridge between Spring Security principals and the domain `User`. It provisions/upserts a `User` row from the OIDC subject the first time it sees a principal. Always go through it; don't read JWT/OidcUser claims in controllers.
- **Channel types.** `PUBLIC` channels are joinable by anyone via `ChannelService.join`. `PRIVATE` channels require `ChannelService.invite` by an admin. The creator becomes the first `ADMIN` member automatically.
- **Slug rule.** `Channel.slug` is generated from the name in `ChannelService.create`: lowercased, non-alphanumerics collapsed to `-`, trimmed to 80 chars. A name with no alphanumerics is rejected.
- **Markdown is rendered server-side** (`MarkdownRenderer`) and sanitized with jsoup `Safelist.basic` plus a small allowlist for headings/tables/code. Clients receive both `bodyMarkdown` and `bodyHtml` — render `bodyHtml` directly with `innerHTML`.
- **Search runs against an embedded Lucene index** at `./data/lucene` (`MessageIndexService`). Writes are pushed by `MessageService` after the surrounding JPA transaction commits, so the index never holds rolled-back data. On startup, `LuceneBootstrap` rebuilds the index from `messages` if the directory is empty (fresh deploy / wiped data dir / cutover from `tsvector`). Queries shorter than 2 chars are treated as empty. The configurable property is `chat.search.lucene-dir`.
- **Search authorization tiers.** `SearchService.searchChannel` requires the standard channel read rules. `searchAllJoined` only spans the viewer's joined channels. `searchEverywhere` (HTTP: `GET /api/search?scope=all`) reads every channel and is gated on Spring authority `ROLE_ADMIN` (Keycloak realm role `admin`).
- **Sidebar.** `SidebarService.sidebarFor(user)` returns public channels ∪ joined channels, sorted joined-first then by name. Each entry carries `joined` and `admin` flags. The right-side `<aside class="sidebar">` in `channels.html` renders this.
- **WebSocket destinations.** Send to `/app/channels/{id}/send`, subscribe to `/topic/channels/{id}`. The server persists, renders Markdown, then broadcasts the `MessageDto`.
- **Open-in-view is off** (`spring.jpa.open-in-view=false`). Touch lazy associations inside `@Transactional` boundaries on the service, never in the controller.

## Testing

- **Unit tests** (`src/test/java/.../service/`, `.../security/`): pure JUnit 5 + Mockito. No Spring context. Cover slug rules, markdown rendering, search input validation, role conversion.
- **Integration tests** (`src/test/java/.../integration/*IT.java`): each feature area has a sibling IT (≈24 of them — `ChannelFlowIT`, `SearchFlowIT`, `MentionInboxIT`, `PresenceFlowIT`, etc.). They `@SpringBootTest(classes = IntegrationTestApplication.class)` against a Testcontainers Postgres. **`IntegrationTestApplication` deliberately excludes** `SecurityAutoConfiguration`, `ServletWebSecurityAutoConfiguration`, `OAuth2ClientAutoConfiguration`, `OAuth2ClientWebSecurityAutoConfiguration`, `OAuth2ResourceServerAutoConfiguration`, and only scans `service` + `repository` + `search`, so tests don't need a live Keycloak. Don't widen the scan in this class.
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
- MIME sniffing on upload uses `URLConnection.guessContentTypeFromStream` which only knows ~10 magic-byte families; swap in Apache Tika if you need broader coverage.
- Distributed rate limiting (current `RateLimiter` is per-process).
