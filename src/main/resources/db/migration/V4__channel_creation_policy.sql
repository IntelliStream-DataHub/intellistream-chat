-- Who may create channels.
--
-- Slack and Mattermost both converge on the same shape: a permission that defaults permissive
-- and that an administrator can tighten, rather than a role you must be granted. Slack exposes it
-- under Settings, Permissions, Channel Management; Mattermost as create_public_channel and
-- create_private_channel in its permission scheme, granted to All Members by default.
--
-- This is a product decision, not an identity one, so it lives here rather than as a Keycloak
-- role: Keycloak owns who you are, the application owns what you may do inside it. It also means
-- an admin can tighten it during an incident without touching the identity provider, and no
-- existing user needs a role grant for the permissive default to keep working.
--
-- EVERYONE (default, preserves existing behaviour) or ADMINS_ONLY.
alter table app_settings
    add column channel_creation varchar(16) not null default 'EVERYONE';

alter table app_settings
    add constraint ck_app_settings_channel_creation
    check (channel_creation in ('EVERYONE', 'ADMINS_ONLY'));
