# IntelliStream Chat — Security, Bug-fix & Open-Source Readiness Plan

Generated 2026-07-23 from a five-track audit (web/API security, auth+WebSocket+config
security, backend bugs/concurrency, front-end JS/XSS, open-source readiness), plus a
cross-project comparison against the sibling `datahub-api` (mature raw-WebSocket robustness
fixes) and `datahub-cleanup` (scheduled orphan-reconciliation) codebases — the `WS-*` and
`CLEAN-*` items come from that comparison. Every finding below carries a `file:line` anchor; the
load-bearing ones were re-verified against the code before landing here (noted **[verified]**).
Findings marked **[2 auditors]** were reported independently by two tracks.

> **On the datahub comparison:** `datahub-api` uses *raw* Spring `WebSocketHandler`s while this
> app uses *STOMP* over the simple broker, so its code isn't copy-pasteable — the transferable
> items are patterns (idempotent teardown, heartbeats, per-message error isolation, reconnect
> backfill). Its raw-WS-specific work (per-connection Pulsar consumers, virtual-thread receive
> loops, a `TenantContext` ThreadLocal leak) does **not** apply: Spring owns all session threads
> and teardown here, and there's no per-connection external resource to drain.

Work top-down: **P0** blocks the public release, **P1** should land before or immediately
after it, **P2** is hardening/polish. The **Open-source prep** and **Good news** sections are
separate workstreams.

Legend: `[ ]` todo · severity in each heading · `file:line → fix`.

---

## P0 — Release blockers

### SEC-1 · Rotate & un-commit the Keycloak client secret  🔴 critical  **[2 auditors, verified]**
- `keycloak/realm.json:17` commits the real confidential-client secret `DAJpMx…`, and
  `application-prod.properties:11` re-introduces it as a **default**
  (`${KEYCLOAK_CLIENT_SECRET:DAJpMx…}`) — directly contradicting `application.yml:38`, which
  deliberately dropped the default so prod fails fast.
- Git history contains **two** distinct secrets (`chat-secret`, `DAJpMx…`).
- **Fix:** (1) regenerate the secret in Keycloak; (2) set `application-prod.properties` to
  `${KEYCLOAK_CLIENT_SECRET:}` (empty → fail-fast); (3) replace the `realm.json` value with a
  placeholder injected at import time; (4) since history exposes secrets, either rewrite
  history (`git filter-repo`) before publishing **or** treat both secrets as burned and ensure
  no deployment uses them.

### SEC-2 · Prod profile defaults auth endpoints to plaintext HTTP  🟠 high
- `application-prod.properties:3-4` defaults both the OIDC provider `issuer-uri` and the
  resource-server `jwt.issuer-uri` to `http://localhost:8081/...`. A `prod` run with no env
  override does OIDC discovery, JWKS fetch and token validation over unauthenticated HTTP.
- **Fix:** remove the http defaults (require `KEYCLOAK_ISSUER_URI`) or mandate `https` in prod.

### BUG-1 · Editing a message that keeps a mention throws 500  🟠 high  **[verified]**
- `service/MentionService.java:71-80`: `syncMentions` does `deleteAllByMessage()` then
  `save(new MessageMention(...))` with no flush; `MessageMention.id` is `IDENTITY`, so the
  INSERT executes immediately while the DELETE is still queued → re-inserting the same
  `(message_id, user_id)` violates `uk_message_mentions` and rolls back `MessageService.edit`.
  `PollService.castVote:108-112` already flushes for exactly this trap.
- **Fix:** `mentionRepo.flush()` after the delete, or diff old-vs-new mention sets and only
  apply the delta. Add an IT that edits a message while keeping one `@mention`.

### SEC-3 · Purge maintainer LAN IP so the default quickstart works for outsiders  🟠 high
- A maintainer's LAN IP was hardcoded in `docker-compose.yml:29,32`, `keycloak/realm.json:19,23,30`,
  `scripts/seed-vault.sh:35`, `README.md`, `security_plan.md`. `podman compose up -d` fails
  with a bind error on any host that doesn't own that IP, so the documented "two-command"
  quickstart is broken for every new user.
