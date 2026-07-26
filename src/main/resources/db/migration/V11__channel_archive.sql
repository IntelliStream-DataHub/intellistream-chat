-- Archiving a channel: the Slack / Mattermost answer to a channel list that only ever grows.
--
-- A workspace accumulates channels the way a filesystem accumulates directories, and the ones that
-- are finished are indistinguishable from the ones that are quiet. Deleting them is not the answer —
-- the history is usually the only record of why something was decided. Archiving freezes the channel
-- as a record: read-only, out of the sidebar and out of channel discovery, still readable, and still
-- searchable, which is the whole point of it existing rather than being destroyed.
--
-- Three columns, mirroring the tombstone idiom V6 established on attachments — same names, same
-- nullability, same meaning, so this schema has one way of saying "this was retired by someone at
-- some point" rather than two.
--
--   archived_at           NULL means live. One nullable timestamp rather than a boolean plus a
--                         separate date: a boolean would need the timestamp beside it anyway (the
--                         banner says when), and two columns that must agree is one more pair that
--                         can disagree.
--   archived_by           the accountable reference. Keeps the record joinable, and dies with the
--                         account if it is ever purged.
--   archived_by_username  what the banner actually renders. Both, for exactly V6's reason: the FK
--                         association is LAZY and the channel header is rendered by HomeController
--                         with open-in-view off, so reading archived_by.displayName in the template
--                         throws LazyInitializationException. The copied name also has the better
--                         semantics — the banner records who archived it at the time, and a later
--                         display-name change should not rewrite that.
--
-- Reversible on purpose. Both products let an admin unarchive, and a one-way door here would be a
-- worse trap than the growing channel list it exists to fix: archive is the action people are
-- pushed towards *instead of* deleting, so it has to be the safe one.
alter table channels add column archived_at          timestamptz;
alter table channels add column archived_by          bigint references users(id);
alter table channels add column archived_by_username varchar(120);

-- No index, deliberately. Every query that filters on archived_at is either bounded to one user's
-- memberships (the sidebar, which already loads all of them in one query) or scans a table that
-- holds one row per channel in the workspace — hundreds, not millions. A partial index here would be
-- write amplification on a column touched by hand, which is the same reasoning V9 recorded for
-- channel_members.favourite.
