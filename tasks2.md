# IntelliStream Chat — Second bug-hunt pass

Generated 2026-07-24 from a fresh multi-track audit (services/concurrency, web/controllers/WS,
front-end JS, persistence/schema/search, security, and a diff-review of the P1/P2 fixes landed
earlier this session). Seven reviewers ran independently; findings below are the **deduplicated,
cross-corroborated** set, each re-checked against current code. None duplicate `tasks.md`.

**Headline:** this pass found real defects *in the fixes that `tasks.md` marks done* — most
importantly, the insert-race remediation (BUG-17 / BUG-2 / poll vote) is **ineffective on
Postgres** (runtime-confirmed below), and the CLEAN-3 reconcile and the BUG-3 reconnect backfill
each have a data-loss bug. Several BUG-* front-end fixes were applied to the channel page but
never ported to the DM page.

Legend: `[ ]` todo · severity per heading · `file:line → fix`. Items tagged **[regression]**
are defects introduced by (or left incomplete in) an earlier fix in `tasks.md`.

---

## P0 — correctness / confidentiality, fix first

### N1 · Insert-race "catch-and-reread" recovery is dead code on Postgres 🔴 high **[regression, runtime-confirmed]** — ✅ FIXED
> Fixed: all sites moved to native `INSERT … ON CONFLICT` (DO NOTHING / DO UPDATE), which keeps
> the transaction usable so the re-read succeeds. `InsertRaceIT` races each path across 8 threads.
The BUG-17 / BUG-2 remediation wrapped racy inserts in `saveAndFlush` + `catch(DataIntegrityViolationException)` + re-read — but the catch sits **inside the same `@Transactional` method** whose transaction the failed INSERT just poisoned. On Postgres the recovery cannot work: (a) Hibernate's failed flush leaves the session broken (the IDENTITY entity has a null id — reusing the session throws `AssertionFailure`), (b) Postgres aborts the whole tx on the constraint error (SQLSTATE 25P02), so the re-read SELECT throws "current transaction is aborted", and (c) both Hibernate and the participating repository proxy mark the tx rollback-only, so even a successful read couldn't commit.
- **Runtime-confirmed** against Testcontainers Postgres 18: replaying `join`'s exact sequence after seeding the winner row produced `org.hibernate.AssertionFailure: Entry for instance of 'ChannelMember' has a null identifier (this can happen if the session is flushed after an exception occurs)` — i.e. the loser still 500s.
- Sites (all inside `@Transactional`): `ReadStateService.java:60-69` (markRead — fires on every live message, the most reachable), `ChannelService.java:110-113` (join) & `:127-130` (invite), `ReactionService.java:67-74` (addReaction), `PollService.java:113-119` (castVote), `UserService.java:157-167` (upsert — **500s a concurrent first login**, the BUG-2 case), `ConversationService.java:70-79` (directBetween — `tasks.md` cites this as the "correct" reference pattern; it has the same flaw).
- Unguarded siblings with the identical race that need the same real fix: `ConversationReactionService.addReaction`, `ConversationService.addToGroup:109-110`, `PresenceService.setStatus`/`setKind`.
- **Fix:** do the insert-or-ignore as a native `INSERT … ON CONFLICT DO NOTHING` then SELECT (the codebase already does this correctly in `ChannelReadRepository.markAllChannelsWithUnreadMentionsRead`), **or** attempt the insert in a `REQUIRES_NEW` self-proxy method (the `ReminderScheduler` pattern) so only the inner tx aborts. Add an IT that seeds the winner in a committed tx and replays the loser — none of these paths has a test today.

### N2 · Mention inbox & bell badge leak PRIVATE-channel content to non-members 🟠 high (confidentiality) — ✅ FIXED
> Fixed on both sides: `syncMentions` drops non-readers before persisting rows (also stops the live
> notification), and the three inbox queries now require membership-or-PUBLIC. Covered by `MentionInboxIT`.
No channel authorization exists anywhere on the mention read path — the "IDOR is prevented" note in `tasks.md`'s good-news section missed this.
- `MentionService.syncMentions:70-88` resolves every `@handle` to any existing user (`findByUsernameIgnoreCase`) and writes a `message_mentions` row **with no check that the mentioned user can read the channel**.
- `MessageMentionRepository.findUnreadInbox:77-92`, `countUnreadFor:61-70`, and `countMentionsPerChannel:41-53` join `message_mentions → messages → channels` filtered only on `mn.user_id` — **no `channel_members` join, no `channel.type` filter**. `findUnreadInbox` returns `channel.slug`, `channel.name`, author identity, and a 240-char `body_markdown` snippet.
- Exploit: Alice posts "`@bob the deal closes at $5M`" in a PRIVATE channel Bob isn't a member of → `GET /api/mentions` hands Bob the private channel name and the body snippet, and the bell count reveals the number of such mentions. Also keeps leaking to a user after they've been removed from a channel.
- **Fix:** in `syncMentions`, only persist mention rows for users who can read the message's channel (member, or channel is PUBLIC) — matching Slack/Mattermost semantics — **and/or** add a `channel_members` join / `type='PUBLIC'` predicate to all three inbox queries.

