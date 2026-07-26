/*
 * Copyright 2026 IntelliStream AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.intellistream.chat.slash;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Reminder;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ReminderRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /remind me|@username in 5m|at 14:00 to <text>}
 *
 * <p>The reminder is persisted with its scheduled instant; {@link ReminderScheduler} picks it up
 * when due and delivers it as a direct message.
 *
 * <p>Nothing about a reminder goes to the channel. It used to: the confirmation was posted as an
 * ordinary channel message, so {@code /remind me in 2h to ask about my salary review} announced
 * itself to the room, and the reminder announced it again two hours later. Slack keeps both sides
 * private and so do we — the confirmation is a notice on {@code /user/queue/notices} (the user is
 * online by definition, nothing to persist), and the reminder itself is a DM, which is what buys
 * it survival across a restart, an unread badge, a permalink and search.
 *
 * <p>Times are resolved in the <em>user's</em> zone ({@link User#effectiveZone}), not the JVM's.
 * "at 14:00" meaning 14:00 on the server was wrong for everyone who does not sit next to it, and
 * wrong invisibly — which is why the confirmation now names the zone it used.
 */
@Component
public class RemindCommand implements SlashCommand {

    /** "in 5m", "in 30s", "in 2h", "in 1d". Anchored at start, case-insensitive. */
    private static final Pattern DURATION = Pattern.compile(
            "^in\\s+(\\d+)\\s*(s|sec|seconds?|m|min|minutes?|h|hr|hours?|d|days?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** "at 14:00", "at 9:30", "at 9pm", "at 2pm". 24h or 12h+am/pm. */
    private static final Pattern AT_TIME = Pattern.compile(
            "^at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TARGET = Pattern.compile(
            "^(me|@[A-Za-z0-9_.-]{1,100})\\b");

    private final ChannelService channelService;
    private final UserService userService;
    private final ReminderRepository reminderRepo;
    private final Clock clock;

    /** {@code ichat.default-zone}: the last resort when neither the user nor their IdP says. */
    private final ZoneId defaultZone;

    @Autowired
    public RemindCommand(ChannelService channelService,
                         UserService userService,
                         ReminderRepository reminderRepo,
                         @Value("${ichat.default-zone:}") String defaultZone) {
        this(channelService, userService, reminderRepo, Clock.systemUTC(),
                User.zoneOrSystemDefault(defaultZone));
    }

    /**
     * Test-visible constructor: lets specs freeze the clock to assert {@code fireAt} exactly, and
     * pin the fallback zone so "at HH:MM" can be asserted somewhere other than wherever the build
     * happens to be running.
     */
    public RemindCommand(ChannelService channelService,
                         UserService userService,
                         ReminderRepository reminderRepo,
                         Clock clock,
                         ZoneId defaultZone) {
        this.channelService = channelService;
        this.userService = userService;
        this.reminderRepo = reminderRepo;
        this.clock = clock;
        this.defaultZone = defaultZone;
    }

    @Override public String name() { return "remind"; }
    @Override public String help() {
        return "/remind me|@username in 5m|at 14:00 to <message>";
    }

    @Override
    @Transactional
    public SlashCommandResult execute(Channel channel, User author, String args) {
        // Queueing a reminder against a channel is a write, and it is the only one here now that
        // nothing is posted: the old code got this check for free from messageService.post, whose
        // AccessDenied rolled back the just-saved row. Explicit, and before the insert.
        channelService.requireWriteAccess(channel, author);
        var parsed = parse(args, author);
        var reminder = new Reminder(channel, author, parsed.target, parsed.fireAt, parsed.body);
        reminderRepo.save(reminder);
        // Private. Naming the zone is the point of naming it: a wrong guess is otherwise only
        // discoverable by the reminder arriving at the wrong hour, hours later.
        var zone = zoneFor(author);
        var when = describeWhen(parsed.fireAt, zone, clock.instant());
        var delivery = parsed.target == null
                ? "I'll send it to you as a direct message"
                : "@" + parsed.target.getUsername() + " gets it as a direct message from you";
        return SlashCommandResult.privately("⏰ Reminder set for " + when + " (" + zone.getId()
                + "). " + delivery + ": “" + parsed.body + "”");
    }

    /** The zone this user's wall-clock times mean: their choice, their IdP's, then the default. */
    private ZoneId zoneFor(User user) {
        return user == null ? defaultZone : user.effectiveZone(defaultZone);
    }

    /** Visible for tests. */
    public Parsed parse(String args, User caller) {
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("Usage: " + help());
        }
        var input = args.trim();
        // Target — optional, defaulting to the caller, which is by far the common case.
        User target = caller;
        boolean targetSelf = true;
        var m = TARGET.matcher(input);
        if (m.find()) {
            var raw = m.group(1);
            input = input.substring(m.end()).stripLeading();
            if (!raw.equalsIgnoreCase("me")) {
                var username = raw.substring(1); // drop @
                target = userService.requireByUsername(username);
                targetSelf = target.getId().equals(caller.getId());
            }
        }
        // When — required.
        Instant fireAt;
        var dm = DURATION.matcher(input);
        var am = AT_TIME.matcher(input);
        if (dm.find()) {
            long amount;
            try {
                amount = Long.parseLong(dm.group(1));
            } catch (NumberFormatException overflow) {
                throw new IllegalArgumentException("That's too far in the future. " + help());
            }
            if (amount < 1) {
                throw new IllegalArgumentException("Pick a positive time. " + help());
            }
            var unit = dm.group(2).toLowerCase();
            Duration d;
            try {
                d = toDuration(amount, unit);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("That's too far in the future. " + help());
            }
            // Clamp on the actual DURATION, not the raw amount — the old amount-based ceiling was
            // seconds-scaled, so "in 3000000000d" slipped through and queued a reminder ~8.6M years
            // out (N31). ~366 days covers every legitimate use.
            if (d.compareTo(Duration.ofDays(366)) > 0) {
                throw new IllegalArgumentException("Pick a time within about a year. " + help());
            }
            fireAt = clock.instant().plus(d);
            input = input.substring(dm.end()).stripLeading();
        } else if (am.find()) {
            // The caller's zone, not the JVM's: "at 14:00" is a wall-clock time, and whose wall
            // it is on is the whole question.
            fireAt = parseAt(am, clock.instant().atZone(zoneFor(caller)));
            input = input.substring(am.end()).stripLeading();
        } else {
            throw new IllegalArgumentException(
                    "Couldn't parse a time. Use \"in 5m\" / \"in 1h\" / \"at 14:00\". " + help());
        }
        // Optional connector "to" or "that"
        if (input.regionMatches(true, 0, "to ", 0, 3)) input = input.substring(3).stripLeading();
        else if (input.regionMatches(true, 0, "that ", 0, 5)) input = input.substring(5).stripLeading();
        if (input.isEmpty()) throw new IllegalArgumentException("Reminder text is required. " + help());
        // The row stores what the user actually wrote, nothing more. It used to be stored with an
        // "@username — " prefix so that posting it into the channel would trip the mention pipeline;
        // delivery is a DM now, which notifies on its own, and attribution ("Reminder from @alice")
        // is presentation that ReminderScheduler adds at fire time.
        //
        // Target null means "me" — saves a join later, and says plainly that there is no third party.
        return new Parsed(targetSelf ? null : target, fireAt, input);
    }

