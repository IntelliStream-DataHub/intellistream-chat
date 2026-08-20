# Changelog

Notable changes to IntelliStream Chat. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [semantic versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — unreleased

First public release, so this entry describes what the release contains rather than diffing it
against anything: there is no released version for something to have been fixed in.

### Chat

- **Channels**, public or private. The sidebar lists every channel you are in, alphabetically,
  with the ones you star lifted into a Favourites group — not a ranked shortlist, because a list
  that reorders itself under you defeats the thing a sidebar is for. Channels can be renamed,
  re-described and archived; members can leave, and when the last admin leaves the role passes to
  the longest-standing member rather than stranding the room.
- **Threads** in a side panel, so replies stay together without burying the channel. A reply marks
  the channel unread and reaches the people already in the thread.
- **Direct and group messages** with the same surface as a channel: threads, typing indicators,
  read state, reactions, attachments, and a per-conversation notification level, so a group that
  became a standing meeting can be turned down without muting the person in it. You can leave a
  group; a one-to-one you simply stop using. A conversation with yourself is a real one — it is
  where your reminders land.
- **Markdown** rendered server-side and sanitised with jsoup, with a formatting toolbar and a
  live preview in the composer.
- **Emoji reactions**, with a picker over roughly 650 emoji — including on your own messages.
- **@mentions** with a typeahead that matches display names as well as handles. Mentions resolve
  against the exact username while the interface shows people by display name, so without it the
  only way to mention someone was to already know a string you were never shown. Plus
  `@channel` and `@here`, a mention inbox, per-user read state, and permalinks that survive the
  login round-trip.
- **Unread on the Slack model**: a bold channel name for ordinary unread, a number only when
  someone used your name or it is a direct message. A count on every busy channel is noise that
  teaches people to ignore the badge that matters. Muted channels are dimmed and still count.
- **Pin** a message to the channel, **save** one to a private list, **forward** it elsewhere, or
  **quote** it into a reply. Forwarding out of a private channel asks first; forwarding out of a
  direct message is not offered at all.
- **Presence and custom status**, workspace-wide, with a Do Not Disturb that genuinely silences —
  toast, sound and OS notification — while unread counts and the mention inbox keep working.
  Silencing an interruption is not the same as hiding information.
- **Polls**, built in a dialog or typed as a `/poll` slash command. Editing a poll reopens the
  dialog; once anyone has voted the options are frozen, because a vote is a statement about a
  specific set of choices.
- **Slash commands** — `/help`, `/poll`, `/remind`. A `/word` naming no command is refused
  privately and never posted, so the muscle memory people arrive with (`/leave`, `/dnd`, `/me`)
  does not become a message the whole room reads. Reminders arrive as a direct message, at the
  time your own timezone says.
- **Twenty themes**, five of them dark. Every accent and highlight in the palette clears the
  WCAG AA 4.5:1 contrast threshold against the text it carries.

### Files

- **Streamed uploads.** The request body *is* the file — no multipart boundary scan — with the
  filename and caption in percent-encoded headers. Bytes go straight to disk and are never fully
  buffered in memory, so there is **no per-file size cap**: a file is as large as it is.
- **Inline image previews** with an in-page lightbox.
- **A per-user file manager** at `/files`: browse and search everything you have uploaded, see
  where it was posted and what it costs you, and delete it. Deleting a file leaves its message
  standing and records in place that the file was removed, when, and by whom — your caption and
  anyone's replies survive.
- **Per-channel file browsing**, so finding a PDF someone shared last month does not mean
  scrolling the channel or remembering words from the message that carried it.
- **Per-account storage quotas** (2 GiB by default), credited back in the same transaction as the
  delete. Being a total rather than a per-file limit, this is what bounds the largest single file
  an ordinary account can send; workspace admins are exempt from it.

### Search

- **Embedded Apache Lucene**, on disk, no separate search cluster.
- Covers **every channel you are allowed to read** — joined or not — every conversation you are
  in, and **attachment filenames**, in one ranked list on a results page with counts and paging.
- **Slack's syntax**: `from:@bob` for what someone wrote, `@bob` for where they were mentioned,
  `in:#channel` to narrow.
- The access rule is **part of the Lucene query**, not a filter over its results, so content you
  cannot read is never scored, counted, ranked or highlighted. A workspace administrator cannot
  search other people's conversations at any privilege level.

### Notifications

- **Per-channel notification levels** on the Slack/Mattermost model: an account-wide default and
  a per-channel override whose default value is *inherit*, not a copy — change the account
  setting and every channel you have not explicitly overridden moves with it. Muting is the
  bottom of that same control.
- **A separate account default for direct messages**, because the same word wants different
  answers in the two places: "mentions only" is a sensible way to follow a channel and a broken
  way to receive a message somebody sent to you alone. Channels default to mentions,
  conversations to every message — which is what lets a large group DM be set to mentions-only
  and have it mean that.
- **Notification sounds**, set separately for mentions and direct messages, chosen from fifteen
  synthesised in the browser — no audio files to ship, serve or license.
- In-tab toasts, plus OS notifications where the browser permits them.

### Administration

- **OIDC single sign-on via Keycloak**, with a branded login theme included.
- **An admin console**: suspend an account and close its live sessions, clear or restore a
  user's messages, set per-person storage quotas, and choose who may create channels.
- **Channel deletion** is a workspace-admin action — it takes other people's messages and files
  with it — so channel admins get archive, which is reversible.
- **An append-only audit trail** of administrative actions.
- **Message retention** with a scheduled purge, and background cleanup of orphaned files.

### Operating it

- **One JVM process, one Postgres database, one systemd unit.** No message broker, no search
  cluster, no cache tier.
- Runs a workspace of a thousand people in **under a gigabyte of memory on a single core**.
- **Flyway migrations**, **health endpoints**, and configuration through environment variables —
  every tunable listed in `.env.example` — with an optional **Vault/OpenBao** backend for the
  database and Keycloak credentials.
- **An optional read replica.** Point `ICHAT_DB_REPLICA_URL` at a standby and every
  `@Transactional(readOnly = true)` is served from it, while writes and migrations stay on the
  primary — 90–95% of queries on a read-heavy mix, and all of them on an endpoint that only reads.
  Off by default, and off means the second pool does not exist rather than sitting idle — a
  deployment that never asks for one gains no proxy and no new way to fail. The replica can be
  configured entirely from Vault alongside the primary's credentials.
- **Identifying the caller costs one read instead of three.** Resolving a logged-in principal used
  to run two queries inside a writable transaction on every single request, to re-derive a row that
  had not changed since the request before. It now takes a read-only single-`select` fast path and
  only falls back to the full upsert when the token actually disagrees with the stored row. This is
  a saving on its own and the thing that makes the replica worthwhile, since the old cost fell on
  every request regardless of how little work it did.
- **An AlmaLinux installer** and a separate **SELinux hardening script**, both verified end to
  end on AlmaLinux 10.2 with SELinux enforcing.
- **Container quick start** with `podman compose up -d`.

### Performance

- **17,066 messages/second** persisted and delivered on a single node, p50 21.6 ms end to end,
  nothing dropped across 427,251 messages.
- **100,000 concurrent WebSocket connections** on one box — all 100,000 established, 2,000,000
  deliveries, none dropped — in 11.2 GiB. Attempts above that were limited by the co-located
  load generator's memory rather than by the server, so treat 100,000 as a measured floor and
  not a ceiling.
- **136,043 deliveries/second** fan-out.
- Method, raw results and the things that turned out not to matter are in `scalability.md`.

[1.0.0]: https://github.com/IntelliStream-DataHub/intellistream-chat/releases/tag/v1.0.0
