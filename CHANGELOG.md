# Changelog

Notable changes to IntelliStream Chat. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

A pass over the places where this app behaved differently from Slack and Mattermost in ways that
would mislead someone arriving with those habits. Most of these were not missing features but
promises the product made and then broke — a command that looked like it worked, a state that said
"muted" and wasn't, a search token that meant the opposite of what it means everywhere else.

### Fixed

- **A `/word` that names no command is refused privately instead of being posted.** `/leave`,
  `/dnd`, `/me` and a dozen other commands that exist elsewhere used to be broadcast verbatim to
  the room. The sender now gets a private notice, the text goes back in their composer, and the
  optimistic bubble is taken down rather than left to relabel itself "not delivered".
- **`/remind` is private.** Both the confirmation and the reminder itself used to post into the
  channel, so a personal note was announced to everyone. Reminders now arrive as a direct message,
  and times resolve in the user's own timezone rather than the server's.
- **Do Not Disturb silences notifications.** It set a red dot and claimed "notifications muted"
  while every toast, chime and OS notification still fired. Unread counts and the mention inbox
  deliberately keep working.
- **Message permalinks survive the login round-trip.** A shared link used to drop a signed-out
  colleague on the welcome page.
- **`@bob` in search finds where Bob was mentioned**, as it does in Slack; `from:@bob` finds what
  he wrote. The two were previously the same token with the opposite meaning, silently.
- **Search reaches every channel you are allowed to read**, not only the ones you joined, and
  matches attachment filenames.
- **Thread replies count as unread** and notify the people in the thread. A reply without an
  @mention previously produced no signal anywhere, so threaded conversations died quietly.
- **You can react to your own message.** The rule refusing it cited Slack and Mattermost, neither
  of which has ever worked that way.
- **A deleted file's bytes are credited back exactly once.** Deleting a file and then its message
  refunded the quota twice, unrepairably — `UserStorage` exposes only an atomic delta.
- **`MultiFieldQueryParser` no longer silently ORs** multi-term input across fields with different
  analyzers, which quietly widened any query containing a hyphenated word.

### Added

- **Leave a channel** — and an ex-member's open socket stops receiving it, since the broker
  authorises SUBSCRIBE once and never re-checks.
- **Rename, re-describe, archive, unarchive and delete channels.** Archive is reversible and
  channel-admin level; delete is workspace-admin only.
- **`@`-mention typeahead**, matching display names as well as handles — mentions previously
  required an exact username while the UI showed display names, and failed silently otherwise.
- **`@channel`, `@here` and `@everyone`.**
- **Pin, save, forward and quote-reply.** Forwarding out of a private channel requires an explicit
  acknowledgement; forwarding out of a DM is deliberately not offered.
- **Direct messages reach parity with channels**: threads, typing indicators, read state, a
  per-conversation notification level, and leaving a group.
- **A search results page** with counts, paging and scope, plus `in:#channel`.
- **Browse the files shared in a channel.**
- **Favourite channels**, pinned to the top of the sidebar.
- **A per-user timezone**, from the OIDC `zoneinfo` claim or the profile page.

### Changed

- **The sidebar lists every channel you are in**, alphabetically, instead of a ranked shortlist of
  the five largest and five most active with the rest hidden behind a search box. Live
  notifications now follow joined channels by construction rather than following whatever the
  sidebar happened to render — a mention in an unlisted channel previously produced no toast, no
  chime, no badge and no bell until the next page load.
- **Unread reads like Slack's**: a bold channel name for ordinary unread, a number only for
  mentions and DMs. Muted channels are dimmed and still count.
- **The sidebar star means favourite**, not "you are an admin of this channel".
- **One message-search box**, scoped to the channel you are reading, instead of three boxes with
  three different meanings.

## [1.0.0] — 2026-07-26

First public release. Everything below is new, so this entry is a description of what the
release contains rather than a diff against anything.

### Chat

- **Channels**, public or private, with a curated sidebar — your largest and most active
  channels rather than every channel that exists — and server-side search for the rest.
- **Threads** in a side panel, so replies stay together without burying the channel.
- **Direct and group messages.** One recipient starts a DM, more than one creates a group.
- **Markdown** rendered server-side and sanitised with jsoup, with a formatting toolbar and a
  live preview in the composer.
- **Emoji reactions**, with a picker over roughly 650 emoji.
- **@mentions** with a mention inbox, per-channel unread and mention badges, per-user read
  state, typing indicators and message permalinks.
- **Presence and custom status**, workspace-wide.
- **Polls**, built in a dialog or typed as a `/poll` slash command. Editing a poll reopens the
  dialog; once anyone has voted the options are frozen, because a vote is a statement about a
  specific set of choices.
- **Slash commands**, including `/poll` and `/remind`.
- **Twenty themes**, five of them dark. Every accent and highlight in the palette clears the
  WCAG AA 4.5:1 contrast threshold against the text it carries.

### Files

- **Streamed uploads.** The request body *is* the file — no multipart boundary scan — with the
  filename and caption in percent-encoded headers. Bytes go straight to disk and are never
  fully buffered in memory.
- **Inline image previews** with an in-page lightbox.
- **A per-user file manager** at `/files`: browse and search everything you have uploaded, see
  where it was posted and what it costs you, and delete it. Deleting a file leaves its message
  standing and records in place that the file was removed, when, and by whom — your caption and
  anyone's replies survive.
- **Per-account storage quotas**, credited back in the same transaction as the delete.

### Search

- **Embedded Apache Lucene**, on disk, no separate search cluster.
- Covers **channels, direct messages and group conversations** in one ranked list.
- The access rule is **part of the Lucene query**, not a filter over its results, so content you
  cannot read is never scored, counted, ranked or highlighted. A workspace administrator cannot
  search other people's conversations at any privilege level.

### Notifications

- **Per-channel notification levels** on the Slack/Mattermost model: an account-wide default and
  a per-channel override whose default value is *inherit*, not a copy — change the account
  setting and every channel you have not explicitly overridden moves with it. Muting is the
  bottom of that same control.
- **Notification sounds**, set separately for mentions and direct messages, chosen from fifteen
  synthesised in the browser — no audio files to ship, serve or license.
- In-tab toasts, plus OS notifications where the browser permits them.

### Administration

- **OIDC single sign-on via Keycloak**, with a branded login theme included.
- **An admin console**: suspend an account and close its live sessions, clear or restore a
  user's messages, set per-person storage quotas, and choose who may create channels.
- **An append-only audit trail** of administrative actions.
- **Message retention** with a scheduled purge, and background cleanup of orphaned files.

### Operating it

- **One JVM process, one Postgres database, one systemd unit.** No message broker, no search
  cluster, no cache tier.
- Runs a workspace of a thousand people in **under a gigabyte of memory on a single core**.
- **Flyway migrations**, **health endpoints**, and configuration through environment variables
  with an optional **Vault/OpenBao** backend for secrets.
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
