-- Enforce case-insensitive username uniqueness.
--
-- V1 only made `subject` unique; `username` was a plain column, but the app routes private
-- STOMP notices by username (convertAndSendToUser) and resolves mentions / presence / /remind
-- by username, so two distinct OIDC subjects that sanitize to the same handle (e.g. two people
-- whose emails both start "bob@…") could cross-deliver private messages. UserService now
-- disambiguates colliding usernames; this index makes the invariant enforced by the database.
create unique index uk_users_username_lower on users (lower(username));
