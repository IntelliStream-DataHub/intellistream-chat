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
 * What a {@link SlashCommand} produced, and what {@link SlashCommandService#dispatch} hands back
 * to its caller. Three things can come out of a command, and they are independent:
 *
 * <ul>
 *   <li>{@code handled=false} — this body was not a slash command at all, please post it as an
 *       ordinary message. Only {@link #NOT_HANDLED} says that; a command never returns it.</li>
 *   <li>{@code message} — a channel message the command posted, for the caller to broadcast.</li>
 *   <li>{@code notice} — text for the sender's eyes only, delivered on
 *       {@code /user/queue/notices}. A command whose whole output is a notice returns no message,
 *       which is how {@code /help} and the {@code /remind} confirmation stay out of the room.</li>
 * </ul>
 */
public record SlashCommandResult(boolean handled, Message message, Notice notice) {

    /**
     * A private, sender-only line. {@code level} is {@code "info"} or {@code "error"} — the
     * client styles an error red ({@code showSlashNotice} in {@code chat/index.js}).
     */
    public record Notice(String level, String text) {}

    public static final SlashCommandResult NOT_HANDLED = new SlashCommandResult(false, null, null);

    /** A command that posted a channel message everybody should see. */
    public static SlashCommandResult handled(Message message) {
        return new SlashCommandResult(true, message, null);
    }

    /** A command that did its work with no visible output at all. */
    public static SlashCommandResult silent() {
        return new SlashCommandResult(true, null, null);
    }

    /** A command that answers only its caller — nothing reaches the channel. */
    public static SlashCommandResult privately(String text) {
        return new SlashCommandResult(true, null, new Notice("info", text));
    }

    /**
     * The body looked like a command but named none. {@code handled=true} on purpose: the point
     * of rejecting is that the text is <em>not</em> posted, so the dispatcher must not fall
     * through to the normal message path.
     */
    public static SlashCommandResult rejected(String text) {
        return new SlashCommandResult(true, null, new Notice("error", text));
    }
}
