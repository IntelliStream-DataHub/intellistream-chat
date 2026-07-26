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

/**
 * One slash command (e.g. {@code /poll}, {@code /remind}). Implementations are picked up as
 * Spring beans by {@link SlashCommandService}; matching is by lowercase {@link #name()} with
 * no leading slash.
 *
 * <p>{@link #execute(Channel, User, String)} receives the body MINUS the leading
 * {@code /name } so commands don't have to re-parse it. What it returns says where the output
 * goes: {@link SlashCommandResult#handled} for a message the whole channel sees,
 * {@link SlashCommandResult#privately} for a line only the sender sees, or
 * {@link SlashCommandResult#silent} for work with no visible output.
 *
 * <p>{@link #help()} is the one-line usage string. It is not decoration — {@code /help} is
 * assembled from these, so write it the way you would want to read it in a list.
 */
public interface SlashCommand {

    String name();

    String help();

    /**
     * @param channel the channel the command was sent in
     * @param author  the user that typed it
     * @param args    everything after {@code /name } — never null, may be empty/blank
     * @return where this command's output goes; {@code null} is tolerated and read as
     *         {@link SlashCommandResult#silent()}
     */
    SlashCommandResult execute(Channel channel, User author, String args);
}
