-- A second account-wide notification default, for direct and group conversations.
--
-- Channels and conversations need different defaults, and one column cannot hold both. The shipped
-- account default is MENTIONS, which is right for channels: most traffic in a room you joined is
-- other people's business, and you want to be told when it becomes yours. Applied to a direct
-- message it says the opposite of what anybody means — a message sent to you and nobody else is
-- addressed to you whether or not it spells your name, and a 1:1 where the other person has to
-- type "@you" to reach you is a broken product.
--
-- Until now the conversation path resolved that by ignoring MENTIONS entirely: only NONE silenced a
-- conversation, so ALL and MENTIONS both delivered. That kept 1:1s working and cost the setting
-- that a twenty-person group DM actually wants — "only tell me when someone says my name" — which
-- had no way to be expressed.
--
-- Two columns is how Slack does it, and it is the only shape where both readings are honest:
-- channels default to MENTIONS, conversations default to ALL, and the per-conversation override
-- then means exactly what it says, including MENTIONS.
--
-- ALL rather than MENTIONS as the seeded value is deliberate and is the whole point of the split:
-- it preserves today's behaviour for every existing account. Nobody's direct messages go quiet
-- because a migration ran.
alter table users
    add column notify_dm_default varchar(16) not null default 'ALL';

-- Same shape as users_notify_default_chk: an account default is a real level, never DEFAULT —
-- there is nothing above it to inherit from.
alter table users
    add constraint users_notify_dm_default_chk
        check (notify_dm_default in ('ALL', 'MENTIONS', 'NONE'));
