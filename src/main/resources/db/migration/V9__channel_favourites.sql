-- Starred (favourite) channels, the Slack / Mattermost convention.
--
-- A column on channel_members, not a channel_favourites table, even though that is what this
-- migration is named after. A favourite is a fact about a membership: you star a channel you are
-- in, the star sits next to the notification override that is already stored per membership, and it
-- has exactly the same lifetime — leaving the channel ends both. A separate table with its own
-- (channel_id, user_id) pair would be a second place to say "this user relates to this channel this
-- way", which is one more thing that can disagree with channel_members and one more row to clean up
-- on leave. It would also permit starring a channel you are not a member of, which is a state with
-- no meaning here.
--
-- Losing the star when you leave and rejoin is correct rather than a limitation: the star said
-- "this is one of my main channels" about a membership that no longer exists, and silently
-- resurrecting it months later would put a channel at the top of someone's sidebar for a reason
-- they cannot see.
--
-- NOT NULL with a default rather than nullable, for the same reason notify_level is: one
-- representation of "not starred" instead of two (NULL and false) that every read must normalise,
-- and ChannelMemberRepository.insertMemberIgnore is a native INSERT naming only
-- (channel_id, user_id, role) — the column default is what fills this in on the join/invite path,
-- which does not go through JPA at all.
alter table channel_members
    add column favourite boolean not null default false;

-- No index. The sidebar render already loads every one of the viewer's memberships in a single
-- query and partitions them in memory, so there is no lookup here for an index to serve; a partial
-- index on (user_id) where favourite would only be write amplification on a column that is toggled
-- by hand.
