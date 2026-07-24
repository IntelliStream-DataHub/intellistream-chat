# ThreadOrbit — Security Plan

> **Note:** this is the historical hardening audit and checklist. For the current, actively
> tracked security/bug backlog and its status, see [`tasks.md`](tasks.md).

> **Companion test files:**
> - `src/test/java/ai/intellistream/threadorbit/integration/SecurityBoundaryIT.java` — locks in the
>   AuthN/AuthZ/sanitisation invariants the codebase has always asserted.
> - `src/test/java/ai/intellistream/threadorbit/integration/InternetExposureSecurityIT.java` — locks
>   in the additions made for public-internet exposure (per-user upload cap from Keycloak,
>   GET rate limits, Lucene wildcard refusal).

---

## 2026-04-26 update — internet-exposure pass

A second review focused on what would matter once this app is reachable from the public
internet, with the new features that shipped between 2026-04-25 and 2026-04-26 (DMs,
hovercard, mention notifications, attachments, idle logout, virtual threads, Lombok
refactor) folded in. Outcome:

### Resolved this round

- **[Was H] Cookie `Secure` flag missing.** Fixed: both the JSESSIONID and CSRF cookies now
  auto-detect `Secure` **per request** from `request.isSecure()` — no env var. Behind a
  TLS-terminating proxy, `forward-headers-strategy: framework` sets `request.isSecure()` from
  `X-Forwarded-Proto: https`, so the cookies are marked Secure automatically; on plain-HTTP
  local dev they aren't, so they round-trip. See the class comment in `SecurityConfig`.
- **[Was H] No upload cap once streaming was added.** Fixed: per-user 50 MiB default,
  unlimited for `chat-admin`, override per-user via Keycloak attribute
  `chat_max_upload_bytes` mapped through to the JWT by the protocol mapper in
  `keycloak/realm.json`. `CurrentUser.uploadCapBytes(Principal)` resolves; both
  `AttachmentService.upload` and `ConversationAttachmentService.upload` enforce
  pre-flight (declared size) and during-stream (chunk count) via the shared
  `AttachmentBytes.streamToFile(in, target, maxBytes)`.
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

### Still open / handed to operations

- **[H-ops] A bundled dev Keycloak client secret ships in `realm.json`.**
  Cannot be fixed in code without breaking `podman compose up -d` for newcomers.
  Listed as item 1 in the README's Production Hardening Checklist; rotation is the
  operator's responsibility before flipping DNS.
- **[M-ops] Open registration without verification or captcha.** Bot mass-registration
  is possible. Listed as item 6 in the README's Production Hardening Checklist; turn
  on **Verify email** in Keycloak before exposing publicly.
- **[L] HSTS unconditionally set, including over plain HTTP in dev.** Cosmetic only —
  HSTS is no-op without HTTPS. Acceptable.
- **[I] In-memory `RateLimiter` doesn't compose across replicas.** Single-instance only;
  scale-out needs a distributed limiter (Bucket4j+Hazelcast or Redis). Listed in the
  Production Hardening Checklist.

### Original 2026-04-25 review follows below for historical context.

---

## Original review (2026-04-25)

Each finding is tagged by severity: **[H]** high, **[M]** medium, **[L]** low,
**[I]** informational. Some have since been resolved — see the update section above.

---

## 1. Authentication

### What's in place
- Two filter chains in `SecurityConfig`:
  - **API/WS chain** (`/api/**`, `/ws/**`): bearer JWT issued by Keycloak, CSRF off.
  - **Web chain** (everything else): OAuth2 login (authorization code) → Keycloak,
    cookie session, CSRF on with `CookieCsrfTokenRepository.withHttpOnlyFalse()`.
- `CurrentUser` is the only place that translates a Spring principal to a domain `User`,
  upserting on first sight.

### Findings
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

### Next steps
- Decide the API auth story (bearer-only vs. cookie-bearer hybrid) and document it in
  `CLAUDE.md`. Add an IT that calls `/api/channels` with no Authorization header and asserts
  the documented behaviour.
- Tighten cookie attributes (`SameSite=Strict`, `Secure` when HTTPS).

---

## 2. Authorization