- **Fix:** default everything to `127.0.0.1`/`localhost`; move the LAN bind into a gitignored
  `compose.override.yml`. (Realm redirect URIs already include the `localhost` variants.)

### DOC-1 · README setup is stale post-rename & setup-breaking  🟠 high
- `README.md` still references the old `chat` realm/client and `default-roles-chat`
  (lines 88, 645, 661, 696) while `keycloak/realm.json` is realm `ichat-realm` / client `ichat-client`; the
  repo-layout section claims `V1__…V13__…sql` but only `V1__init.sql` exists; line 895 claims
  "every source file carries the Apache header" (false — see OSS-2).
- **Fix:** correct realm/client names, migration layout, and the header claim.

---

## P1 — Fix before or immediately after release

### Security

- [x] **SEC-4 · Poll voting uses the read check** 🟡 medium **[2 auditors, verified]** —
  `web/PollRestController.java:120` `requireMembership` calls `channelService.requireMember`
  (short-circuits `true` for PUBLIC), so a non-member can mutate poll tallies in any public
  channel. `ReactionService.addReaction:44` correctly uses `requireWriteAccess`. → Use
  `requireWriteAccess` in `castVote`/`removeVote`; keep `requireMember` for the `GET`.
- [x] **SEC-5 · Username-enumeration oracle on group/DM/invite** 🟡 medium —
  `web/ConversationRestController.java:138-150` `createGroup` echoes
  `"Unknown user(s): " + names` verbatim, unbounded (≤100/req), unthrottled; same for
  `/direct`, `/{id}/members`, `/api/channels/{id}/invite`. Defeats the 120/min throttle those
  handles are otherwise protected by. → Rate-limit these mutations per user and return a
  generic "one or more members not found."
- [x] **SEC-6 · DM reaction/edit/delete are not rate limited** 🟡 medium **[verified]** —
  `web/ConversationRestController.java:205-240` `addReaction`/`removeReaction`/`editMessage`/
  `deleteMessage` write + fan out over STOMP with no `rateLimiter.tryAcquire`, unlike their
  channel twins in `MessageRestController` (and unlike dm-send/upload/download in the same
  file). → Add the `reaction-toggle`/`msg-edit`/`msg-delete` limiter calls.
- [x] **SEC-7 · CSP `connect-src` wildcards weaken the policy** 🟡 medium **[verified]** —
  `config/SecurityConfig.java:114` sets `connect-src 'self' ws: wss:`; the scheme wildcards
  permit outbound WebSocket to any host (exfil channel) and contradict the adjacent comment.
  Same-origin `wss://…/ws` is already covered by `'self'`. → Drop `ws: wss:`.

### WebSocket robustness (from datahub-api comparison)

- [x] **WS-1 · One throttled/oversized message tears down the whole STOMP connection** 🟠 high
  **[verified]** — `web/ChatWebSocketController.java:81` throws `RateLimitExceededException` from
  the `@MessageMapping` send (and `@Valid @Size(8000)` breaches propagate too), and there is
  **no** `@MessageExceptionHandler` / `StompSubProtocolErrorHandler` anywhere in the codebase.
  Spring turns the unhandled exception into a STOMP `ERROR` frame, which per spec closes the
  entire connection — so hitting the 30/min send cap or pasting a >8000-char message drops every
  subscription and flips the sender offline until reconnect. datahub-api instead treats
  per-message failures as data (logs / sends an error frame, socket stays open). → Add a
  `@MessageExceptionHandler` that routes rate-limit/validation errors to `/user/queue/notices`
  and keeps the session open — mirroring the pattern already used for slash-command errors
  (`ChatWebSocketController.java:90-97`). Same for `ConversationWebSocketController`.
