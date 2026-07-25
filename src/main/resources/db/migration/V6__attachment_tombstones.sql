-- Deleting a file from the file manager no longer deletes the message that posted it.
--
-- The previous behaviour took the message with the file, because an upload IS a message here
-- (attachments.message_id is NOT NULL). That destroyed the caption, and any replies to it, to
-- remove one file. It also forced a refusal on any message with replies, which meant the exact
-- files most worth removing — the ones people had responded to — were the ones you could not
-- remove.
--
-- The attachment is now tombstoned instead: the row survives with its filename, and records when
-- it was deleted and by whom, so the message can say so in place of the file. The bytes are
-- reaped and the quota credited exactly as before; what is kept is a few dozen bytes of metadata
-- per deleted file, which is what lets the message explain itself rather than showing a gap.
--
-- Mirrors the columns V3 added to messages, deliberately: same names, same nullability, same
-- meaning, so there is one soft-delete idiom in this schema rather than two.

-- deleted_by is the accountable reference; deleted_by_username is what the message actually
-- shows. Both, on purpose:
--   * the FK keeps the record joinable and honest, and dies with the account if it is ever purged
--   * the copied name means rendering a message never has to touch users at all. That association
--     is LAZY and message rendering runs with open-in-view off, so reading it in the DTO throws
--     LazyInitializationException; the alternatives are eager-loading or join-fetching a row that
--     is null for virtually every attachment ever loaded, on the hottest read path in the app.
-- A tombstone is history, so recording the name as it was at the time is also the correct
-- semantics — a later rename should not rewrite what this message says happened.
alter table attachments add column deleted_at timestamptz;
alter table attachments add column deleted_by bigint references users(id);
alter table attachments add column deleted_by_username varchar(120);

alter table conversation_attachments add column deleted_at timestamptz;
alter table conversation_attachments add column deleted_by bigint references users(id);
alter table conversation_attachments add column deleted_by_username varchar(120);

-- The file manager lists a user's live files, and every message render asks for its attachments.
-- Both want "not tombstoned", so the partial indexes carry that predicate rather than making the
-- planner filter it out after the fact.
create index ix_attachments_live on attachments(message_id) where deleted_at is null;
create index ix_conv_attachments_live on conversation_attachments(conversation_message_id) where deleted_at is null;