---

## P1 — data-loss / visibility bugs (several are defects in shipped fixes)

### N3 · Lucene reconcile (CLEAN-3) deletes freshly-indexed messages 🟡 medium **[regression]** — ✅ FIXED
`cleanup/CleanupTasks.java:146-167`: `findAllMessageIds()` snapshots the DB **before** `allIndexedIds()` enumerates the index. A message committed + indexed (afterCommit) between the two reads is in `indexIds` but not the older `dbIds` → classified "stale" → `messageIndex.deleteAll(stale)` removes its doc (when `dry-run=false`). It stays search-invisible until the next reconcile (~1h) re-adds it, and recurs every run on a busy server. The file sweep has a 24h mtime grace for exactly this commit-window race; the index reconcile has none. → **Fix:** snapshot the index **first** (the race then degrades to a harmless duplicate re-index), or exclude ids above `max(dbIds)` (ids are monotonic), or `existsById`-recheck each stale candidate before deleting.

### N4 · BUG-3 / BUG-14 / BUG-15 fixes never ported to the DM page 🟡 medium **[regression]** — ✅ FIXED
`tasks.md` marks these done for both `chat/index.js` and `conversation.js`, but only the channel side was implemented:
- **BUG-3 (reconnect catch-up):** `conversation.js:364-394` `onConnect` has no backfill at all → every DM sent during a blip/sleep is missing until reload.
- **BUG-14 (reaction wipes edit draft):** `conversation.js:181` `replaceMessageDom` removes `.message-edit` unconditionally, and DM reactions broadcast `message-updated` → anyone reacting destroys the author's unsaved edit.
- **BUG-15 (force-scroll):** `conversation.js:126` `scrollIntoView({block:'end'})` runs for every incoming message → yanks a DM reader reading history to the tail.
- **Fix:** port each guard from the channel page (backfill loop; "edit form open + not a body change → refresh trays only"; near-bottom scroll check).

### N5 · STOMP reconnect backfill (BUG-3) silently truncated at 50 + can render out of order 🟡 medium **[regression]** — ✅ FIXED
`chat/index.js:604-616` requests `?after=<last>&limit=200`, but `MessageService.after:113` clamps to `DEFAULT_PAGE_SIZE=50` and the client never pages → if >50 messages were missed, only the oldest 50 load and a permanent invisible gap remains (no indicator) until reload. Separately, the subscription registers while the backfill is in flight, so a live `created` event appended first ends up **above** the older backfill rows (wrong order, wrong grouping). Backfill also returns only top-level rows, so thread counts stay stale. → **Fix:** loop `?after=` until a short page; buffer live `created` events until the backfill resolves (or insert by `createdAt`), then `refreshDayDividers()`.

### N6 · `POST /api/channels/{id}/messages` never broadcasts over STOMP 🟡 medium — ✅ FIXED
`web/ChannelRestController.java:230-243` persists + indexes + returns the DTO but never `broker.convertAndSend("/topic/channels/{id}", MessageEvent.created(...))`. Every sibling create path broadcasts (WS send, HTTP thread reply `MessageRestController:174`, attachment upload, DM HTTP send). A message posted through this documented HTTP twin is invisible to connected clients until reload and fires no mention notifications. → **Fix:** build the DTO and broadcast like `reply()`.

### N7 · Permalink to a deleted message breaks the whole channel page 🟡 medium — ✅ FIXED
`web/HomeController.java:183` `safeAround` catches only `IllegalArgumentException`, but `MessageService.around` throws `ResourceNotFoundException` for a missing or channel-mismatched anchor (only the thread-reply case is IAE). So a stale mention/search deep-link `/channels/{id}?m=<deleted-id>` escapes the fallback-to-recent the method's own javadoc promises, and `ApiExceptionHandler` renders a **bare JSON 404 in place of the HTML channel page**. Stale permalinks are routine after deletion. → **Fix:** also catch `ResourceNotFoundException` and fall back to recent.

