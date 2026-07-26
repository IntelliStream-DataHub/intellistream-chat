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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code /help} — the list of commands, to the person who asked and nobody else.
 *
 * <p>This is the command every user tries first, and until it existed it was broadcast as the
 * literal text "/help". It answers on {@code /user/queue/notices}, so it works in a channel
 * you are the only reader of and never turns a question into a message someone has to scroll
 * past.
 *
 * <p>The registry comes in through an {@link ObjectProvider} rather than as a constructor
 * dependency because {@link SlashCommandService} is built <em>from</em> the list of commands,
 * this one included: asking for it directly is a cycle. Resolving it at execute time also means
 * the list can never drift from what is actually registered.
 */
@Component
public class HelpCommand implements SlashCommand {

    private final ObjectProvider<SlashCommandService> registry;

    public HelpCommand(ObjectProvider<SlashCommandService> registry) {
        this.registry = registry;
    }

    @Override public String name() { return "help"; }

    @Override public String help() { return "/help — this list"; }

    /**
     * No channel access check and no database work: reading the list of commands is not an act
     * in the room. A user who has not joined a public channel can still ask what exists.
     */
    @Override
    public SlashCommandResult execute(Channel channel, User author, String args) {
        return SlashCommandResult.privately(registry.getObject().helpText());
    }
}
