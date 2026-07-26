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
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /remind me|@username in 5m|at 14:00 to <text>}
 *
 * <p>The reminder is persisted with its scheduled instant; {@link ReminderScheduler} picks
 * it up when due and posts a message into the same channel, optionally prefixed with the
 * target's @-mention so they get the standard mention notification.
 *
 * <p>Posting a confirmation message back to the channel ("Reminder set for …") would feel
 * chatty in a busy room — instead we return a private confirmation as the executing user's
 * own message so they can see it in the timeline.
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

    private final MessageService messageService;
    private final UserService userService;
    private final ReminderRepository reminderRepo;
    private final Clock clock;

    @Autowired
    public RemindCommand(MessageService messageService,
                         UserService userService,
                         ReminderRepository reminderRepo) {
        this(messageService, userService, reminderRepo, Clock.systemDefaultZone());
    }

    /** Test-visible constructor: lets specs freeze the clock to assert {@code fireAt} exactly. */
    public RemindCommand(MessageService messageService,
                         UserService userService,
                         ReminderRepository reminderRepo,
                         Clock clock) {
        this.messageService = messageService;
        this.userService = userService;
        this.reminderRepo = reminderRepo;
        this.clock = clock;
    }

    @Override public String name() { return "remind"; }
    @Override public String help() {
        return "/remind me|@username in 5m|at 14:00 to <message>";
    }

    @Override
    @Transactional
    public SlashCommandResult execute(Channel channel, User author, String args) {
        var parsed = parse(args, author);
        // Reuse the parent's @-mention infrastructure: when there's a target user, the message
        // body the scheduler posts later will start with "@username — …" so the standard mention
        // pipeline notifies them.
        var reminder = new Reminder(channel, author, parsed.target, parsed.fireAt, parsed.body);
        reminderRepo.save(reminder);
        // Confirm back into the channel as the requester so they see the queue actually took it.
        // The post() call enforces requireWriteAccess — under @Transactional, an AccessDenied
        // throw rolls back the just-saved Reminder so non-members can't queue work via /remind.
        // No live "@username" in the confirmation — that would fire a mention notification NOW,
        // and the reminder fires a second one when it's actually due (N30). Name the target plainly.
        var confirmation = "⏰ Reminder set for " + describeWhen(parsed.fireAt, clock.getZone())
                + (parsed.target == null ? "" : " (will tag " + parsed.target.getUsername() + ")")
                + ": _" + parsed.body + "_";
        return SlashCommandResult.handled(messageService.post(channel, author, confirmation));
    }

    /** Visible for tests. */
    public Parsed parse(String args, User caller) {
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("Usage: " + help());
        }
        var input = args.trim();
        // Target — optional. Default is "me" (no @-mention prefix when fired).
        User target = caller; // default to self
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
            fireAt = parseAt(am, clock.instant().atZone(clock.getZone()));
            input = input.substring(am.end()).stripLeading();
        } else {
            throw new IllegalArgumentException(
                    "Couldn't parse a time. Use \"in 5m\" / \"in 1h\" / \"at 14:00\". " + help());
        }
        // Optional connector "to" or "that"
        if (input.regionMatches(true, 0, "to ", 0, 3)) input = input.substring(3).stripLeading();
        else if (input.regionMatches(true, 0, "that ", 0, 5)) input = input.substring(5).stripLeading();
        if (input.isEmpty()) throw new IllegalArgumentException("Reminder text is required. " + help());
        // For non-self targets we want the produced message to mention them so they get notified.
        var body = targetSelf ? input : "@" + target.getUsername() + " — " + input;
        // Treat the persisted target as null when it's "me" — saves a join later and avoids a
        // self-mention on fire.
        return new Parsed(targetSelf ? null : target, fireAt, body);
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

    private static String describeWhen(Instant when, ZoneId zone) {
        var z = when.atZone(zone);
        var today = LocalDate.now(zone);
        var on = z.toLocalDate().equals(today) ? "today"
                : z.toLocalDate().equals(today.plusDays(1)) ? "tomorrow"
                : z.toLocalDate().toString();
        return on + " at " + z.toLocalTime().withSecond(0).withNano(0);
    }

    /** Visible for tests. */
    public record Parsed(User target, Instant fireAt, String body) {}
}