- [x] **WS-2 · No STOMP heartbeat or idle timeout → phantom-online sessions leak** 🟡 medium
  **[verified]** — `config/WebSocketConfig.java` sets neither a broker heartbeat nor a
  `ServletServerContainerFactoryBean` `maxSessionIdleTimeout`, so a half-open TCP (client
  vanished with no FIN — sleep, dropped wifi) never fires `SessionDisconnectEvent`: the session
  leaks and the user shows "online" forever. This is also the missing mechanism that makes
  BUG-7's disconnect cleanup fire at all. datahub-api pairs a 15s server PING with a 45s idle
  timeout for exactly this. → `enableSimpleBroker(...).setHeartbeatValue(new long[]{10000,10000})
  .setTaskScheduler(scheduler)` and register a `ServletServerContainerFactoryBean` with
  `setMaxSessionIdleTimeout`.

### Bugs

- [x] **BUG-2 · Non-unique username mis-routes private notices & breaks login** 🟠 high
  **[2 auditors, verified]** — `db/migration/V1__init.sql:20`: `users.username` has no unique
  constraint (only `subject`). `UserService.upsert:119-140` rewrites username every login with
  no collision check and `sanitizeUsername` collapses `bob@a.com` & `bob@b.com` → `bob`.
  Private notices route by username (`ChatWebSocketController.java:94`
  `convertAndSendToUser(user.getUsername(), …)`), and mentions/presence/`/remind` all key on it
  → one collision cross-delivers private messages and throws on login. → V2 migration adding
  `unique index on users(lower(username))`; make `sanitizeUsername` collision-proof (subject
  suffix) with the existing `DataIntegrityViolationException` catch-and-retry.
- [x] **BUG-3 · No message catch-up after STOMP reconnect** 🟠 high — `chat/index.js:591-635`,
  `conversation.js:343-378`: the simple broker has no replay, so every message sent during a
  network blip / laptop sleep is missing until a full reload, silently. → In `onConnect` (when
  not the first connect) fetch `/api/channels/{id}/messages?after=<last data-created-at>` and
  append via the existing de-duping path. *(datahub-api solves the same problem with durable
  Pulsar cursors + ack/nack; the simple broker can't replay server-side, so this HTTP backfill
  is the STOMP-world equivalent — gate the read with the same channel read check.)*
- [x] **BUG-4 · Edit button vanishes on live/paged channel messages** 🟠 high **[verified]** —
  `chat/index.js:945-1003` `buildMessageLi` never sets `li.dataset.bodyMarkdown`, but
  `attachActions`/`startEdit`/`replaceMessageDom` all depend on it, so every WS-appended and
  infinite-scroll message loses Edit and misdetects the first reaction as a body edit.
  `conversation.js:84` does it correctly. → Add
  `li.dataset.bodyMarkdown = msg.bodyMarkdown || '';`.
- [x] **BUG-5 · Broken mention deep-links** 🟠 high — `chat/index.js:578`,
  `mention-inbox.js:72` build `/channels/{id}#m-{id}`, but the permalink consumer matches
  `#m=<id>` and needs `?m=<id>` for server context-around; clicking a mention lands at the
  channel tail with no highlight. → Build like `permalinkFor` (index.js:1892):
  `'/channels/'+ch+'?m='+id+'#m='+id`.
- [x] **BUG-6 · Reminder batch aborts on one bad row** 🟡 medium —
  `slash/ReminderScheduler.java:110-133`: `fireOne` catches `messageService.post`'s exception,
  but `post` is `@Transactional` and has already marked the tx rollback-only, so `fireOne`'s
  commit throws `UnexpectedRollbackException` into `runOnce`'s loop → all later due reminders
  slip a poll cycle. → Move try/catch into `runOnce` (let `fireOne` throw); post via a separate
  `REQUIRES_NEW` method so the batch tx is never poisoned.
