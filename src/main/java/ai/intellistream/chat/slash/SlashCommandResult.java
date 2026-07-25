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

import ai.intellistream.chat.domain.Message;

/**
 * What {@link SlashCommandService#dispatch} hands back to its caller. {@code handled=false}
 * means "this body wasn't a slash command, please post it as a normal message"; the
 * dispatcher distinguishes that from {@code handled=true, message=null} (a recognised
 * command that produced no immediate output, like a queued reminder).
 */
public record SlashCommandResult(boolean handled, Message message) {

    public static final SlashCommandResult NOT_HANDLED = new SlashCommandResult(false, null);

    public static SlashCommandResult handled(Message message) {
        return new SlashCommandResult(true, message);
    }

    public static SlashCommandResult silent() {
        return new SlashCommandResult(true, null);
    }
}
