-- Per-channel notification levels, modelled on Slack and Mattermost.
--
-- One control, three levels — ALL / MENTIONS / NONE — expressed twice: once as the account-wide
-- default, and once per channel membership as an override. "Mute" is not a separate flag; it is
-- NONE, the bottom of the same control. A boolean alongside a level would be a second source of
-- truth for the same question ("does this channel notify me?") and the two inevitably disagree —
-- muting a channel set to ALL, then unmuting it, has to remember what ALL was, which is exactly
-- the state the level column already holds.

-- The account-wide default. Ships as MENTIONS because that is what the app does today for
-- everybody: existing rows take the column default and their behaviour is unchanged.
alter table users
    add column notify_default varchar(16) not null default 'MENTIONS';

-- No DEFAULT here on purpose. The account default is the bottom of the inheritance chain; there
-- is nothing above it to inherit from, so the value must be concrete. The check constraint is the
-- schema-level statement of that, mirroring NotificationLevel's guard in the domain.
alter table users
    add constraint users_notify_default_chk
        check (notify_default in ('ALL', 'MENTIONS', 'NONE'));

-- The per-channel override. DEFAULT is a real, stored value meaning "follow the account default",
-- and it is the column default, so every membership that predates this migration — and every one
-- created afterwards that nobody has touched — inherits.
--
-- This is the whole design, and it is worth being explicit about the alternative that looks
-- equivalent and is not: copying the account default into the membership at join time. That
-- version stores MENTIONS rather than DEFAULT, which reads identically on day one and then
-- silently stops tracking. Change the account default to ALL and nothing moves, because every
-- channel is now carrying its own frozen copy of the old value, indistinguishable from a
-- deliberate per-channel choice. Storing the *inheritance* rather than a snapshot of what it
-- currently resolves to is what makes "change my default" actually change anything.
--
-- NOT NULL with a column default rather than a nullable column, for two reasons. There is then
-- exactly one representation of "inherit" instead of two (NULL and 'DEFAULT') that every read
-- would have to normalise. And ChannelMemberRepository.insertMemberIgnore is a native INSERT that
-- names only (channel_id, user_id, role) — the column default is what fills this in for the
-- join/invite path, which does not go through JPA at all.
alter table channel_members
    add column notify_level varchar(16) not null default 'DEFAULT';

alter table channel_members
    add constraint channel_members_notify_level_chk
        check (notify_level in ('DEFAULT', 'ALL', 'MENTIONS', 'NONE'));