- [x] **BUG-7 · Presence counter corrupted by duplicate disconnects** 🟡 medium —
  `service/PresenceTracker.java:43-62`: Spring may publish `SessionDisconnectEvent` more than
  once per session; blind counter decrement drives a two-tab user offline while a live session
  remains, and the connect/disconnect race can drop an online user from `sessions`. → Track
  `ConcurrentHashMap<String,Set<String>>` of session ids keyed off `event.getSessionId()`,
  mutate via `compute()` so add/remove/emptiness-removal are atomic and a repeat disconnect for
  an already-removed session is inert. *(datahub-api uses the same guarded-idempotency approach —
  `compareAndSet` teardown guard in `DatapointListenSession.stop()` — for exactly this class of
  double-fire.)*
- [x] **BUG-8 · Upload captions bypass mention + search indexing** 🟡 medium —
  `service/AttachmentService.java:128-130` saves the caption via
  `messageRepository.save(new Message(...))`, skipping `syncMentions` and the afterCommit
  Lucene write that `MessageService.post` performs → captions are unsearchable and their
  `@mentions` never notify, until a bootstrap rebuild silently changes behavior. → Route the
  caption through `MessageService.post` (or replicate syncMentions + index write).
- [x] **BUG-9 · Channel delete leaks attachment files & Lucene docs** 🟡 medium —
  `service/ChannelService.java:132-136` `destroy` is a bare `delete(channel)`; DB cascades rows
  but nothing removes the on-disk attachment files (permanent disk leak) or the channel's
  Lucene docs (index bloats forever; `rebuildIfEmpty` never reconciles a non-empty index).
  `MessageService.delete` does both cleanups. → Collect message ids + storage keys, afterCommit
  delete index docs (add a `channelId` term / `deleteByChannel`) and files. *(This is the exact
  shape datahub-cleanup's `DeletedFilePurgeTask` uses: unlink file, then delete row; CLEAN-1
  below adds the scheduled backstop for the crash-in-between case.)*
- [x] **BUG-10 · DM attachment files orphaned on message delete** 🟡 medium —
  `service/ConversationService.java:151-160` `deleteMessage` removes rows (FK cascade) but never
  deletes the files on disk; the channel twin (`MessageService.delete:296-313`) does. → Look up
  the message's `ConversationAttachment` storage keys and register an afterCommit file cleanup.
- [x] **BUG-11 · Reminder `at`-times resolve in server timezone** 🟡 medium —
  `slash/RemindCommand.java:142` uses `ZoneId.systemDefault()`, so `/remind … at 14:00` fires
  at the wrong local time for any non-server-TZ user; also `in 99999999999999d` →
  `Duration.ofDays` `ArithmeticException` → raw 500. → Store/collect a per-user timezone;
  clamp the duration and map `ArithmeticException` to the friendly usage error.
- [x] **BUG-12 · Backgrounded tab wipes unread/mention state** 🟡 medium —
  `chat/index.js:702-708` POSTs `/read` on every `created` event with no
  `visibilityState`/`hasFocus` check (the adjacent mention-notify call has one), so a tab left
  open overnight clears sidebar badges, the bell, and unseen mention rows. → Only POST `/read`
  when the tab is visible+focused; post one catch-up on `visibilitychange→visible`.
- [x] **BUG-13 · Timezone-split day grouping & timestamps** 🟡 medium —
  server renders `data-day`/`<time>` in the *server* zone (`channels.html:308,333`) while the
  client computes them in the *browser* zone (`chat-kit.js:67-69`), so cross-TZ viewers get
  wrong day dividers/grouping and disagreeing timestamps between old and live messages. →
  Hydrate day keys/times client-side from `data-created-at`, or format server-side per-user TZ.
- [x] **BUG-14 · Reacting deletes an in-progress edit form** 🟡 medium —
  `chat/index.js:1714-1757`, `conversation.js:159-187`: `replaceMessageDom` removes
  `.message-edit` on any `updated` broadcast, and reaction toggles arrive as `updated`, so a
  reaction from anyone destroys the author's unsaved edit draft. → If an edit form is present,
  refresh reactions/attachments but preserve the form + textarea value.
