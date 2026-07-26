-- Direct messages stop being a thinner surface than channels.
--
-- One migration for the whole DM-parity change rather than one per feature, because the columns
-- below are a single decision expressed in two places: a conversation is a room, and a room has
-- threads and a notification setting. Splitting them across V12/V13 would produce two migrations
-- that must be applied together to leave the schema in a state the application recognises.

-- ---------------------------------------------------------------------------
-- Threads
-- ---------------------------------------------------------------------------

-- Self-referencing parent, exactly as `messages.parent_id` does for channels. NULL means the
-- message is top-level and belongs in the conversation feed; non-NULL means it is a reply and
-- belongs in its parent's thread panel and nowhere else.
--
-- ON DELETE CASCADE: deleting the message that starts a thread takes the thread with it. The
-- alternative — orphaning the replies — would leave rows that no view can reach and that the
-- Lucene reconcile would keep faithfully indexing.
alter table conversation_messages
    add column parent_id bigint references conversation_messages(id) on delete cascade;

-- Reading one thread: every reply of a parent, oldest first.
create index ix_conv_messages_parent on conversation_messages(parent_id);

-- Reading the feed: top-level messages of a conversation, newest first. A partial index, because
-- the feed query is `parent_id is null` and the existing ix_conv_messages_created would have to
-- scan replies to discard them. In a busy thread the replies outnumber the parents.
create index ix_conv_messages_toplevel
    on conversation_messages(conversation_id, created_at)
    where parent_id is null;

-- A reply's parent must live in the same conversation, and a reply may not itself be replied to
-- (one level, like channels). Neither is expressible as a column constraint — the first needs a
-- second row, the second needs recursion — so both are enforced in ConversationService.replyInThread
-- and asserted in ConversationThreadIT. This comment is here so the next person looks there rather
-- than assuming the schema said it.

-- ---------------------------------------------------------------------------
-- Per-conversation notification level
-- ---------------------------------------------------------------------------

-- The same control channel_members got in V7, on the same terms, and deliberately not a new one:
-- DEFAULT is a real stored value meaning "follow the account default", so changing the account
-- default moves every conversation the user has not explicitly overridden. Storing a snapshot of
-- what the default resolved to at join time would read identically on day one and then silently
-- stop tracking — see the long note in V7 for why that distinction is the whole design.
--
-- NOT NULL with a column default rather than nullable, for V7's second reason as well:
-- ConversationMemberRepository.insertMemberIgnore is a native INSERT naming only
-- (conversation_id, user_id), and the column default is what fills this in for the
-- start-a-DM and add-to-group paths, which never go through JPA.
alter table conversation_members
    add column notify_level varchar(16) not null default 'DEFAULT';

alter table conversation_members
    add constraint conversation_members_notify_level_chk
        check (notify_level in ('DEFAULT', 'ALL', 'MENTIONS', 'NONE'));
