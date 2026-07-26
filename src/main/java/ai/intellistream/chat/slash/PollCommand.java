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
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.PollService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /poll Question? | Option A | Option B | Option C}
 *
 * <p>Posts a short host message containing the question, then attaches a {@link ai.intellistream.chat.domain.Poll}
 * row with the options. The frontend renders the host message with a click-to-vote widget
 * sourced from {@link ai.intellistream.chat.web.dto.PollDto} on the message DTO. Reactions on the
 * host message stay reactions — they're not used for vote tallying anymore.
 */
@Component
public class PollCommand implements SlashCommand {

    private final MessageService messageService;
    private final PollService pollService;

    public PollCommand(MessageService messageService, PollService pollService) {
        this.messageService = messageService;
        this.pollService = pollService;
    }

    @Override public String name() { return "poll"; }
    @Override public String help() { return "/poll Question? | Option A | Option B [| ...]"; }

    @Override
    @Transactional
    public SlashCommandResult execute(Channel channel, User author, String args) {
        var parts = parsePipeSeparated(args);
        if (parts.size() < 3) {
            throw new IllegalArgumentException(
                    "Usage: " + help() + " (need a question and at least 2 options)");
        }
        if (parts.size() - 1 > PollService.MAX_OPTIONS) {
            throw new IllegalArgumentException("Too many options (max " + PollService.MAX_OPTIONS + ")");
        }
        var question = parts.get(0);
        var options = parts.subList(1, parts.size());
        // Body is intentionally short — the rich poll widget is rendered by the client from
        // PollDto, but search, mention notifications, and the message snippet still benefit
        // from having the question text inline.
        var body = bodyFor(question);
        var saved = messageService.post(channel, author, body);
        pollService.create(saved, question, options);
        return SlashCommandResult.handled(saved);
    }

    /** The stored message body for a poll. Short on purpose — the widget carries the detail. */
    public static String bodyFor(String question) {
        return "📊 **Poll:** " + question;
    }

    /** A parsed {@code /poll} edit: the question and its option labels, in order. */
    public record ParsedPoll(String question, List<String> options) {}

    /**
     * Parse an edited poll command back into its parts, or null if this body is not one.
     *
     * <p>Editing a poll means editing the command that made it, because the command is the only
     * form in which the options are visible at all — the stored body is just the question line,
     * so an edit box showing that could change the wording and never the choices, which is the
     * shape of edit that looks like it worked and did not.
     *
     * <p>Returns null rather than throwing for a non-poll body: this is asked of every edit, and
     * "no, that is an ordinary message" is the common answer, not an error.
     */
    public static ParsedPoll parseEditedCommand(String body) {
        if (body == null) return null;
        var trimmed = body.strip();
        if (!trimmed.regionMatches(true, 0, "/poll", 0, 5)) return null;
        // "/pollute the well" is not a poll command; require a separator after the name.
        if (trimmed.length() > 5 && !Character.isWhitespace(trimmed.charAt(5))) return null;
        var parts = parsePipeSeparated(trimmed.substring(5).strip());
        if (parts.size() < 3) {
            throw new IllegalArgumentException(
                    "Usage: /poll Question? | Option A | Option B (need a question and at least 2 options)");
        }
        return new ParsedPoll(parts.get(0), List.copyOf(parts.subList(1, parts.size())));
    }

    /**
     * Split on bare {@code |} characters, preserving any escaped {@code \\|} as a literal pipe
     * inside an option label. Empty segments are dropped — running pipes ({@code | |}) and
     * leading/trailing pipes don't produce ghost options.
     */
    public static List<String> parsePipeSeparated(String args) {
        if (args == null || args.isBlank()) return List.of();
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\\' && i + 1 < args.length() && args.charAt(i + 1) == '|') {
                sb.append('|');
                i++;
                continue;
            }
            if (c == '|') {
                var part = sb.toString().trim();
                if (!part.isEmpty()) out.add(part);
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        var tail = sb.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }
}