### N8 · Channel invite is an unthrottled username-enumeration oracle 🟡 medium (incomplete SEC-5) — ✅ FIXED
`web/ChannelRestController.java:112-121`: SEC-5 added the `user-lookup` limiter to `startDirect`/`createGroup`/`addMember` but **not** `invite`, and `invite` resolves the username (`requireByUsername` → 400 for unknown) **before** `requireWriteAccess` (→ 403 for an existing user). A non-member thus gets 403-vs-400 as a clean existence oracle with no rate limit. `setMemberRole:128-141` has the same order-of-checks issue. → **Fix:** add the `user-lookup` limiter and check write-access **before** resolving the username.

### N9 · 413 `maxBytes` (and unread counts) serialized as JSON strings — breaks the upload-error UX 🟡 medium **[regression]** — ✅ FIXED
The global `Long → ToStringSerializer` (commit `1610c41`) turns `ApiExceptionHandler.java:96` `Map.of(…, "maxBytes", <long>, …)` into `"maxBytes":"52428800"` (autoboxed to `Long`). All three clients guard `typeof err.maxBytes === 'number'` (`profile.js:150`, `conversation.js:~522`, `chat/index.js:~951`) → now always false → the precise "max N MiB" message never renders; users see the generic failure. Same root cause at `MentionRestController.java:67` (`{"unread":"5"}`). → **Fix:** return small typed records with **primitive** `long` fields (primitive `long` keeps the default number serializer), or relax the JS `typeof` checks.

---

## P2 — lower-severity bugs & polish

### Front-end
- [x] **N10 · Channel composer send no-ops while STOMP is disconnected** low — `chat/index.js:854-857` calls `stomp.publish` with no `connected` check; StompJS throws during the reconnect/handshake window and the async rejection is unhandled → Enter silently does nothing (slash commands too). The DM page solved this (`conversation.js:401-443` `awaitConnected` + HTTP fallback); the channel page didn't. → Port the awaitConnected/HTTP-fallback pattern (needs N6's broadcast to be useful).
- [x] **N11 · `closeThread` doesn't cancel an in-flight `openThread` fetch** low — `chat/index.js:2041-2060`: the BUG-18 `threadReq` guard covers click→click but not click→close; a late response reopens the closed panel. → `threadReq++` in `closeThread`.
- [x] **N12 · Search dropdown reopens after Escape / outside-click** low — `chat/index.js:377-381,502-511`: `close()` neither clears the 220ms debounce nor bumps `inflight`, so a search scheduled just before dismissal fires and re-renders. → `clearTimeout(debounce)` + `++inflight` in `close()`.
- [x] **N13 · Deleting a day's first message erases the day divider / orphans a grouped row** low — `chat/index.js:1847-1860` `removeMessageDom` calls `positionDayDividers()` not `refreshDayDividers()`, so remaining same-day rows lose their label and a following `.grouped` row renders with no avatar/author. → Call `refreshDayDividers()` after removal.
- [x] **N14 · Partial multi-file upload can duplicate the caption** low — `chat/index.js:838-852`: the first upload consumes `body` as caption; if a later file fails, `input.value` isn't cleared, so resubmitting re-posts the caption. → Clear the input (or mark the caption consumed) after the first success.

