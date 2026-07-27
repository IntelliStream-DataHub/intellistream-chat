# IntelliStream Chat — security and bug audit

A record of the pre-release audit: what was found, what it was, and what was done about it. Kept
because the codebase refers to these findings by id — `BUG-9`, `SEC-4`, `CLEAN-3` and the rest
appear in comments where a fix is not self-explaining, and a bare id with nothing behind it is
worse than no comment at all.

Audited 2026-07-23 across five tracks (web/API security, auth + WebSocket + config security,
backend bugs and concurrency, front-end JS/XSS, open-source readiness), plus a comparison against
the sibling `datahub-api` and `datahub-cleanup` codebases which is where the `WS-*` and `CLEAN-*`
items came from. Every finding carries a `file:line` anchor from the time it was written; the
load-bearing ones were re-verified against the code before landing here (**[verified]**), and
findings reported independently by two tracks are marked **[2 auditors]**.

> **On the datahub comparison:** `datahub-api` uses *raw* Spring `WebSocketHandler`s while this
> app uses *STOMP* over the simple broker, so its code isn't copy-pasteable — the transferable
> items are patterns (idempotent teardown, heartbeats, per-message error isolation, reconnect
> backfill). Its raw-WS-specific work (per-connection Pulsar consumers, virtual-thread receive
> loops, a `TenantContext` ThreadLocal leak) does **not** apply: Spring owns all session threads
> and teardown here, and there's no per-connection external resource to drain.

The P0/P1/P2 grouping below is the severity each item carried *at the time of the audit*, kept so the
ids stay findable rather than as any remaining order of work. The table that follows says where each
one stands today, because a reader arriving from a `// BUG-9` comment wants that before anything
else.

---

## Contents

