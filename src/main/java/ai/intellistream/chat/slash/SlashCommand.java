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

package ai.intellistream.chat.slash;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;

/**
 * One slash command (e.g. {@code /poll}, {@code /remind}). Implementations are picked up as
 * Spring beans by {@link SlashCommandService}; matching is by lowercase {@link #name()} with
 * no leading slash.
 *
 * <p>{@link #execute(Channel, User, String)} receives the body MINUS the leading
 * {@code /name } so commands don't have to re-parse it. They return either a posted
 * {@link Message} (most commands; the broadcast layer announces it) or {@code null} when the
 * command had no visible chat output (e.g. a reminder that just queues for later).
 */
public interface SlashCommand {

    String name();

    String help();

    /**
     * @param channel the channel the command was sent in
     * @param author  the user that typed it
     * @param args    everything after {@code /name } — never null, may be empty/blank
     * @return the message produced by the command, or {@code null} if no immediate post was made
     */
    Message execute(Channel channel, User author, String args);
}