### Web / API
- [x] **N15 · Typing indicator uses the read check** low — `ChatWebSocketController.java:125` `typing()` calls `requireMember` (PUBLIC short-circuits), so a non-member can broadcast "X is typing" into any public channel. Convention is `requireWriteAccess` for channel-directed writes. → Switch to `requireWriteAccess`.
- [x] **N16 · `UploadParts.readSmallField` corrupts multibyte UTF-8 captions** low — `UploadParts.java:44-51` decodes each ≤1024-byte read independently, so a character split across a read boundary becomes U+FFFD; it also compares char length against a byte limit. → Accumulate into a `ByteArrayOutputStream` and decode once.
- [x] **N17 · Multipart part-ordering bugs in the streaming uploads** low — `AttachmentRestController.java:96-131`, `ConversationRestController.java:320-347`: a caption arriving *after* the file part is silently dropped, and an unknown field after the file throws 400 **after** the upload tx committed → ghost row (no broadcast) + client retry duplicates. → Collect non-file fields before persisting; don't error after commit.
- [x] **N18 · Avatar GET is an enumeration signal at 5× the profile budget** low — `AvatarRestController.java:122-128` returns 400 (unknown user) vs 404 (no avatar) at 600/min, undercutting the deliberate 120/min anti-enumeration cap on the profile endpoint. → Return a uniform 404 for unknown users.
- [x] **N19 · Split-brain user-destination routing** low — `WebSocketExceptionAdvice.java:66` routes notices by `principal.getName()` (`preferred_username`, the registry key — correct), but `ChatWebSocketController.java:94` (slash-command errors) routes by `user.getUsername()` (sanitized). When they differ (email-style username, or a BUG-2 collision suffix) the slash-error path silently delivers nothing. → Route both by the same key the user-destination registry uses.
- [~] **N20 (accept-by-design: silent drop avoids tearing down the connection; 200/min cap is generous) · SUBSCRIBE rate-limit drop is silent** low — `StompAuthorizationConfig.java:119-123` `return null` drops the frame but StompJS still registers the subscription locally, so a user whose connect burst exceeds 200 subscribes/min ends with silently-dead sidebar channels (no error, no retry). → Emit a client-visible error frame, or raise the cap for the initial burst.

### Mentions / search / persistence
- [x] **N21 · `@mentions` inside code blocks still notify** low — `MentionService.syncMentions` regexes raw markdown while `MarkdownRenderer:188-193` deliberately skips mentions inside `<code>`/`<pre>`. So `@alice` in a fenced block creates a mention row + bell badge for a "mention" that renders as plain code. → Extract from the CommonMark AST, skipping code spans.
- [x] **N22 · Sentence-final `@handle.` swallowed** low — the `MENTION` regex (`MentionService.java:49`) includes `.`/`-` in the handle class, so `"thanks @bob."` captures `bob.` → resolves to nobody → no notification/highlight for the commonest sentence-final form. → Trailing-punctuation trim / progressive-trim fallback on lookup.
- [x] **N23 · Lucene author field goes stale after a username change** low — the index stores the author username at write time (`MessageIndexService.java:105`); `UserService.upsert` rewrites usernames on login (claim change / BUG-2 suffix) without reindexing, and CLEAN-3 diffs **ids only**. After a rename `@newname` misses every pre-rename message forever while `@oldname` keeps matching them. → Reindex a user's messages on username change, or have the reconcile compare fields.
- [~] **N24 (deferred: needs a client-side composite cursor; same-microsecond bulk-import edge) · BUG-20 only half-applied** low **[regression]** — `before()`/`after()` (infinite scroll + the N5 reconnect backfill) still keyset on `createdAt` alone (`MessageRepository.java:64-82`), so a same-timestamp message straddling a page boundary is still skipped; `recent()`/`before()` also re-sort by `createdAt` only (`MessageService.java:91,100`). Only `around()` got the composite `(createdAt,id)` keyset. (Documented as deferred in the BUG-20 commit; listed here for completeness.) → Reuse `findTopLevelBeforeKeyset`/`AfterKeyset` with `(createdAt,id)` cursors; add `.thenComparing(getId)` to the re-sorts; consider extending `ix_messages_channel_created` to `(channel_id, created_at, id)`.
- [x] **N25 · Startup rebuild can leave duplicate index docs** low — `MessageIndexService.rebuild:236-242` uses `addDocument`; `rebuildIfEmpty` runs on `ApplicationReadyEvent` while the server already accepts posts, so a concurrent `index()` for the same id landing before rebuild's `addDocument` yields two docs (never reconciled). → Use `updateDocument(new Term(F_ID,…), doc)` in `rebuild()`, and/or rebuild before opening the port.
- [x] **N26 · Reconcile re-index commits Lucene once per doc under a held DB connection** low — `CleanupTasks.java:169-175` calls `index()` per missing id and `MessageIndexService.index` does `commit()`+`maybeRefresh()` per call → a large backlog means thousands of fsync-commits inside the `@Transactional(readOnly=true)` method, pinning a Hikari connection. → Batch into one commit (like `rebuild()`).
- [x] **N27 · `findByUsernameIgnoreCase` can't use the `lower(username)` index** low — `UserRepository.java:32`: Spring's `IgnoreCase` derives `UPPER(username)=UPPER(?)`, which the V2 `lower(username)` functional unique index can't serve → seq scan on `users` on every mention resolution, `requireByUsername`, and login. → Explicit `@Query("… where lower(u.username)=lower(:u)")` (matching `findAllByUsernameLowerIn`).
- [x] **N28 · N+1 query hotspots** low — sidebar lazy-loads each membership's channel (`SidebarService.java:58-64`, and `isAdminForAny` re-runs the same query); reaction serialization reads `r.getUser().getUsername()` without a fetch-join (`MessageReactionRepository`/`ConversationReactionRepository` `findByMessageIn…`); `HomeController.listDirectConversations:224-239` runs a members query per DIRECT conversation on every page load. → Add `join fetch` variants for the sidebar and reaction paths.