### What's in place
- `ChannelService.requireMember` / `requireAdmin` gates service operations.
- Public channels are readable by any authenticated user; writes require membership.
- `MessageService.edit` is author-only. `MessageService.delete` is author-or-admin.
- `AttachmentService.requireForDownload` gates downloads via `requireMember`.
- `MentionService.syncMentions` only inserts rows for users that exist; doesn't leak presence.

### Findings
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

### Next steps
- Rate-limit message POSTs and uploads (per-user, sliding window).
- Decide whether public channels should require a join to read. If yes, change
  `requireMember`'s shortcut and update the IT.
- Add a service-level invariant that thread replies inherit the parent's channel.

---

## 3. Input validation & storage

### What's in place
- `@Valid` on request DTOs (`SendMessageRequest`, `EditMessageRequest`, etc.) with
  `@NotBlank` and `@Size(max = 8000)`.
- `MessageService.post` re-checks emptiness and length defensively.
- `AttachmentService.upload` enforces filename presence, sanitises filename to a basename,
  caps size at 50 MiB *while streaming* (`AttachmentService.streamToFile` aborts the moment
  the running byte count exceeds the cap).
- `AttachmentService.resolve` rejects storage keys that escape the storage root via
  `Path.normalize()` + `startsWith(storageRoot)`.
- `Channel.slug` is generated server-side from a regex and capped.

### Findings
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

### Next steps
- Tighten `sanitizeFilename`: strip `..`, control chars, NUL; normalise unicode.
- Add MIME sniffing on upload; add `X-Content-Type-Options: nosniff` to download responses.
- Validate usernames during OIDC provisioning.

---

## 4. Output encoding / XSS

### What's in place
- `MarkdownRenderer` parses CommonMark, sanitises with jsoup `Safelist.basic` plus a
  controlled allowlist (headings, tables, code blocks, `class` on `<pre>`/`<code>`,
  `class`+`data-username` on `<span>` for mentions).
- Mention decoration runs *after* sanitisation and uses HTML-escaped values for both text and
  attribute content.