- [x] **BUG-15 · Live append force-scrolls readers to the bottom** 🟡 medium —
  `chat/index.js:1026` `messagesEl.scrollTop = scrollHeight` unconditionally, yanking a user
  reading history down on every new message. → Only auto-scroll when already near the bottom;
  otherwise show a "new messages" pill.
- [x] **BUG-16 · `appendMessage` has no de-dupe → duplicate rows** 🟡 medium **[verified]** —
  `chat/index.js:1006-1028` lacks the id guard `conversation.js:49` has, so a broadcast that
  races the final infinite-scroll page renders a duplicate `<li>`. → Add the same
  `querySelector('li.message[data-id=…]')` guard.

---

## P2 — Hardening & polish (lower severity)

### Security hardening
- [x] **SEC-8 · No rate limit on markdown preview** low — `web/PreviewRestController.java:41`
  runs the full CommonMark+jsoup+mention pipeline per call, no limiter. → Add ~60/min per user.
- [x] **SEC-9 · No rate limit on channel creation** low — `web/ChannelRestController.java:94`.
  → Modest per-user create limit.
- [x] **SEC-10 · Presence mutations & GET batch unbounded** low —
  `web/PresenceRestController.java:72-108` broadcast without a limiter; `:66-70` splits an
  uncapped username list into an IN-query. → Rate-limit mutations; cap the batch length.
- [x] **SEC-11 · No SUBSCRIBE-frame rate limit** low — `config/StompAuthorizationConfig.java`
  runs 1-2 DB queries per SUBSCRIBE with no per-session limit. → Sliding-window cap on SUBSCRIBE.
- [x] **SEC-12 · `forward-headers-strategy: framework` trusts X-Forwarded-* unconditionally**
  low — `application.yml:64`; safe only while bound to loopback. → Keep loopback bind, or switch
  to `native` with `server.tomcat.remoteip.internal-proxies`; fix the misleading class comment.
- [x] **SEC-13 · Realm enables direct-access (ROPC) grant** low — `keycloak/realm.json:27`
  `directAccessGrantsEnabled: true` + committed secret = direct token minting. → Disable for the
  confidential client in the shipped realm.
- [x] **SEC-14 · nginx example disables body-size cap** low — `nginx_example.conf:58`
  `client_max_body_size 0` + admin uploads = `UNLIMITED`. → Ship a concrete cap (e.g. `500m`).
- [x] **SEC-15 · Presence is globally visible to all authenticated users** low —
  `/topic/presence` isn't scoped to shared channels. → Confirm intended; if not, scope broadcasts.

### Bug polish
- [x] **BUG-17 · Check-then-act insert races surface as 500s** low —
  `ReadStateService.java:53-61`, `ChannelService.java:83-100` (join/invite),
  `ReactionService.java:50-52`, `PollService.java:103-113`, `ConversationService.java:104-111`:
  concurrent idempotent ops both insert; the loser throws `DataIntegrityViolationException` →
  500. `directBetween`/`upsert` show the correct catch-and-reread. → Wrap in catch-and-reread or
  `on conflict do nothing`.
- [x] **BUG-18 · `openThread` has no stale-response guard** low — `chat/index.js:1959-1972`:
  two quick thread clicks race; the last response to land wins regardless of last click. → Use a
  monotonic request id like search/preview already do.
- [x] **BUG-19 · Avatar-preview blob leak + optimistic preview** low — `profile.js:87-99,117`:
  `createObjectURL` never revoked, and the preview swaps before server validation (a rejected
  upload shows the new picture beside the error). → Revoke on replace; swap only on 2xx.
