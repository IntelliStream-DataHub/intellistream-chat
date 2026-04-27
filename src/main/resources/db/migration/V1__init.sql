-- Radiance — initial schema (consolidated 2026-04-26 from V1..V21).
-- Squashed at v0.1.0 because the previous migrations were a working notebook,
-- not a public history. Future schema changes go in fresh V2+ files; never
-- edit this one in place once it has shipped.

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------

create table users (
    id                   uuid primary key default gen_random_uuid(),
    subject              varchar(255) not null,
    username             varchar(100) not null,
    email                varchar(255),
    display_name         varchar(255),
    theme                varchar(32)  not null default 'default',
    tutorial_dismissed   boolean      not null default false,
    avatar_storage_key   varchar(255),
    avatar_content_type  varchar(64),
    avatar_updated_at    timestamptz,
    -- Last time we saw any authenticated request from this user. Surfaced on the admin
    -- page; nullable so a never-seen user reads as NULL rather than a fake timestamp.
    last_active_at       timestamptz,
    -- Cached chat-admin flag, refreshed from the chat-admin Keycloak realm role on every
    -- login (UserService.provisionFromOidc / provisionFromJwt). Local cache so other-user
    -- lookups (hovercard, admin list) don't hit Keycloak per request.
    admin                boolean      not null default false,
    created_at           timestamptz  not null default now(),
    constraint uk_users_subject unique (subject)
);

