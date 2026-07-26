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
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes {@code /name args} bodies to the matching {@link SlashCommand} bean. A body that
 * doesn't start with {@code /} comes back as {@link SlashCommandResult#NOT_HANDLED} — the
 * message dispatcher then posts it normally.
 *
 * <p>A body that <em>does</em> look like a command but names none is
 * {@link SlashCommandResult#rejected}, not passed through. Passing it through is how "/help"
 * became a message everybody in the room could read: people arrive with muscle memory for
 * {@code /leave}, {@code /dnd}, {@code /me}, {@code /shrug} and a dozen more that don't exist
 * here, and every one of them used to be broadcast verbatim. Slack and Mattermost both answer
 * privately instead, and so do we — the text is not sent, and the sender alone is told why.
 *
 * <p>Which leaves the person who genuinely wants a line starting with a slash: see
 * {@link #ESCAPE_HINT}. Every rejection carries it, because a refusal without a way forward
 * just moves the surprise later.
 */
@Service
public class SlashCommandService {

    /**
     * How to write a line that starts with a slash and have it posted as text. All three work
     * because {@link #looksLikeCommand} looks at the very first character of the raw body:
     * a backslash escape ({@code \/leave}) renders as a bare slash through CommonMark, a code
     * span keeps it verbatim, and a single leading space is invisible in the rendered output.
     */
    public static final String ESCAPE_HINT =
            "To post a line that starts with a slash, escape it (\\/leave), wrap it in backticks, "
                    + "or type one space before it.";

    /** Longest unknown name echoed back; the rest is the sender's own text, not ours to repeat. */
    private static final int MAX_ECHOED_NAME = 32;

    private final Map<String, SlashCommand> byName;

    public SlashCommandService(List<SlashCommand> commands) {
        var map = new HashMap<String, SlashCommand>(commands.size());
        for (var c : commands) map.put(c.name().toLowerCase(), c);
        this.byName = Map.copyOf(map);
    }

    /** True when the body looks like {@code /word ...}; tells callers to consider dispatch. */
    public static boolean looksLikeCommand(String body) {
        if (body == null || body.length() < 2 || body.charAt(0) != '/') return false;
        var c = body.charAt(1);
        return Character.isLetter(c);
    }

    public SlashCommandResult dispatch(Channel channel, User author, String body) {
        if (!looksLikeCommand(body)) return SlashCommandResult.NOT_HANDLED;
        var trimmed = body.stripLeading();
        var space = trimmed.indexOf(' ');
        var name = (space < 0 ? trimmed.substring(1) : trimmed.substring(1, space)).toLowerCase();
        var args = space < 0 ? "" : trimmed.substring(space + 1).trim();
        var cmd = byName.get(name);
        if (cmd == null) return SlashCommandResult.rejected(unknownCommandNotice(name));
        var result = cmd.execute(channel, author, args);
        return result == null ? SlashCommandResult.silent() : result;
    }

    /**
     * The private line a sender gets for {@code /nosuchthing}. Says three things, in order of
     * what the reader needs: their message was not sent, where the real list is, and how to
     * post the text anyway if that is what they meant.
     */
    public String unknownCommandNotice(String name) {
        var shown = name.length() > MAX_ECHOED_NAME ? name.substring(0, MAX_ECHOED_NAME) + "…" : name;
        return "/" + shown + " isn't a command here, so nothing was sent. "
                + "Type /help for the list. " + ESCAPE_HINT;
    }

    /**
     * The {@code /help} body: every registered command's {@link SlashCommand#help()} line,
     * alphabetically, then the escape hint.
     *
     * <p>Joined with a middle dot rather than newlines because the notice banner is a single
     * text node with default {@code white-space}, so a {@code \n} would collapse to a space and
     * run the entries together.
     */
    public String helpText() {
        var lines = byName.values().stream()
                .sorted(Comparator.comparing(SlashCommand::name))
                .map(SlashCommand::help)
                .toList();
        return "Commands: " + String.join(" · ", lines) + " · " + ESCAPE_HINT;
    }

    /** Registered command names, lowercase and sorted. Visible for tests and for {@code /help}. */
    public List<String> names() {
        return byName.keySet().stream().sorted().toList();
    }
}