### Content / correctness polish
- [x] **N29 · jsoup safelist lets users forge mention markup** low — `MarkdownRenderer.java:51,55` allow `span[class,data-username]`; CommonMark passes raw HTML through, so a user can hand-write `<span class="mention" data-username="admin">@admin</span>` and render a styled, clickable "mention" of anyone. Not XSS (client escapes `data-username`), but it defeats server-authoritative mention decoration. → Drop `span`/`data-username` from the safelist and let `decorateMentions` own that markup.
- [x] **N30 · `/remind` double-notifies the target and broadcasts pre-commit** low — the confirmation body contains `@bob`, so the target is mention-notified at schedule time and again at fire time (`RemindCommand.java:107-110`); and `ReminderScheduler.java:138` broadcasts inside the `REQUIRES_NEW` tx before commit (a failed commit shows clients a message that never persisted). → Suppress the self-mention in the confirmation; move the broadcast to after the tx commits.
- [x] **N31 · BUG-11 "clamp to about a year" doesn't clamp** low/cosmetic — the bound is applied to the raw amount regardless of unit, so `/remind in 3000000000d` passes the "within about a year" guard (no crash — the goal of avoiding a 500 is met, but the clamp claim in the message/commit is false). → Convert to a `Duration` first, then bound.
- [x] **N32 · `LuceneConfig` property name drifted from the docs** low — code reads `ichat.search.lucene-dir` (`LuceneConfig.java:30`) but CLAUDE.md and the `IntegrationTestApplication` javadoc document `chat.search.lucene-dir`; an operator following the docs sets an ignored property. → Fix the docs (all sibling props use `intellistream.`).

---

## Verified sound this pass (no action)

Recorded so they aren't re-litigated. The reviewers explicitly confirmed: the `RateLimiter`
`compute`/`computeIfPresent` rewrite (BUG-22) is atomic and leak-free; `demote`'s `FOR UPDATE`
last-admin lock (BUG-23) genuinely closes the TOCTOU under READ COMMITTED; `around()`'s composite
keyset + re-sort is correct; `LuceneBootstrap` streaming (BUG-24) and `allIndexedIds()` (deleted-doc
handling via `MultiBits.getLiveDocs`) are correct; every paginated `join fetch` query fetches only
to-one associations (DB-side pagination, no in-memory paging); schema ↔ entity columns all match
(no `ddl-auto=validate` risk); FK cascade coverage on all live delete paths is complete; the
`MessageService.delete` / `ChannelService.destroy` / DM-delete file+index cleanup ordering is right
(BUG-9/10); `PresenceTracker` idempotent-disconnect (BUG-7), the WS-1 `@MessageExceptionHandler`
advice, WS-2 heartbeats/idle-timeout, the `MentionService` flush fix (BUG-1), `profile.js`
blob-revoke/swap-on-2xx (BUG-19), `appendMessage` de-dupe (BUG-16), and the mention deep-link +
timestamp-hydration fixes (BUG-5/13) are all correct; search injection/DoS guards, admin gating,
attachment path/size/Tika handling, and DOM-XSS sinks remain sound.

---

## Suggested sequencing
1. **N1** first — it's the highest-impact and defeats three checked-off items; the codebase already
   has both correct patterns (`ON CONFLICT`, `REQUIRES_NEW`) to copy. Ship it with the race IT that's
   currently missing.
2. **N2** next (private-channel confidentiality), then the P1 data-loss/visibility set (N3–N9),
   arming CLEAN-3 (N3) only after its ordering fix.
3. Roll the P2 items opportunistically; N4/N5/N24 are the "finish the job" follow-ups to fixes that
   only half-landed.