-- Per-user custom status (emoji + short text + optional auto-clear).
-- Online/offline is in-memory (driven by STOMP session lifecycle), so it isn't stored here.
create table user_presence (
    user_id          uuid primary key references users(id) on delete cascade,
    status_emoji     varchar(16),
    status_text      varchar(120),
    status_clear_at  timestamptz,
    updated_at       timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Channels + membership
-- ---------------------------------------------------------------------------

create table channels (
    id          uuid primary key default gen_random_uuid(),
    slug        varchar(80)  not null,
    name        varchar(120) not null,
    description varchar(500),
    type        varchar(16)  not null,
    created_by  uuid not null references users(id),
    created_at  timestamptz  not null default now(),
    constraint uk_channels_slug unique (slug)
);

create table channel_members (
    id         uuid primary key default gen_random_uuid(),
    channel_id uuid not null references channels(id) on delete cascade,
    user_id    uuid not null references users(id) on delete cascade,
    role       varchar(16) not null,
    joined_at  timestamptz not null default now(),
    constraint uk_channel_member unique (channel_id, user_id)
);
create index ix_channel_members_user on channel_members(user_id);

-- Per-user read marker per channel — powers the unread badge.
create table channel_reads (
    id            uuid primary key default gen_random_uuid(),
    channel_id    uuid not null references channels(id) on delete cascade,
    user_id       uuid not null references users(id) on delete cascade,
    last_read_at  timestamptz not null default now(),
    constraint uk_channel_reads unique (channel_id, user_id)
);
create index ix_channel_reads_user on channel_reads(user_id);

-- ---------------------------------------------------------------------------
-- Channel messages, threads, attachments, reactions, mentions
-- ---------------------------------------------------------------------------

-- Note: an earlier revision carried a generated tsvector + GIN index on body_markdown.
-- Search has since moved to an embedded Lucene index at ./data/lucene, so the column is gone.
create table messages (
    id            uuid primary key default gen_random_uuid(),
    channel_id    uuid not null references channels(id) on delete cascade,
    author_id     uuid not null references users(id),
    body_markdown text not null,
    parent_id     uuid references messages(id) on delete cascade,
    pinned_at     timestamptz,
    pinned_by     uuid references users(id),
    created_at    timestamptz not null default now(),
    edited_at     timestamptz
);
create index ix_messages_channel_created on messages(channel_id, created_at);
create index ix_messages_parent          on messages(parent_id) where parent_id is not null;
create index ix_messages_channel_pinned  on messages(channel_id, pinned_at) where pinned_at is not null;

create table attachments (
    id           uuid primary key default gen_random_uuid(),
    message_id   uuid not null references messages(id) on delete cascade,
    filename     varchar(255) not null,
    content_type varchar(255) not null,
    size_bytes   bigint not null,
    storage_key  varchar(255) not null,
    created_at   timestamptz not null default now()
);
create index ix_attachments_message on attachments(message_id);

create table message_mentions (
    id          uuid primary key default gen_random_uuid(),
    message_id  uuid not null references messages(id) on delete cascade,
    user_id     uuid not null references users(id) on delete cascade,
    constraint uk_message_mentions unique (message_id, user_id)
);
create index ix_message_mentions_user on message_mentions(user_id);

create table message_reactions (
    id          uuid primary key default gen_random_uuid(),
    message_id  uuid not null references messages(id) on delete cascade,
    user_id     uuid not null references users(id) on delete cascade,
    emoji       varchar(64) not null,
    created_at  timestamptz not null default now(),
    constraint uk_reaction unique (message_id, user_id, emoji)
);
create index ix_reactions_message on message_reactions(message_id);

-- ---------------------------------------------------------------------------
-- Direct / group conversations
-- ---------------------------------------------------------------------------

-- Off-channel private conversations: DIRECT (1-to-1) and GROUP.
create table conversations (
    id         uuid primary key default gen_random_uuid(),
    type       varchar(16) not null,
    title      varchar(120),
    -- Sorted-userId composite key for DIRECT conversations so the same pair of users
    -- always reuses the same row. NULL for GROUP conversations.
    dm_key     varchar(80),
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    constraint uk_conversations_dm_key unique (dm_key)
);

create table conversation_members (
    id              uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references conversations(id) on delete cascade,
    user_id         uuid not null references users(id) on delete cascade,
    -- NULL last_read_at means "everything is read" — the next incoming message marks unread.
    last_read_at    timestamptz,
    joined_at       timestamptz not null default now(),
    constraint uk_conversation_member unique (conversation_id, user_id)
);
create index ix_conversation_members_user on conversation_members(user_id);

create table conversation_messages (
    id              uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references conversations(id) on delete cascade,
    author_id       uuid not null references users(id),
    body_markdown   text not null,
    created_at      timestamptz not null default now(),
    edited_at       timestamptz
);
create index ix_conv_messages_created on conversation_messages(conversation_id, created_at);

create table conversation_attachments (
    id                       uuid         primary key default gen_random_uuid(),
    conversation_message_id  uuid         not null references conversation_messages(id) on delete cascade,
    filename                 varchar(255) not null,
    content_type             varchar(120) not null,
    size_bytes               bigint       not null,
    storage_key              varchar(64)  not null unique,
    created_at               timestamptz  not null default now()
);
create index ix_conv_attach_message on conversation_attachments(conversation_message_id);

create table conversation_reactions (
    id                       uuid primary key default gen_random_uuid(),
    conversation_message_id  uuid not null references conversation_messages(id) on delete cascade,
    user_id                  uuid not null references users(id) on delete cascade,
    emoji                    varchar(64) not null,
    created_at               timestamptz not null default now(),
    constraint uk_conv_reaction unique (conversation_message_id, user_id, emoji)
);
create index ix_conv_reactions_message on conversation_reactions(conversation_message_id);

-- ---------------------------------------------------------------------------
-- Polls
-- ---------------------------------------------------------------------------

-- /poll creates one row in `polls` linked 1:1 to its host message; options live in
-- `poll_options`; cast votes in `poll_votes` (one per voter per poll, enforced at
-- the DB so concurrent double-clicks can't double-count). Deleting the host
-- message cascades the poll away.
create table polls (
    id          uuid primary key default gen_random_uuid(),
    message_id  uuid not null unique references messages(id) on delete cascade,
    question    varchar(500) not null,
    created_at  timestamptz not null default now()
);

create table poll_options (
    id        uuid primary key default gen_random_uuid(),
    poll_id   uuid not null references polls(id) on delete cascade,
    position  int not null,
    label     varchar(200) not null,
    constraint uk_poll_options_position unique (poll_id, position)
);
create index ix_poll_options_poll on poll_options(poll_id);

create table poll_votes (
    id         uuid primary key default gen_random_uuid(),
    poll_id    uuid not null references polls(id) on delete cascade,
    option_id  uuid not null references poll_options(id) on delete cascade,
    voter_id   uuid not null references users(id) on delete cascade,
    voted_at   timestamptz not null default now(),
    constraint uk_poll_votes_voter unique (poll_id, voter_id)
);
create index ix_poll_votes_poll   on poll_votes(poll_id);
create index ix_poll_votes_option on poll_votes(option_id);

-- ---------------------------------------------------------------------------
-- Reminders (/remind)
-- ---------------------------------------------------------------------------

-- Queued reminders posted by /remind. Stores channel + author + when + body, plus a
-- fired_at marker so the scheduler can mark each row done atomically without re-firing.
create table reminders (
    id           uuid primary key default gen_random_uuid(),
    channel_id   uuid not null references channels(id) on delete cascade,
    creator_id   uuid not null references users(id) on delete cascade,
    target_id    uuid references users(id) on delete set null,
    fire_at      timestamptz not null,
    body         text not null,
    fired_at     timestamptz,
    created_at   timestamptz not null default now()
);
create index ix_reminders_due on reminders (fire_at) where fired_at is null;

-- ---------------------------------------------------------------------------
-- Singleton settings (admin-editable branding + privacy toggles)
-- ---------------------------------------------------------------------------

create table app_settings (
    id                  smallint     primary key default 1 check (id = 1),
    title               varchar(120) not null default 'Radiance',
    logo_path           varchar(255),
    logo_content_type   varchar(64),
    logo_updated_at     timestamptz,
    -- Default TRUE preserves the long-standing behaviour (small workspaces routinely use
    -- the column for "find someone's email"); admins can flip it OFF for compliance-conscious
    -- deployments. See README "Admin email visibility" for context.
    expose_user_emails  boolean      not null default true,
    updated_at          timestamptz  not null default now()
);

insert into app_settings (id, title) values (1, 'Radiance');
