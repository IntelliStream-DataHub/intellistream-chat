# Changelog

Notable changes to IntelliStream Chat. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [semantic versioning](https://semver.org/spec/v2.0.0.html).

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
