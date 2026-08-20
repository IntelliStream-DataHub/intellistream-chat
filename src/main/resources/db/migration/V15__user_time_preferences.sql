-- Per-user display preferences for timestamps: which zone a message time is rendered in, and in
-- whose conventions.
--
-- V8 added zone_id / oidc_zone_id, but only for *input*: "/remind me at 14:00" needed to know what
-- 14:00 meant. Display was left alone on the belief — written down in profile.html — that
-- timestamps "already follow the device you are reading on". Half of them did. The message feed is
-- rendered twice by two different formatters: server-side by Thymeleaf for the history you land on,
-- and client-side by JS for everything that arrives after. The client half used the browser's zone
-- and locale; the server half used the *server's* zone and a hard-coded 'h:mm a' pattern. On a
-- UTC container that is every historical message in UTC and in US 12-hour form, directly above live
-- messages in local 24-hour form, in the same list.
--
-- Three columns and a flag:
--
--   detected_zone_id       what the browser's Intl.DateTimeFormat().resolvedOptions().timeZone
--                          reported, posted back by time-format.js. This is the only signal that
--                          knows where the reader physically is right now, so it outranks the IdP
--                          claim (which is an account attribute set once and rarely revisited) and
--                          is outranked only by an explicit choice. Kept apart from zone_id for the
--                          same reason oidc_zone_id is: a detection must never silently overwrite
--                          a deliberate pick, and a deliberate pick must survive a login from
--                          somebody else's laptop in another country.
--   hour_cycle             AUTO / H12 / H24. AUTO means "whatever this viewer's locale does",
--                          which is the CLDR short-time pattern for the Accept-Language locale —
--                          HH:mm for nb-NO, h:mm a for en-US. The explicit values exist because
--                          locale is a bad proxy for this one preference: plenty of people run an
--                          en-US browser and still want a 24-hour clock, and today they have no way
--                          to say so.
--   date_style             AUTO / DMY / MDY / ISO, same idea for dates.
--   zone_prompt_dismissed  the "we could not work out your time zone" banner is a one-time nudge,
--                          not a permanent fixture. Set when the user dismisses it or picks a zone.
--
-- No backfill, for the reason V8 gives: NULL is "we have not been told", which is a different and
-- more useful state than a guess written into every row. AUTO is likewise the shipping state and
-- reproduces locale-derived formatting, so nobody's display changes because of a column default —
-- it changes because the renderer stopped using the server's zone.
alter table users
    add column detected_zone_id varchar(64);

alter table users
    add column hour_cycle varchar(16) not null default 'AUTO';

alter table users
    add column date_style varchar(16) not null default 'AUTO';

alter table users
    add column zone_prompt_dismissed boolean not null default false;

-- Enum values are written by name, so the constraint doubles as the list of legal names. A bad
-- value here is a rendering bug that shows up as an exception on a page load, which is late; the
-- check makes it a failed write at the point somebody introduced it.
alter table users
    add constraint ck_users_hour_cycle check (hour_cycle in ('AUTO', 'H12', 'H24'));

alter table users
    add constraint ck_users_date_style check (date_style in ('AUTO', 'DMY', 'MDY', 'ISO'));
