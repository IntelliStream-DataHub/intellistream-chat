-- Per-user timezone, so "/remind me at 14:00" means 14:00 where the user is.
--
-- Until now the only zone in the system was the server's: RemindCommand resolved "at HH:MM"
-- through Clock.systemDefaultZone(), so a reminder set by anyone not sitting in the same zone as
-- the JVM fired at the wrong hour, silently and with a confirmation message that agreed with
-- itself. Slack resolves it in the user's profile timezone; this is the column that lets us.
--
-- Two columns, not one, because "what zone is this user in" has two independent answers that must
-- not overwrite each other:
--
--   zone_id       what the user chose on the profile page. NULL means they have not chosen, i.e.
--                 "follow my account", and is the shipping state for every existing row.
--   oidc_zone_id  the last `zoneinfo` claim we saw from the identity provider. Refreshed on
--                 sign-in by CurrentUser, so it tracks a change made in Keycloak. Often NULL —
--                 zoneinfo is a standard OIDC claim but an optional one, and Keycloak does not
--                 populate it unless a mapper is configured.
--
-- Folding them into one column would mean either the IdP silently reverting a deliberate choice
-- on next login, or a guess going stale forever the moment it was written. The resolution order
-- (User.effectiveZone) is zone_id, then oidc_zone_id, then the `ichat.default-zone` property,
-- which itself defaults to the server zone — so a deployment that changes nothing keeps exactly
-- the behaviour it has today.
--
-- No backfill. There is nothing to backfill *from*: the old behaviour was not "everyone is in the
-- server's zone", it was "nobody had a zone and we used the server's", and writing the server's
-- zone into every row would turn that absence into a thousand explicit claims we cannot support.
-- Leaving them NULL keeps the fallback doing what it already did, and makes a real answer
-- distinguishable from the absence of one.
--
-- IANA region ids ("Europe/Oslo", "America/Argentina/ComodRivadavia" — the longest in tzdb at 32
-- characters). 64 leaves room for tzdb growth without inviting arbitrary strings; both columns are
-- validated against ZoneId.getAvailableZoneIds() before they are written.
alter table users
    add column zone_id varchar(64);

alter table users
    add column oidc_zone_id varchar(64);