| | |
|---|---|
| [Status index](#status-index) | Every finding, whether it is fixed, and what pins it. **Start here.** |
| [Still open](#still-open-and-owned-by-whoever-deploys-it) | The four items a deployment still owns. |
| [P0 — Release blockers](#p0--release-blockers) | The July 2026 audit, by the severity each item carried then. |
| [P1](#p1--fix-before-or-immediately-after-release) · [P2](#p2--hardening--polish-lower-severity) | |
| [Open-source release prep](#open-source-release-prep) | The `OSS-*` items. |
| [Verified solid](#good-news--verified-solid-no-action-needed) | Examined and found sound — no action. |
| [Appendix: April 2026 review](#appendix--the-april-2026-hardening-review) | The earlier hardening pass, kept for its reasoning. |

---

## Status index

Every finding and where it stands, so an id met in a code comment can be resolved without reading the
whole document. **Pinned by** names a file or test that cites the id, which is the strongest evidence a
fix is still in place — a regression has to delete the reference to escape notice. The rest were closed
when the audit was closed and carry no such anchor; four were re-verified against current code while
writing this index and are marked *(re-checked)*.

| Finding | What it was | Status | Pinned by |
|---|---|---|---|
| **SEC-1** | Rotate & un-commit the Keycloak client secret | ⚠️ Operator — Code side done — the prod profile has no secret default, so it fails fast. The **dev** secret still ships in `keycloak/realm.json` on purpose, so `podman compose up` works for newcomers; rotating it is item 1 of the README hardening checklist. | — |
| **SEC-2** | Prod profile defaults auth endpoints to plaintext HTTP | ✅ Fixed *(re-checked)* | — |
| **BUG-1** | Editing a message that keeps a mention throws 500 | ✅ Fixed | — |
| **SEC-3** | Purge maintainer LAN IP so the default quickstart works for outsiders | ✅ Fixed *(re-checked)* | — |
| **SEC-4** | Poll voting uses the read check | ✅ Fixed *(re-checked)* | `PollFlowIT.java` |
| **SEC-5** | Username-enumeration oracle on group/DM/invite | ✅ Fixed | `ChannelRestController.java`, `GroupConversationFlowIT.java` |
| **SEC-6** | DM reaction/edit/delete are not rate limited | ✅ Fixed | — |
| **SEC-7** | CSP `connect-src` wildcards weaken the policy | ✅ Fixed *(re-checked)* | — |
| **WS-1** | One throttled/oversized message tears down the whole STOMP connection | ✅ Fixed | — |
| **WS-2** | No STOMP heartbeat or idle timeout → phantom-online sessions leak | ✅ Fixed | — |
| **BUG-2** | Non-unique username mis-routes private notices & breaks login | ✅ Fixed | — |
| **BUG-3** | No message catch-up after STOMP reconnect | ✅ Fixed | `ConversationMessageRepository.java`, `ConversationReactionAndEditFlowIT.java` |
| **BUG-4** | Edit button vanishes on live/paged channel messages | ✅ Fixed | — |
| **BUG-5** | Broken mention deep-links | ✅ Fixed | — |
| **BUG-6** | Reminder batch aborts on one bad row | ✅ Fixed | — |
| **BUG-7** | Presence counter corrupted by duplicate disconnects | ✅ Fixed | — |
| **BUG-8** | Upload captions bypass mention + search indexing | ✅ Fixed | — |
| **BUG-9** | Channel delete leaks attachment files & Lucene docs | ✅ Fixed | `CleanupProperties.java`, `CleanupTasks.java` |
| **BUG-10** | DM attachment files orphaned on message delete | ✅ Fixed | — |
| **BUG-11** | Reminder `at`-times resolve in server timezone | ✅ Fixed | — |
| **BUG-12** | Backgrounded tab wipes unread/mention state | ✅ Fixed | — |
| **BUG-13** | Timezone-split day grouping & timestamps | ✅ Fixed | — |
| **BUG-14** | Reacting deletes an in-progress edit form | ✅ Fixed | `conversation.js` |
| **BUG-15** | Live append force-scrolls readers to the bottom | ✅ Fixed | `conversation.js` |
| **BUG-16** | `appendMessage` has no de-dupe → duplicate rows | ✅ Fixed | — |
| **SEC-8** | No rate limit on markdown preview** low | ✅ Fixed | — |
| **SEC-9** | No rate limit on channel creation** low | ✅ Fixed | — |
| **SEC-10** | Presence mutations & GET batch unbounded** low | ✅ Fixed | — |
| **SEC-11** | No SUBSCRIBE-frame rate limit** low | ✅ Fixed | — |
| **SEC-12** | `forward-headers-strategy: framework` trusts X-Forwarded-* unconditionally | ✅ Fixed | — |
| **SEC-13** | Realm enables direct-access (ROPC) grant** low | ✅ Fixed | — |
| **SEC-14** | nginx example disables body-size cap** low | ✅ Fixed | — |
| **SEC-15** | Presence is globally visible to all authenticated users** low | ✅ Fixed | `StompAuthorizationConfig.java` |
| **BUG-17** | Check-then-act insert races surface as 500s** low | ✅ Fixed | — |
| **BUG-18** | `openThread` has no stale-response guard** low | ✅ Fixed | — |
| **BUG-19** | Avatar-preview blob leak + optimistic preview** low | ✅ Fixed | — |
| **BUG-20** | Keyset pagination lacks an id tie-break** low | ✅ Fixed | `MessageRepository.java`, `MessageService.java` |
| **BUG-21** | afterCommit index write has an unrecoverable loss window** low | ✅ Fixed | `ChannelService.java`, `MessageService.java` |
| **BUG-22** | RateLimiter prune race under-enforces at the sweep** low | 📌 Limitation — Fixed per-process. `RateLimiter` is in-memory, so it still does not compose across replicas — deferred with the rest of horizontal scaling. | `RateLimiter.java` |
| **BUG-23** | `demote` last-admin TOCTOU** low | ✅ Fixed | — |
| **BUG-24** | LuceneBootstrap loads the whole messages table into heap** low | ✅ Fixed | `LuceneBootstrap.java`, `MessageRepository.java` |
| **CLEAN-1** | Scheduled orphan-attachment sweep** low **[backstop for BUG-9/10] | ✅ Fixed | `AttachmentRepository.java`, `CleanupTasks.java` |
| **CLEAN-2** | Avatar orphan sweep + fix the replace-path leak** low **[NEW leak] | ✅ Fixed | `AvatarService.java`, `CleanupTasks.java` |
| **CLEAN-3** | Periodic Lucene↔DB reconciliation** low **[backstop for BUG-21] | ✅ Fixed | `CleanupTasks.java`, `ConversationService.java` |
| **CLEAN-4** | Cleanup observability: dry-run + enabled flags + summary logging** low | ✅ Fixed | — |
| **CLEAN-5** | The sweeps are single-instance only** low | 📌 Limitation — Recorded, not fixed: the sweeps race across nodes because `@EnableScheduling` runs on every one. Single-node deployments are unaffected; see `CleanupProperties`. | `CleanupProperties.java` |
| **OSS-1** | Community health files | ✅ Fixed | — |
| **OSS-2** | Add missing license headers | ✅ Fixed | — |
| **OSS-3** | Third-party notices | ✅ Fixed | — |
| **OSS-4** | Add CI | ✅ Fixed | — |
| **OSS-5** | Ship `application-dev.properties.example` | ✅ Fixed | — |
| **OSS-6** | Refresh or trim the standalone security plan | ✅ Fixed | — |
| **OSS-7** | Rework the README "AI-slop" framing | ✅ Fixed | — |
| **OSS-8** | Relocate the root `index.html` | ✅ Fixed | — |
| **OSS-9** | Fix test-count claims | ✅ Fixed | — |
| **OSS-10** | Publish point and stale branches | 📌 Tracked elsewhere — Release logistics rather than an audit finding. | — |
| **OSS-11** | `.gitignore` whitelists `!.env.example` but none exists | ✅ Fixed | — |

### Still open, and owned by whoever deploys it

Not code defects — decisions that only a deployment can make. All four are in the README's production
hardening checklist, and they are the whole of what the earlier security review
(the [April review](#appendix--the-april-2026-hardening-review), now the appendix below) still lists as open.

- **Rotate the bundled Keycloak client secret.** It ships so the compose quickstart works; it is a
  credential the moment your instance is real.
- **Turn on email verification** before opening registration, or bots can mass-register.
- **Replace the rate limiter** before running more than one node — it is per-process.
- **HSTS is set unconditionally**, including over plain HTTP in development. A no-op without TLS,
  and accepted.

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
  `scripts/seed-vault.sh:35`, `README.md`, the April review. `podman compose up -d` fails
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

- **SEC-4 · Poll voting uses the read check** 🟡 medium **[2 auditors, verified]** —
  `web/PollRestController.java:120` `requireMembership` calls `channelService.requireMember`
  (short-circuits `true` for PUBLIC), so a non-member can mutate poll tallies in any public
  channel. `ReactionService.addReaction:44` correctly uses `requireWriteAccess`. → Use
  `requireWriteAccess` in `castVote`/`removeVote`; keep `requireMember` for the `GET`.
- **SEC-5 · Username-enumeration oracle on group/DM/invite** 🟡 medium —
  `web/ConversationRestController.java:138-150` `createGroup` echoes
  `"Unknown user(s): " + names` verbatim, unbounded (≤100/req), unthrottled; same for
  `/direct`, `/{id}/members`, `/api/channels/{id}/invite`. Defeats the 120/min throttle those
  handles are otherwise protected by. → Rate-limit these mutations per user and return a
  generic "one or more members not found."
- **SEC-6 · DM reaction/edit/delete are not rate limited** 🟡 medium **[verified]** —
  `web/ConversationRestController.java:205-240` `addReaction`/`removeReaction`/`editMessage`/
  `deleteMessage` write + fan out over STOMP with no `rateLimiter.tryAcquire`, unlike their
  channel twins in `MessageRestController` (and unlike dm-send/upload/download in the same
  file). → Add the `reaction-toggle`/`msg-edit`/`msg-delete` limiter calls.
- **SEC-7 · CSP `connect-src` wildcards weaken the policy** 🟡 medium **[verified]** —
  `config/SecurityConfig.java:114` sets `connect-src 'self' ws: wss:`; the scheme wildcards
  permit outbound WebSocket to any host (exfil channel) and contradict the adjacent comment.
  Same-origin `wss://…/ws` is already covered by `'self'`. → Drop `ws: wss:`.

### WebSocket robustness (from datahub-api comparison)

- **WS-1 · One throttled/oversized message tears down the whole STOMP connection** 🟠 high
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
- **WS-2 · No STOMP heartbeat or idle timeout → phantom-online sessions leak** 🟡 medium
  **[verified]** — `config/WebSocketConfig.java` sets neither a broker heartbeat nor a
  `ServletServerContainerFactoryBean` `maxSessionIdleTimeout`, so a half-open TCP (client
  vanished with no FIN — sleep, dropped wifi) never fires `SessionDisconnectEvent`: the session
  leaks and the user shows "online" forever. This is also the missing mechanism that makes
  BUG-7's disconnect cleanup fire at all. datahub-api pairs a 15s server PING with a 45s idle
  timeout for exactly this. → `enableSimpleBroker(...).setHeartbeatValue(new long[]{10000,10000})
  .setTaskScheduler(scheduler)` and register a `ServletServerContainerFactoryBean` with
  `setMaxSessionIdleTimeout`.

### Bugs

- **BUG-2 · Non-unique username mis-routes private notices & breaks login** 🟠 high
  **[2 auditors, verified]** — `db/migration/V1__init.sql:20`: `users.username` has no unique
  constraint (only `subject`). `UserService.upsert:119-140` rewrites username every login with
  no collision check and `sanitizeUsername` collapses `bob@a.com` & `bob@b.com` → `bob`.
  Private notices route by username (`ChatWebSocketController.java:94`
  `convertAndSendToUser(user.getUsername(), …)`), and mentions/presence/`/remind` all key on it
  → one collision cross-delivers private messages and throws on login. → V2 migration adding
  `unique index on users(lower(username))`; make `sanitizeUsername` collision-proof (subject
  suffix) with the existing `DataIntegrityViolationException` catch-and-retry.
- **BUG-3 · No message catch-up after STOMP reconnect** 🟠 high — `chat/index.js:591-635`,
  `conversation.js:343-378`: the simple broker has no replay, so every message sent during a
  network blip / laptop sleep is missing until a full reload, silently. → In `onConnect` (when
  not the first connect) fetch `/api/channels/{id}/messages?after=<last data-created-at>` and
  append via the existing de-duping path. *(datahub-api solves the same problem with durable
  Pulsar cursors + ack/nack; the simple broker can't replay server-side, so this HTTP backfill
  is the STOMP-world equivalent — gate the read with the same channel read check.)*
- **BUG-4 · Edit button vanishes on live/paged channel messages** 🟠 high **[verified]** —
  `chat/index.js:945-1003` `buildMessageLi` never sets `li.dataset.bodyMarkdown`, but
  `attachActions`/`startEdit`/`replaceMessageDom` all depend on it, so every WS-appended and
  infinite-scroll message loses Edit and misdetects the first reaction as a body edit.
  `conversation.js:84` does it correctly. → Add
  `li.dataset.bodyMarkdown = msg.bodyMarkdown || '';`.
- **BUG-5 · Broken mention deep-links** 🟠 high — `chat/index.js:578`,
  `mention-inbox.js:72` build `/channels/{id}#m-{id}`, but the permalink consumer matches
  `#m=<id>` and needs `?m=<id>` for server context-around; clicking a mention lands at the
  channel tail with no highlight. → Build like `permalinkFor` (index.js:1892):
  `'/channels/'+ch+'?m='+id+'#m='+id`.
- **BUG-6 · Reminder batch aborts on one bad row** 🟡 medium —
  `slash/ReminderScheduler.java:110-133`: `fireOne` catches `messageService.post`'s exception,
  but `post` is `@Transactional` and has already marked the tx rollback-only, so `fireOne`'s
  commit throws `UnexpectedRollbackException` into `runOnce`'s loop → all later due reminders
  slip a poll cycle. → Move try/catch into `runOnce` (let `fireOne` throw); post via a separate
  `REQUIRES_NEW` method so the batch tx is never poisoned.
- **BUG-7 · Presence counter corrupted by duplicate disconnects** 🟡 medium —
  `service/PresenceTracker.java:43-62`: Spring may publish `SessionDisconnectEvent` more than
  once per session; blind counter decrement drives a two-tab user offline while a live session
  remains, and the connect/disconnect race can drop an online user from `sessions`. → Track
  `ConcurrentHashMap<String,Set<String>>` of session ids keyed off `event.getSessionId()`,
  mutate via `compute()` so add/remove/emptiness-removal are atomic and a repeat disconnect for
  an already-removed session is inert. *(datahub-api uses the same guarded-idempotency approach —
  `compareAndSet` teardown guard in `DatapointListenSession.stop()` — for exactly this class of
  double-fire.)*
- **BUG-8 · Upload captions bypass mention + search indexing** 🟡 medium —
  `service/AttachmentService.java:128-130` saves the caption via
  `messageRepository.save(new Message(...))`, skipping `syncMentions` and the afterCommit
  Lucene write that `MessageService.post` performs → captions are unsearchable and their
  `@mentions` never notify, until a bootstrap rebuild silently changes behavior. → Route the
  caption through `MessageService.post` (or replicate syncMentions + index write).
- **BUG-9 · Channel delete leaks attachment files & Lucene docs** 🟡 medium —
  `service/ChannelService.java:132-136` `destroy` is a bare `delete(channel)`; DB cascades rows
  but nothing removes the on-disk attachment files (permanent disk leak) or the channel's
  Lucene docs (index bloats forever; `rebuildIfEmpty` never reconciles a non-empty index).
  `MessageService.delete` does both cleanups. → Collect message ids + storage keys, afterCommit
  delete index docs (add a `channelId` term / `deleteByChannel`) and files. *(This is the exact
  shape datahub-cleanup's `DeletedFilePurgeTask` uses: unlink file, then delete row; CLEAN-1
  below adds the scheduled backstop for the crash-in-between case.)*
- **BUG-10 · DM attachment files orphaned on message delete** 🟡 medium —
  `service/ConversationService.java:151-160` `deleteMessage` removes rows (FK cascade) but never
  deletes the files on disk; the channel twin (`MessageService.delete:296-313`) does. → Look up
  the message's `ConversationAttachment` storage keys and register an afterCommit file cleanup.
- **BUG-11 · Reminder `at`-times resolve in server timezone** 🟡 medium —
  `slash/RemindCommand.java:142` uses `ZoneId.systemDefault()`, so `/remind … at 14:00` fires
  at the wrong local time for any non-server-TZ user; also `in 99999999999999d` →
  `Duration.ofDays` `ArithmeticException` → raw 500. → Store/collect a per-user timezone;
  clamp the duration and map `ArithmeticException` to the friendly usage error.
- **BUG-12 · Backgrounded tab wipes unread/mention state** 🟡 medium —
  `chat/index.js:702-708` POSTs `/read` on every `created` event with no
  `visibilityState`/`hasFocus` check (the adjacent mention-notify call has one), so a tab left
  open overnight clears sidebar badges, the bell, and unseen mention rows. → Only POST `/read`
  when the tab is visible+focused; post one catch-up on `visibilitychange→visible`.
- **BUG-13 · Timezone-split day grouping & timestamps** 🟡 medium —
  server renders `data-day`/`<time>` in the *server* zone (`channels.html:308,333`) while the
  client computes them in the *browser* zone (`chat-kit.js:67-69`), so cross-TZ viewers get
  wrong day dividers/grouping and disagreeing timestamps between old and live messages. →
  Hydrate day keys/times client-side from `data-created-at`, or format server-side per-user TZ.
- **BUG-14 · Reacting deletes an in-progress edit form** 🟡 medium —
  `chat/index.js:1714-1757`, `conversation.js:159-187`: `replaceMessageDom` removes
  `.message-edit` on any `updated` broadcast, and reaction toggles arrive as `updated`, so a
  reaction from anyone destroys the author's unsaved edit draft. → If an edit form is present,
  refresh reactions/attachments but preserve the form + textarea value.
- **BUG-15 · Live append force-scrolls readers to the bottom** 🟡 medium —
  `chat/index.js:1026` `messagesEl.scrollTop = scrollHeight` unconditionally, yanking a user
  reading history down on every new message. → Only auto-scroll when already near the bottom;
  otherwise show a "new messages" pill.
- **BUG-16 · `appendMessage` has no de-dupe → duplicate rows** 🟡 medium **[verified]** —
  `chat/index.js:1006-1028` lacks the id guard `conversation.js:49` has, so a broadcast that
  races the final infinite-scroll page renders a duplicate `<li>`. → Add the same
  `querySelector('li.message[data-id=…]')` guard.

---

## P2 — Hardening & polish (lower severity)

### Security hardening
- **SEC-8 · No rate limit on markdown preview** low — `web/PreviewRestController.java:41`
  runs the full CommonMark+jsoup+mention pipeline per call, no limiter. → Add ~60/min per user.
- **SEC-9 · No rate limit on channel creation** low — `web/ChannelRestController.java:94`.
  → Modest per-user create limit.
- **SEC-10 · Presence mutations & GET batch unbounded** low —
  `web/PresenceRestController.java:72-108` broadcast without a limiter; `:66-70` splits an
  uncapped username list into an IN-query. → Rate-limit mutations; cap the batch length.
- **SEC-11 · No SUBSCRIBE-frame rate limit** low — `config/StompAuthorizationConfig.java`
  runs 1-2 DB queries per SUBSCRIBE with no per-session limit. → Sliding-window cap on SUBSCRIBE.
- **SEC-12 · `forward-headers-strategy: framework` trusts X-Forwarded-* unconditionally**
  low — `application.yml:64`; safe only while bound to loopback. → Keep loopback bind, or switch
  to `native` with `server.tomcat.remoteip.internal-proxies`; fix the misleading class comment.
- **SEC-13 · Realm enables direct-access (ROPC) grant** low — `keycloak/realm.json:27`
  `directAccessGrantsEnabled: true` + committed secret = direct token minting. → Disable for the
  confidential client in the shipped realm.
- **SEC-14 · nginx example disables body-size cap** low — `nginx_example.conf:58`
  `client_max_body_size 0` + admin uploads = `UNLIMITED`. → Ship a concrete cap (e.g. `500m`).
- **SEC-15 · Presence is globally visible to all authenticated users** low —
  `/topic/presence` isn't scoped to shared channels. → Confirm intended; if not, scope broadcasts.

### Bug polish
- **BUG-17 · Check-then-act insert races surface as 500s** low —
  `ReadStateService.java:53-61`, `ChannelService.java:83-100` (join/invite),
  `ReactionService.java:50-52`, `PollService.java:103-113`, `ConversationService.java:104-111`:
  concurrent idempotent ops both insert; the loser throws `DataIntegrityViolationException` →
  500. `directBetween`/`upsert` show the correct catch-and-reread. → Wrap in catch-and-reread or
  `on conflict do nothing`.
- **BUG-18 · `openThread` has no stale-response guard** low — `chat/index.js:1959-1972`:
  two quick thread clicks race; the last response to land wins regardless of last click. → Use a
  monotonic request id like search/preview already do.
- **BUG-19 · Avatar-preview blob leak + optimistic preview** low — `profile.js:87-99,117`:
  `createObjectURL` never revoked, and the preview swaps before server validation (a rejected
  upload shows the new picture beside the error). → Revoke on replace; swap only on 2xx.
- **BUG-20 · Keyset pagination lacks an id tie-break** low — `MessageService.java:96-150`
  orders by `createdAt` only; same-timestamp messages straddling a page are skipped and
  `around()` omits the exact-anchor row. → Order by `(createdAt, id)` and paginate the composite.
- **BUG-21 · afterCommit index write has an unrecoverable loss window** low —
  `MessageService.java:312-345`, `LuceneBootstrap.java:49-64`: a crash between DB commit and the
  index write permanently desyncs the index (rebuild only fires on a fully empty index); a
  throwing `deleteAll` also skips the file cleanup that follows it. → try/catch-log each
  afterCommit body; add a periodic/admin reconciliation by max message id.
- **BUG-22 · RateLimiter prune race under-enforces at the sweep** low —
  `security/RateLimiter.java:54-84`: `removeIf` checks emptiness under the deque lock but removes
  from the map outside it, losing a concurrent event. → Verify-and-reinsert after `addLast`, or
  remove via `windows.compute`.
- **BUG-23 · `demote` last-admin TOCTOU** low — `ChannelService.java:115-130`: two admins
  demoting each other can both pass the last-admin guard → zero-admin channel. → Pessimistic lock
  on the membership rows before counting.
- **BUG-24 · LuceneBootstrap loads the whole messages table into heap** low —
  `LuceneBootstrap.java:53-58` `findAll()` + lazy per-author init → multi-GB spike on a fresh
  deploy at scale. → Keyset-stream with a flat author-joined projection.

### Scheduled cleanup & reconciliation (from datahub-cleanup comparison)

These are backstops for the resource leaks above — orphan sweeps that catch what slips through
the write-path crash windows. Model on datahub-cleanup's task family; collapse its
multi-tenant loops to a single un-routed pass (chat is single-tenant, one data dir each).

- **CLEAN-1 · Scheduled orphan-attachment sweep** low **[backstop for BUG-9/10]** —
  even with the write-path fixes, a crash between DB commit and the afterCommit `deleteFiles`
  leaves an orphan. Add a `@Scheduled` task that lists `./data/attachments`, builds the live key
  set = `attachments.storage_key ∪ conversation_attachments.storage_key`, and deletes files not
  in it whose mtime is older than a grace window (~24h, so a mid-upload file pre-commit is
  spared). **Abort the run if either DB query fails** — never delete against a partial live set
  (datahub's `OrphanTenantFolderCleanupTask` refuses to act on an empty/unreadable live set).
- **CLEAN-2 · Avatar orphan sweep + fix the replace-path leak** low **[NEW leak]** — the
  audit's file-leak review of attachments also applies to avatars: `AvatarService`'s
  replace-avatar path only best-effort `deleteIfExists`es the previous key in an afterCommit hook
  (leaks the old file on any crash/IO error between commit and hook), and nothing cleans up when
  a `User` is deleted. → Same sweep family: list `./data/avatars`, live set = non-null
  `users.avatar_storage_key`, delete unreferenced files older than the grace window.
- **CLEAN-3 · Periodic Lucene↔DB reconciliation** low **[backstop for BUG-21]** — replace
  rebuild-only-when-empty with a `@Scheduled` reconcile: pull the id set from `messages`, diff
  against the index, `index(...)` the missing and `deleteAll(...)` the stale. Needs a new
  "enumerate all doc ids" on `MessageIndexService` (it has `index`/`deleteAll`/`isEmpty` but no
  id scan). Skip the run if the DB read fails.
- **CLEAN-4 · Cleanup observability: dry-run + enabled flags + summary logging** low
  **[NEW]** — the app has zero cleanup observability. Bind `chat.cleanup.*`
  `@ConfigurationProperties` (`enabled`, `dry-run`, `grace`), **default `dry-run=true`**, and
  have each sweep log "[dry-run] would delete …" per item plus a per-run count, so an operator
  can watch the orphan/desync backlog before arming destructive deletes.
- **CLEAN-5 · The sweeps are single-instance only** low — *recorded limitation, not a fix.*
  `@EnableScheduling` runs on every node, so two nodes would race the purges and the reconcile.
  A Postgres advisory-lock guard is the fix and is deferred with the rest of horizontal scaling;
  until then the sweeps are safe on a single node and must be disabled (`enabled=false`) on all
  but one node of a multi-node deployment. Carried in `CleanupProperties`' javadoc, where an
  operator reading the config will actually meet it.

---

## Open-source release prep

### Blockers
- **OSS-1 · Community health files** — add `SECURITY.md` (private vuln-reporting channel —
  priority for a self-hosted chat app), `CONTRIBUTING.md` (distill README's testing/dev-stack
  sections), `CODE_OF_CONDUCT.md`. `LICENSE` (Apache-2.0) already exists.
- **OSS-2 · Add missing license headers** — 9 Java + 13 JS first-party files lack the
  Apache header (README claims 100% coverage). Java: `StompAuthorizationConfig`,
  `ConversationReaction`, `MessageReaction`, `ConversationReactionRepository`,
  `MessageReactionRepository`, `ConversationReactionService`, `ReactionService`,
  `ReactionGroupDto`, `ReactionRequest` (+ `ReactionFlowIT` in tests). JS: `conversation.js`,
  `hovercard.js`, `idle-logout.js`, `mention-inbox.js`, `notifications.js`, `presence.js`,
  `profile.js`, `theme-loader.js`, `emoji-data.js`, and the four `*.manifest.js`.
- **OSS-3 · Third-party notices** — `static/js/vendor/stomp.umd.min.js` carries no license
  banner (StompJS = Apache-2.0); `static/fonts/OFL-Figtree.txt` is a 3-line pointer but OFL 1.1
  requires the full text to accompany the fonts. Add `THIRD-PARTY-NOTICES.md` covering StompJS,
  highlight.js (BSD-3 + its GitHub themes) and the full Figtree OFL.

### Important
- **OSS-4 · Add CI** — no `.github/workflows`. Add a GitHub Actions job running
  `./gradlew test` (Testcontainers works out-of-box on `ubuntu-latest`; the podman socket dance
  is local-only). Gradle wrapper is already committed.
- **OSS-5 · Ship `application-dev.properties.example`** — the real file is gitignored, but
  README/ASSETS/application.yml all reference it and `bootRun` force-activates `dev`. Commit a
  sanitized template (no LAN IP) documenting `assets.unbundled`, `dev-tools.enabled`,
  `allowed-origins`, issuer overrides.
- **OSS-6 · Refresh or trim the standalone security plan** — README links it 3× as the hardening
  checklist, but it references `com.example.chat.*` paths (two renames stale), a removed
  `CHAT_SECURITY_COOKIE_SECURE` env var, and the LAN IP. Update names or trim to the still-true
  checklist. (Consider whether this internal doc should ship at all.)
- **OSS-7 · Rework the README "AI-slop" framing** — the opening says "this is still
  considered AI-slop, and there are bugs, even serious bugs" plus a Teams rant; it's the first
  thing every visitor reads. Keep the candor deliberately or rework it.
- **OSS-8 · Relocate the root `index.html`** — it's a 707-line standalone marketing page for
  intellistream.ai, unreferenced by the build (the app's landing page is `templates/landing.html`).
  Move to `docs/`/`website/` or its own branch so the repo root isn't a marketing artifact.

### Nice-to-have
- **OSS-9 · Fix test-count claims** — README says "~190 tests / 21 classes"; the tree has
  ~37 test files. Recount or drop the numbers.
- **OSS-10 · Publish point and stale branches** — release logistics rather than an audit
  finding, tracked outside this document. The private `origin` and its stale branches
  (`code-audit-fixes`, `landing-page`, `presence-search-and-polish`, `rebrand-and-openbao-secrets`)
  are retired by the move to GitHub; author emails in history are normal for an open-source
  project and were kept deliberately.
- **OSS-11 · `.gitignore` whitelists `!.env.example` but none exists** — add one or drop the
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

## Appendix — the April 2026 hardening review

The earlier of the two reviews, kept whole for its reasoning rather than its status: what was
examined, what was decided, and why. Read it as a record of a moment. Anything it lists as a
finding has since been resolved, superseded, or carried into the status index above — that table,
not this appendix, is the current position.

> **Companion test files:**
> - `src/test/java/ai/intellistream/chat/integration/SecurityBoundaryIT.java` — locks in the
>   AuthN/AuthZ/sanitisation invariants the codebase has always asserted.
> - `src/test/java/ai/intellistream/chat/integration/InternetExposureSecurityIT.java` — locks
>   in the additions made for public-internet exposure (per-user upload cap from Keycloak,
>   GET rate limits, Lucene wildcard refusal).

---

### 2026-04-26 update — internet-exposure pass

A second review focused on what would matter once this app is reachable from the public
internet, with the new features that shipped between 2026-04-25 and 2026-04-26 (DMs,
hovercard, mention notifications, attachments, idle logout, virtual threads, Lombok
refactor) folded in. Outcome:

#### Resolved this round

- **[Was H] Cookie `Secure` flag missing.** Fixed: both the JSESSIONID and CSRF cookies now
  auto-detect `Secure` **per request** from `request.isSecure()` — no env var. Behind a
  TLS-terminating proxy, `forward-headers-strategy: framework` sets `request.isSecure()` from
  `X-Forwarded-Proto: https`, so the cookies are marked Secure automatically; on plain-HTTP
  local dev they aren't, so they round-trip. See the class comment in `SecurityConfig`.
- **[Was H] No upload cap once streaming was added.** Fixed at the time: per-user 50 MiB default,
  unlimited for `ichat-admin`, override per-user via Keycloak attribute
  `chat_max_upload_bytes` mapped through to the JWT by the protocol mapper in
  `keycloak/realm.json`. `CurrentUser.uploadCapBytes(Principal)` resolves; both
  `AttachmentService.upload` and `ConversationAttachmentService.upload` enforce
  pre-flight (declared size) and during-stream (chunk count) via the shared
  `AttachmentBytes.streamToFile(in, target, maxBytes)`.
  *(Superseded, July 2026: the 50 MiB default was removed — a file is now as large as it is, bounded
  by the per-account storage quota rather than a per-file cap. The `chat_max_upload_bytes` claim
  still imposes one per account where an operator wants it.)*
- **[Was H] No rate limit on hot GET endpoints.** Fixed: `GET /api/users/{username}`
  → 120/min (anti-enumeration), `GET /api/users/{u}/avatar` → 600/min (hot path),
  `GET /api/attachments/{id}/download` → 200/min, `GET /api/conversations/{c}/attachments/{a}/download`
  → 200/min. All scoped per-user via `RateLimiter`.
- **[Was M] Lucene wildcard DoS.** Fixed: `MessageIndexService.FuzzyTermQueryParser`
  overrides `getWildcardQuery`, `getPrefixQuery`, and `getRegexpQuery` to return
  `null`, so a query like `a*` resolves to "no results" instead of fanning out across
  every term in the index. Naked `*` no longer becomes `MatchAllDocsQuery`.
- **[Was M] Generic "Request rejected." swallowed legitimate user-facing errors.**
  Fixed for the upload-too-large case: new `UploadTooLargeException` is rendered as
  HTTP 413 with `{ code: "upload_too_large", message, maxBytes }`; the JS upload UX
  in `chat.js` / `conversation.js` / `profile.js` reads the structured payload and
  renders "File too large — your account is capped at N MiB per upload."

The four items this pass left open are in the [status index](#still-open-and-owned-by-whoever-deploys-it)
at the top of this document, which is where open work is tracked.

---

### Original review (2026-04-25)

Each finding is tagged by severity: **[H]** high, **[M]** medium, **[L]** low,
**[I]** informational. Some have since been resolved — see the update section above.

---

### 1. Authentication

#### What's in place
- Two filter chains in `SecurityConfig`:
  - **API/WS chain** (`/api/**`, `/ws/**`): bearer JWT issued by Keycloak, CSRF off.
  - **Web chain** (everything else): OAuth2 login (authorization code) → Keycloak,
    cookie session, CSRF on with `CookieCsrfTokenRepository.withHttpOnlyFalse()`.
- `CurrentUser` is the only place that translates a Spring principal to a domain `User`,
  upserting on first sight.

#### Findings
- **[H] The browser fetches `/api/**` from the OIDC session, but the API chain only accepts
  bearer JWT.** The current chat.js does `fetch('/api/...')` from a logged-in browser without
  attaching a Bearer token. With the current configuration, those calls *should* return 401.
  If they appear to work it's because something else is permitting them — either the resource
  server is silently allowing requests with no Authorization header (misconfiguration), or
  there is an undocumented filter ordering. **Verify and either:** add cookie-based auth as a
  second mechanism on the API chain, *or* have the browser attach a token from the OIDC session
  (token-relay).
- **[M] The CSRF cookie is `HttpOnly = false` deliberately so JS can read it.** That's required
  for the double-submit pattern, but combined with no `SameSite=Strict` it means a
  cross-site script that already has a token wouldn't help an attacker — but a same-site XSS
  would. Set `SameSite=Strict` on the CSRF cookie and the session cookie.
- **[L] `defaultSuccessUrl("/channels", true)`** with `alwaysUse=true` means a deep-link is
  thrown away after login. Not a vulnerability but a UX paper-cut that often gets fixed by
  enabling saved-request redirect — and people then mis-implement open-redirect protection.
  When you add it, **whitelist redirect targets**.
- **[L] `forward-headers-strategy: framework`.** Make sure the proxy in front strips
  `X-Forwarded-*` from external clients, otherwise an attacker can claim any source IP / host.

#### Next steps
- Decide the API auth story (bearer-only vs. cookie-bearer hybrid) and document it in
  `AGENT.md`. Add an IT that calls `/api/channels` with no Authorization header and asserts
  the documented behaviour.
- Tighten cookie attributes (`SameSite=Strict`, `Secure` when HTTPS).

---

### 2. Authorization

#### What's in place
- `ChannelService.requireMember` / `requireAdmin` gates service operations.
- Public channels are readable by any authenticated user; writes require membership.
- `MessageService.edit` is author-only. `MessageService.delete` is author-or-admin.
- `AttachmentService.requireForDownload` gates downloads via `requireMember`.
- `MentionService.syncMentions` only inserts rows for users that exist; doesn't leak presence.

#### Findings
- **[H] No global rate limiting.** A signed-in user can:
  - Spam any number of messages (no per-user/per-channel rate limit).
  - Open thousands of WebSocket subscriptions.
  - Trigger unbounded reads via `GET /api/channels/{id}/messages?limit=…` (capped at 50, OK).
  - Brute-force the upload endpoint.
  Add a Bucket4j (or similar) layer at the controller boundary, scoped per-user.
- **[M] `ChannelService.requireMember` allows any authenticated user to read a `PUBLIC`
  channel even if they have not joined it.** Documented behaviour, but worth re-reading the
  product intent — especially in light of search: `searchAllJoined` is correctly scoped to
  joined channels, but `searchChannel(publicChannel, anyone)` succeeds. A user can enumerate
  every public channel's history without ever joining.
- **[M] No check that the parent of a thread reply belongs to the same channel as the
  reply.** In practice it's the only path that creates a reply (`MessageService.replyInThread`
  reads the parent's channel and uses *that*), but a future refactor could introduce a bug
  where a parent from channel A gets a reply attributed to channel B. Add an invariant in
  `MessageRepository` or as a CHECK constraint.
- **[L] `ChannelService.invite` doesn't check that the inviter and the invitee are in the
  same realm or that the invitee is a real user with consent.** Acceptable for an internal
  workspace; flag if this app ever goes multi-tenant.

#### Next steps
- Rate-limit message POSTs and uploads (per-user, sliding window).
- Decide whether public channels should require a join to read. If yes, change
  `requireMember`'s shortcut and update the IT.
- Add a service-level invariant that thread replies inherit the parent's channel.

---

### 3. Input validation & storage

#### What's in place
- `@Valid` on request DTOs (`SendMessageRequest`, `EditMessageRequest`, etc.) with
  `@NotBlank` and `@Size(max = 8000)`.
- `MessageService.post` re-checks emptiness and length defensively.
- `AttachmentService.upload` enforces filename presence, sanitises filename to a basename,
  caps size at 50 MiB *while streaming* (`AttachmentService.streamToFile` aborts the moment
  the running byte count exceeds the cap).
- `AttachmentService.resolve` rejects storage keys that escape the storage root via
  `Path.normalize()` + `startsWith(storageRoot)`.
- `Channel.slug` is generated server-side from a regex and capped.

#### Findings
- **[M] `AttachmentService.sanitizeFilename` only strips path separators.** It does not
  remove `..`, control characters, or NUL. The download endpoint URL-encodes, so the
  rendered `Content-Disposition` is safe — but the value is also stored in the DB and
  echoed verbatim in `MessageDto.attachments[].filename`, which is then injected into the
  DOM via `textContent` (safe today, fragile if any caller swaps to `innerHTML`). Strip
  control chars and `..`, normalise unicode.
- **[M] No content-type sniffing on upload.** We trust the client-declared MIME type. An
  attacker can upload `.html` as `image/png` and link a victim to `/api/attachments/{id}/download`
  with a crafted `Accept`. Today the response sets the declared content-type and forces
  `Content-Disposition: attachment`, which mitigates inline rendering — but if a future
  feature adds `inline` for thumbnails, this becomes XSS-via-image. **Add MIME sniffing**
  (e.g. Apache Tika) and reject mismatches; add a `X-Content-Type-Options: nosniff` header.
- **[M] No virus scanning.** Out of scope for an MVP but should be on the roadmap if this
  ever ships externally.
- **[L] Mention regex (`MentionService.MENTION`)** allows `.` and `-` in handles and uses a
  lookbehind to avoid matching email-like `foo@bar.com`. That's correct — but it does
  match `@..` and similar oddities. A username like `..` could cause a false-positive
  display. In practice usernames are constrained by Keycloak; still, validate
  `User.username` in `provisionFromOidc` to reject suspicious shapes.

#### Next steps
- Tighten `sanitizeFilename`: strip `..`, control chars, NUL; normalise unicode.
- Add MIME sniffing on upload; add `X-Content-Type-Options: nosniff` to download responses.
- Validate usernames during OIDC provisioning.

---

### 4. Output encoding / XSS

#### What's in place
- `MarkdownRenderer` parses CommonMark, sanitises with jsoup `Safelist.basic` plus a
  controlled allowlist (headings, tables, code blocks, `class` on `<pre>`/`<code>`,
  `class`+`data-username` on `<span>` for mentions).
- Mention decoration runs *after* sanitisation and uses HTML-escaped values for both text and
  attribute content.
- Client renders `bodyHtml` via `innerHTML` (intentional — that's the contract).

#### Findings
- **[M] `Safelist.basic` allows `<a href>` and the renderer does not enforce
  `rel="noopener"` / `rel="nofollow"` / `target` policy.** A user-pasted link can open a
  window with `window.opener` access. Configure jsoup to enforce
  `rel="noopener noreferrer"` on every anchor.
- **[L] Highlight.js runs client-side and inserts `<span>` tags into the message body.**
  Those spans are not in our safelist, but they're added *after* clean — fine because they
  come from a trusted local script. Just be aware: do not move highlighting to the server
  unless it goes through the safelist with `<span class>` allowed.
- **[L] No Content-Security-Policy header.** Every web page should set a strict CSP that:
  - Disallows `unsafe-inline` for scripts (we have one inline `<script>` in `channels.html`
    today — extract it to a static file to enable strict CSP).
  - Restricts `script-src` to `'self'` and any vendored CDN roots.
  - Restricts `connect-src` to the WS host.

#### Next steps
- Add `rel="noopener noreferrer"` post-processing for anchors in `MarkdownRenderer`.
- Move the inline `<script>` in `channels.html` to a static file (e.g. `theme-loader.js`)
  and enable a strict CSP.

---

### 5. Persistence / SQL

#### What's in place
- All queries are JPA-managed (`Spring Data JPA`) with named/positional parameters.
- The native search query uses `:channelId`, `:q`, `:lim` parameters — no string
  concatenation.
- Migrations live under Flyway with `ddl-auto=validate`.

#### Findings
- **[L] Native queries use parameter binding correctly.** No SQL injection risk seen.
- **[L] Postgres user is created with `chat` / `chat` in `application.yml` defaults.**
  Acceptable for local dev; ensure production uses a secret.
- **[I] No row-level security in Postgres.** Authorization is purely application-level.
  Acceptable; document it.

---

### 6. Logging & PII

#### What's in place
- `application.yml` sets `org.springframework.security: INFO`, `org.hibernate.SQL: WARN`.
- No structured logging configured.

#### Findings
- **[M] On error, `ApiExceptionHandler` returns the raw `IllegalArgumentException` /
  `IllegalStateException` message to the client.** Many of these messages include identifiers
  (`Message not found: <uuid>`, `User not provisioned: <subject>`). Low risk because the
  caller already knew the id. Higher-risk: a future exception that includes a query body.
  Audit before adding new throws.
- **[L] No PII redaction in logs.** OIDC subjects and usernames will end up in the log if
  Spring Security debug logging is ever enabled.

#### Next steps
- Add a generic-error filter that returns a stable error code + opaque message; surface the
  detail only in the server log.
- Document a logging policy for usernames / display names.

---

### 7. WebSocket / STOMP

#### What's in place
- `/ws` SockJS handshake gated by the API filter chain (JWT-required). Subsequent messages
  ride on the established session.
- `ChatWebSocketController.send` gates writes via `messageService.post` →
  `channelService.requireMember`.

#### Findings
- **[H] No subscription-time authorization.** A user authenticates the handshake, then can
  `SUBSCRIBE` to *any* `/topic/channels/{id}` — including channels they aren't a member of.
  The server happily fan-outs everything to them. **Add a `ChannelInterceptor` that gates
  SUBSCRIBE frames against `channelService.isMember`** for private channels.
- **[M] No connection / message rate limit.** A misbehaving client can hammer
  `/app/channels/{id}/typing` to fan-out to every subscriber.
- **[L] Typing pings are not authorized for membership.** `typing()` calls
  `channelService.requireMember`, which is correct — but only for the server side; the
  fan-out destination is `/topic/channels/{id}/typing`. Combined with the SUBSCRIBE issue,
  a snooper would see typing pings from private channels they have no business knowing
  about.

#### Next steps
- Wire a STOMP `ChannelInterceptor` that authorizes SUBSCRIBE per channel.
- Add per-user message rate limiting.

---

### 8. File upload / download

#### What's in place
- Streaming upload via Apache Commons FileUpload — never buffers the full file in memory.
- 50 MiB hard cap enforced *during* streaming.
- Storage keys are random UUIDs; original filenames live only in the DB.
- Path-escape protection in `AttachmentService.resolve`.

#### Findings
- **[M] Download authorization uses `requireMember`,** which means *anyone authenticated*
  can download attachments from public channels regardless of whether they joined. Same
  semantic as message read. Document or tighten.
- **[L] No `Content-Disposition: attachment` enforcement for image previews.** Today the
  download endpoint always sets `attachment`. The image preview (`<img src="…/download">`)
  works because browsers ignore `Content-Disposition` for `<img>` requests. If a future
  feature serves *inline* images on a sub-route, ensure it sniffs the content type.
- **[L] Multipart parts other than `file` and `caption` are silently drained.** Defensive
  but masks bugs (e.g. a typo'd field name). Reject unknown form fields with 400.

#### Next steps
- Decide whether public-channel attachments require membership.
- Reject unknown multipart fields with a clear 400.

---

### 9. Dependencies

#### What's in place
- Spring Boot 4.0.5, Java 25, Postgres 18, Lucene 10.4.0, jsoup 1.18.1, commonmark 0.22.0,
  commons-fileupload2 2.0.0-M2, Apache Tika is *not* a dependency yet.

#### Findings
- **[M] `commons-fileupload2-jakarta-servlet6:2.0.0-M2` is a milestone build.** Pin to a
  GA release once available; track for security updates.
- **[L] No automated dependency-vulnerability scanning.** Add `org.owasp.dependencycheck`
  or rely on Renovate/Dependabot.

---

### 10. Headers & deployment

#### Findings
- **[H] No security headers configured.** Add at the web filter chain:
  - `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' …`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains` (when HTTPS only)
  - `X-Frame-Options: DENY` (or `frame-ancestors 'none'` in CSP)
- **[Resolved] `server.address` now defaults to `${SERVER_ADDRESS:127.0.0.1}`** in
  `application.yml` (loopback, safe behind a proxy). The maintainer's LAN IP is no longer in
  the committed tree — LAN/mobile dev uses a gitignored `compose.override.yml`.

---

### Priority backlog

| # | Item | Severity | Status |
|---|------|----------|--------|
| 1 | STOMP SUBSCRIBE authorization (block snooping on private channels) | **H** | ✅ done — `StompAuthorizationConfig` |
| 2 | API auth story (cookie vs. bearer) — document & test | **H** | ✅ documented in `AGENT.md` |
| 3 | Per-user rate limiting (REST + STOMP) | **H** | ✅ done — `RateLimiter` (in-memory; replace for multi-instance) |
| 4 | Default security headers (CSP, nosniff, HSTS, Referrer-Policy) | **H** | ✅ done in `SecurityConfig` |
| 5 | MIME sniffing on upload + nosniff on download | **M** | ✅ done — `AttachmentService.sniffContentType` + `X-Content-Type-Options` header |
| 6 | `rel="noopener noreferrer"` on rendered anchors | **M** | ✅ done — `MarkdownRenderer.hardenAnchors` |
| 7 | Tighten `sanitizeFilename` (`..`, controls, NUL, unicode) | **M** | ✅ done |
| 8 | Service-level invariant: thread reply inherits parent channel | **M** | ✅ enforced by `MessageService.replyInThread` (`channel = parent.getChannel()`); covered by `SecurityBoundaryIT` |
| 9 | Cookie attributes (`SameSite=Strict`, `Secure`) | **M** | ✅ `SameSite=Strict` on JSESSIONID + CSRF cookie; `Secure` should be added in HTTPS deploys via `server.servlet.session.cookie.secure=true` |
| 10 | Strict CSP — extract inline `<script>` first | **M** | ✅ done — moved to `theme-loader.js` / `profile.js` |
| 11 | Decide public-channel read posture (member-only vs. anyone-authenticated) | **M** | ✅ documented in `AGENT.md`; current behavior kept |
| 12 | OWASP dependency scanner + dependency upgrades | **M** | ⚠️ deferred — listed under roadmap in `AGENT.md` for opt-in |
| 13 | Validate usernames during OIDC provisioning | **L** | ✅ done — `UserService.sanitizeUsername` |
| 14 | Generic error envelope, redact identifiers | **L** | ✅ done — `ApiExceptionHandler` returns `{code, message, traceId}` with full detail logged server-side only |
| 15 | Reject unknown multipart fields with 400 | **L** | ✅ done in `AttachmentRestController` |

---

### What the new IT covers

`SecurityBoundaryIT` exercises the boundaries the codebase already enforces, so any
regression shows up loudly:

- Path-traversal storage keys are rejected by `AttachmentService.resolve`.
- A non-member can't read/search/post in a private channel.
- A non-author can't edit another user's message; an admin can't edit (but can delete).
- A non-author non-admin can't delete.
- Markdown sanitization removes `<script>` and `javascript:` URIs.
- The mention decorator escapes hostile attribute content.
- File-size enforcement aborts mid-stream.

The items in the backlog above are *not yet enforced* — they're for our next pass.