- [x] **BUG-20 · Keyset pagination lacks an id tie-break** low — `MessageService.java:96-150`
  orders by `createdAt` only; same-timestamp messages straddling a page are skipped and
  `around()` omits the exact-anchor row. → Order by `(createdAt, id)` and paginate the composite.
- [x] **BUG-21 · afterCommit index write has an unrecoverable loss window** low —
  `MessageService.java:312-345`, `LuceneBootstrap.java:49-64`: a crash between DB commit and the
  index write permanently desyncs the index (rebuild only fires on a fully empty index); a
  throwing `deleteAll` also skips the file cleanup that follows it. → try/catch-log each
  afterCommit body; add a periodic/admin reconciliation by max message id.
- [x] **BUG-22 · RateLimiter prune race under-enforces at the sweep** low —
  `security/RateLimiter.java:54-84`: `removeIf` checks emptiness under the deque lock but removes
  from the map outside it, losing a concurrent event. → Verify-and-reinsert after `addLast`, or
  remove via `windows.compute`.
- [x] **BUG-23 · `demote` last-admin TOCTOU** low — `ChannelService.java:115-130`: two admins
  demoting each other can both pass the last-admin guard → zero-admin channel. → Pessimistic lock
  on the membership rows before counting.
- [x] **BUG-24 · LuceneBootstrap loads the whole messages table into heap** low —
  `LuceneBootstrap.java:53-58` `findAll()` + lazy per-author init → multi-GB spike on a fresh
  deploy at scale. → Keyset-stream with a flat author-joined projection.

### Scheduled cleanup & reconciliation (from datahub-cleanup comparison)

These are backstops for the resource leaks above — orphan sweeps that catch what slips through
the write-path crash windows. Model on datahub-cleanup's task family; collapse its
multi-tenant loops to a single un-routed pass (chat is single-tenant, one data dir each).