- Client renders `bodyHtml` via `innerHTML` (intentional — that's the contract).

### Findings
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

### Next steps
- Add `rel="noopener noreferrer"` post-processing for anchors in `MarkdownRenderer`.
- Move the inline `<script>` in `channels.html` to a static file (e.g. `theme-loader.js`)
  and enable a strict CSP.

---

## 5. Persistence / SQL

### What's in place
- All queries are JPA-managed (`Spring Data JPA`) with named/positional parameters.
- The native search query uses `:channelId`, `:q`, `:lim` parameters — no string
  concatenation.
- Migrations live under Flyway with `ddl-auto=validate`.

### Findings
- **[L] Native queries use parameter binding correctly.** No SQL injection risk seen.
- **[L] Postgres user is created with `chat` / `chat` in `application.yml` defaults.**
  Acceptable for local dev; ensure production uses a secret.
- **[I] No row-level security in Postgres.** Authorization is purely application-level.
  Acceptable; document it.

---

## 6. Logging & PII

### What's in place
- `application.yml` sets `org.springframework.security: INFO`, `org.hibernate.SQL: WARN`.
- No structured logging configured.

### Findings
- **[M] On error, `ApiExceptionHandler` returns the raw `IllegalArgumentException` /
  `IllegalStateException` message to the client.** Many of these messages include identifiers
  (`Message not found: <uuid>`, `User not provisioned: <subject>`). Low risk because the
  caller already knew the id. Higher-risk: a future exception that includes a query body.
  Audit before adding new throws.
- **[L] No PII redaction in logs.** OIDC subjects and usernames will end up in the log if
  Spring Security debug logging is ever enabled.

### Next steps
- Add a generic-error filter that returns a stable error code + opaque message; surface the
  detail only in the server log.
- Document a logging policy for usernames / display names.

---

## 7. WebSocket / STOMP

### What's in place
- `/ws` SockJS handshake gated by the API filter chain (JWT-required). Subsequent messages
  ride on the established session.
- `ChatWebSocketController.send` gates writes via `messageService.post` →
  `channelService.requireMember`.

### Findings
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

### Next steps
- Wire a STOMP `ChannelInterceptor` that authorizes SUBSCRIBE per channel.
- Add per-user message rate limiting.

---

## 8. File upload / download

### What's in place
- Streaming upload via Apache Commons FileUpload — never buffers the full file in memory.
- 50 MiB hard cap enforced *during* streaming.
- Storage keys are random UUIDs; original filenames live only in the DB.
- Path-escape protection in `AttachmentService.resolve`.

### Findings
- **[M] Download authorization uses `requireMember`,** which means *anyone authenticated*
  can download attachments from public channels regardless of whether they joined. Same
  semantic as message read. Document or tighten.
- **[L] No `Content-Disposition: attachment` enforcement for image previews.** Today the
  download endpoint always sets `attachment`. The image preview (`<img src="…/download">`)
  works because browsers ignore `Content-Disposition` for `<img>` requests. If a future
  feature serves *inline* images on a sub-route, ensure it sniffs the content type.
- **[L] Multipart parts other than `file` and `caption` are silently drained.** Defensive
  but masks bugs (e.g. a typo'd field name). Reject unknown form fields with 400.

### Next steps
- Decide whether public-channel attachments require membership.
- Reject unknown multipart fields with a clear 400.

---

## 9. Dependencies

### What's in place
- Spring Boot 4.0.5, Java 25, Postgres 18, Lucene 10.4.0, jsoup 1.18.1, commonmark 0.22.0,
  commons-fileupload2 2.0.0-M2, Apache Tika is *not* a dependency yet.

### Findings
- **[M] `commons-fileupload2-jakarta-servlet6:2.0.0-M2` is a milestone build.** Pin to a
  GA release once available; track for security updates.
- **[L] No automated dependency-vulnerability scanning.** Add `org.owasp.dependencycheck`
  or rely on Renovate/Dependabot.

---

## 10. Headers & deployment

### Findings
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

## Priority backlog

| # | Item | Severity | Status |
|---|------|----------|--------|
| 1 | STOMP SUBSCRIBE authorization (block snooping on private channels) | **H** | ✅ done — `StompAuthorizationConfig` |
| 2 | API auth story (cookie vs. bearer) — document & test | **H** | ✅ documented in `CLAUDE.md` |
| 3 | Per-user rate limiting (REST + STOMP) | **H** | ✅ done — `RateLimiter` (in-memory; replace for multi-instance) |
| 4 | Default security headers (CSP, nosniff, HSTS, Referrer-Policy) | **H** | ✅ done in `SecurityConfig` |
| 5 | MIME sniffing on upload + nosniff on download | **M** | ✅ done — `AttachmentService.sniffContentType` + `X-Content-Type-Options` header |
| 6 | `rel="noopener noreferrer"` on rendered anchors | **M** | ✅ done — `MarkdownRenderer.hardenAnchors` |
| 7 | Tighten `sanitizeFilename` (`..`, controls, NUL, unicode) | **M** | ✅ done |
| 8 | Service-level invariant: thread reply inherits parent channel | **M** | ✅ enforced by `MessageService.replyInThread` (`channel = parent.getChannel()`); covered by `SecurityBoundaryIT` |
| 9 | Cookie attributes (`SameSite=Strict`, `Secure`) | **M** | ✅ `SameSite=Strict` on JSESSIONID + CSRF cookie; `Secure` should be added in HTTPS deploys via `server.servlet.session.cookie.secure=true` |
| 10 | Strict CSP — extract inline `<script>` first | **M** | ✅ done — moved to `theme-loader.js` / `profile.js` |
| 11 | Decide public-channel read posture (member-only vs. anyone-authenticated) | **M** | ✅ documented in `CLAUDE.md`; current behavior kept |
| 12 | OWASP dependency scanner + dependency upgrades | **M** | ⚠️ deferred — listed under roadmap in `CLAUDE.md` for opt-in |
| 13 | Validate usernames during OIDC provisioning | **L** | ✅ done — `UserService.sanitizeUsername` |
| 14 | Generic error envelope, redact identifiers | **L** | ✅ done — `ApiExceptionHandler` returns `{code, message, traceId}` with full detail logged server-side only |
| 15 | Reject unknown multipart fields with 400 | **L** | ✅ done in `AttachmentRestController` |

---

## What the new IT covers

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