    private static Duration toDuration(long amount, String unit) {
        return switch (unit) {
            case "s", "sec", "second", "seconds" -> Duration.ofSeconds(amount);
            case "m", "min", "minute", "minutes" -> Duration.ofMinutes(amount);
            case "h", "hr", "hour", "hours" -> Duration.ofHours(amount);
            case "d", "day", "days" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
    }

    /**
     * Resolve "at HH:MM (am|pm)" to the next occurrence at-or-after {@code now}. If today's
     * matching time has already passed we roll forward a day so a "/remind at 9am" issued at
     * 10am queues for tomorrow morning, not the past.
     */
    private static Instant parseAt(Matcher m, ZonedDateTime now) {
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        var ampm = m.group(3);
        if (ampm != null) {
            if (hour < 1 || hour > 12) throw new IllegalArgumentException("Invalid hour for am/pm: " + hour);
            if (ampm.equalsIgnoreCase("pm") && hour != 12) hour += 12;
            else if (ampm.equalsIgnoreCase("am") && hour == 12) hour = 0;
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Invalid time");
        }
        var target = now.with(LocalTime.of(hour, minute));
        if (!target.isAfter(now)) target = target.plusDays(1);
        return target.toInstant();
    }

    /**
     * "today at 14:00" / "tomorrow at 09:00" / "2026-08-01 at 09:00", in {@code zone}.
     *
     * <p>{@code now} is passed in rather than read from the system clock so that "today" is the
     * same day the rest of the parse used. Reading {@code LocalDate.now(zone)} here meant a frozen
     * test clock and this method disagreeing, and — for one second a day — production too.
     */
    static String describeWhen(Instant when, ZoneId zone, Instant now) {
        var z = when.atZone(zone);
        var today = now.atZone(zone).toLocalDate();
        var on = z.toLocalDate().equals(today) ? "today"
                : z.toLocalDate().equals(today.plusDays(1)) ? "tomorrow"
                : z.toLocalDate().toString();
        return on + " at " + z.toLocalTime().withSecond(0).withNano(0);
    }

    /** Visible for tests. */
    public record Parsed(User target, Instant fireAt, String body) {}
}
