-- Slack/Mattermost-style manual presence override. The existing user_presence row
-- carried only a custom-status emoji + text (which still drives the avatar emoji
-- badge). This adds a separate column for the user-chosen "I am AWAY / DND /
-- OFFLINE" override that takes precedence over the auto-derived connection state.
--
-- Values map 1:1 to the PresenceKind Java enum. Stored as a CHECK-constrained text
-- column instead of a Postgres enum so a future kind addition is just an ALTER
-- TABLE ... DROP CONSTRAINT / ADD CONSTRAINT round-trip rather than a CREATE TYPE
-- alter dance.
--
-- NULL means "no manual override; use auto state". The auto state is binary
-- (connected via STOMP → ACTIVE, otherwise OFFLINE), kept in PresenceTracker
-- in-memory so we don't need a DB column for it.

alter table user_presence
    add column manual_status_kind varchar(16);

alter table user_presence
    add constraint user_presence_manual_kind_chk
        check (manual_status_kind is null or manual_status_kind in ('AWAY', 'DND', 'OFFLINE'));
