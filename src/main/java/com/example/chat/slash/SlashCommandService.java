/*
 * Copyright 2026 Olav Gjerde
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

package com.example.chat.slash;

import com.example.chat.domain.Channel;
import com.example.chat.domain.User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes {@code /name args} bodies to the matching {@link SlashCommand} bean. A body that
 * doesn't start with {@code /} or whose token after the slash isn't a registered command
 * comes back as {@link SlashCommandResult#NOT_HANDLED} — the message dispatcher then posts
 * it normally. Unknown {@code /something} text is also passed through (rather than erroring)
 * so a typo just becomes a regular message instead of disappearing.
 */
@Service
public class SlashCommandService {

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
        if (cmd == null) return SlashCommandResult.NOT_HANDLED;
        var result = cmd.execute(channel, author, args);
        return result == null ? SlashCommandResult.silent() : SlashCommandResult.handled(result);
    }
}