- [x] **CLEAN-1 · Scheduled orphan-attachment sweep** low **[backstop for BUG-9/10]** —
  even with the write-path fixes, a crash between DB commit and the afterCommit `deleteFiles`
  leaves an orphan. Add a `@Scheduled` task that lists `./data/attachments`, builds the live key
  set = `attachments.storage_key ∪ conversation_attachments.storage_key`, and deletes files not
  in it whose mtime is older than a grace window (~24h, so a mid-upload file pre-commit is
  spared). **Abort the run if either DB query fails** — never delete against a partial live set
  (datahub's `OrphanTenantFolderCleanupTask` refuses to act on an empty/unreadable live set).
- [x] **CLEAN-2 · Avatar orphan sweep + fix the replace-path leak** low **[NEW leak]** — the
  audit's file-leak review of attachments also applies to avatars: `AvatarService`'s
  replace-avatar path only best-effort `deleteIfExists`es the previous key in an afterCommit hook
  (leaks the old file on any crash/IO error between commit and hook), and nothing cleans up when
  a `User` is deleted. → Same sweep family: list `./data/avatars`, live set = non-null
  `users.avatar_storage_key`, delete unreferenced files older than the grace window.
- [x] **CLEAN-3 · Periodic Lucene↔DB reconciliation** low **[backstop for BUG-21]** — replace
  rebuild-only-when-empty with a `@Scheduled` reconcile: pull the id set from `messages`, diff
  against the index, `index(...)` the missing and `deleteAll(...)` the stale. Needs a new
  "enumerate all doc ids" on `MessageIndexService` (it has `index`/`deleteAll`/`isEmpty` but no
  id scan). Skip the run if the DB read fails.
- [x] **CLEAN-4 · Cleanup observability: dry-run + enabled flags + summary logging** low
  **[NEW]** — the app has zero cleanup observability. Bind `chat.cleanup.*`
  `@ConfigurationProperties` (`enabled`, `dry-run`, `grace`), **default `dry-run=true`**, and
  have each sweep log "[dry-run] would delete …" per item plus a per-run count, so an operator
  can watch the orphan/desync backlog before arming destructive deletes.
- [ ] **CLEAN-5 · Single-instance guard before these sweeps ship** low **[NEW caveat]** —
  `@EnableScheduling` runs on every node and AGENT.md already flags per-process state, so two
  nodes would race the purges/reconcile. Only matters once the app runs multi-node — a Postgres
  advisory-lock guard is the fix, deferred with the rest of horizontal-scaling (see the
  `horizontal-scalability-plan` memory). Single-node deploys can ignore this.

---

## Open-source release prep

### Blockers
- [x] **OSS-1 · Community health files** — add `SECURITY.md` (private vuln-reporting channel —
  priority for a self-hosted chat app), `CONTRIBUTING.md` (distill README's testing/dev-stack
  sections), `CODE_OF_CONDUCT.md`. `LICENSE` (Apache-2.0) already exists.
- [x] **OSS-2 · Add missing license headers** — 9 Java + 13 JS first-party files lack the
  Apache header (README claims 100% coverage). Java: `StompAuthorizationConfig`,
  `ConversationReaction`, `MessageReaction`, `ConversationReactionRepository`,
  `MessageReactionRepository`, `ConversationReactionService`, `ReactionService`,
  `ReactionGroupDto`, `ReactionRequest` (+ `ReactionFlowIT` in tests). JS: `conversation.js`,
  `hovercard.js`, `idle-logout.js`, `mention-inbox.js`, `notifications.js`, `presence.js`,
  `profile.js`, `theme-loader.js`, `emoji-data.js`, and the four `*.manifest.js`.
- [x] **OSS-3 · Third-party notices** — `static/js/vendor/stomp.umd.min.js` carries no license
  banner (StompJS = Apache-2.0); `static/fonts/OFL-Figtree.txt` is a 3-line pointer but OFL 1.1
  requires the full text to accompany the fonts. Add `THIRD-PARTY-NOTICES.md` covering StompJS,
  highlight.js (BSD-3 + its GitHub themes) and the full Figtree OFL.

### Important
- [x] **OSS-4 · Add CI** — no `.github/workflows`. Add a GitHub Actions job running
  `./gradlew test` (Testcontainers works out-of-box on `ubuntu-latest`; the podman socket dance
  is local-only). Gradle wrapper is already committed.
- [x] **OSS-5 · Ship `application-dev.properties.example`** — the real file is gitignored, but
  README/ASSETS/application.yml all reference it and `bootRun` force-activates `dev`. Commit a
  sanitized template (no LAN IP) documenting `assets.unbundled`, `dev-tools.enabled`,
  `allowed-origins`, issuer overrides.
- [x] **OSS-6 · Refresh or trim `security_plan.md`** — README links it 3× as the hardening
  checklist, but it references `com.example.chat.*` paths (two renames stale), a removed
  `CHAT_SECURITY_COOKIE_SECURE` env var, and the LAN IP. Update names or trim to the still-true
  checklist. (Consider whether this internal doc should ship at all.)
- [x] **OSS-7 · Rework the README "AI-slop" framing** — the opening says "this is still
  considered AI-slop, and there are bugs, even serious bugs" plus a Teams rant; it's the first
  thing every visitor reads. Keep the candor deliberately or rework it.
- [x] **OSS-8 · Relocate the root `index.html`** — it's a 707-line standalone marketing page for
  intellistream.ai, unreferenced by the build (the app's landing page is `templates/landing.html`).
  Move to `docs/`/`website/` or its own branch so the repo root isn't a marketing artifact.

### Nice-to-have
- [x] **OSS-9 · Fix test-count claims** — README says "~190 tests / 21 classes"; the tree has
  ~37 test files. Recount or drop the numbers.
- [ ] **OSS-10 · Prune stale branches & confirm publish point** *(your call — destructive / a decision)* — `main` is ahead of the private
  `origin`; stale branches (`code-audit-fixes`, `landing-page`, `presence-search-and-polish`,
  `rebrand-and-openbao-secrets`) exist; history exposes author emails (normal for OSS — confirm).
- [x] **OSS-11 · `.gitignore` whitelists `!.env.example` but none exists** — add one or drop the
  rule.

---

## Good news — verified solid (no action needed)

These were probed and found correct; recording them so they aren't re-litigated.

- **No DOM-XSS.** Every `innerHTML`/template sink checked: `bodyHtml` and `/api/preview` are
  jsoup-sanitized (by design); search snippets are HTML-escaped server-side by Lucene's
  `SimpleHTMLEncoder`; hovercard interpolations use `escapeHtml`/`escapeAttr`; all other
  user strings flow through `textContent`/`dataset`/property assignment.
- **Per-frame WebSocket authorization is correct** (confirmed against datahub-api's design).
  SUBSCRIBE is re-authorized against live membership on every frame
  (`StompAuthorizationConfig.java:82-104`) and SEND at the service layer, with `Principal` passed
  explicitly and the resolved `User` cached on session attributes — never read from a WS-thread
  `SecurityContext` (which is empty). Keep it per-frame; never switch to connect-time-only authz.
- **Authorization model is sound.** SEND (`/app/**`) and SUBSCRIBE are both membership-checked;
  `requireMember` (read) vs `requireWriteAccess` (write) is applied correctly everywhere **except
  polls** (SEC-4); IDOR is prevented (every by-id service resolves the parent + checks access);
  search authz tiers (channel/joined/admin) are correct; `/admin/**` is `hasRole('ADMIN')`;
  admin realm-role mapping requires literal `ichat-admin` and ignores Keycloak's built-in `admin`.
- **File handling is safe.** On-disk names are server UUIDs (no path traversal), `resolve()` is
  `startsWith(root)`-checked, downloads `URLEncoder`-escape filenames (no header injection),
  uploads are Tika-sniffed + `nosniff` + inline only for non-SVG images, sizes capped mid-stream.
- **No SSRF** (preview renders markdown, fetches nothing). **No SQL/Lucene injection** (fuzzy
  parser disables wildcard/prefix/regexp; no string-built SQL).
- **CSRF posture is correct** (stateless bearer API chain + SameSite=Strict cookie CSRF web
  chain); **error handler** redacts internals behind a trace id; **RateLimiter** prunes empty
  buckets (bounded memory, modulo BUG-22).
- **Lucene** writes are deferred to afterCommit (never indexes rolled-back data) and use
  thread-safe writer/searcher closed in `@PreDestroy`. **Scheduled** jobs use `fixedDelay` (no
  overlap) and correct `@Lazy` self-injection for `REQUIRES_NEW`.
- **`radiance`→`threadorbit`→`intellistream-chat` renames are complete** (`git grep -i radiance` clean); Gradle wrapper
  committed; `.gitignore` thorough; `application.yml` secret-free with safe localhost defaults.

---

## Suggested sequencing

1. **Before making the repo public:** SEC-1, SEC-3, OSS-2, OSS-3, OSS-8, DOC-1 (nothing
   embarrassing or credential-bearing on first view).
2. **First patch release:** BUG-1, BUG-2, WS-1, SEC-2, SEC-4 (the exploitable/crashing/
   availability set — WS-1 disconnects any rate-limited user), plus OSS-1/OSS-4/OSS-5 so
   contributors can land PRs.
3. **Rolling:** the rest of P1 (incl. WS-2 and the reconnect/real-time bugs), then P2 — land the
   `CLEAN-*` sweeps only after the write-path fixes (BUG-9/BUG-10) so the backstops have less to
   catch, and ship them `dry-run=true` first (CLEAN-4).

Each P0/P1 fix should ship with a test — the existing `integration/*IT.java` suite is the right
home for the server-side ones (BUG-1, BUG-2, SEC-4, SEC-6), and the JS bugs are observable by
driving the app (the `verify`/Playwright flow used during development).
