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
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MentionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic coverage for the dispatcher: which bodies are commands, what happens to the ones
 * that name nothing, and what {@code /help} says. No Spring, no database — the registry is a
 * list of test doubles, which is also the point: nothing here knows about /poll or /remind.
 */
class SlashCommandServiceTest {

    /** A command that records nothing and posts nothing; only its name and help text matter. */
    private record Stub(String name, String help) implements SlashCommand {
        @Override public SlashCommandResult execute(Channel channel, User author, String args) {
            return SlashCommandResult.privately("ran " + name + " with [" + args + "]");
        }
    }

    private static SlashCommandService service(SlashCommand... commands) {
        return new SlashCommandService(List.of(commands));
    }

    // ---------------------------------------------------------------- unknown commands ----

    @Test
    void unknownCommandIsRejectedRatherThanPosted() {
        var result = service(new Stub("poll", "/poll ...")).dispatch(null, null, "/leave");

        // handled(), so the caller must NOT fall through to "post it as an ordinary message".
        assertThat(result.handled()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.notice()).isNotNull();
        assertThat(result.notice().level()).isEqualTo("error");
    }

    @Test
    void rejectionNamesTheCommandSaysNothingWasSentAndOffersHelpAndAnEscape() {
        var notice = service(new Stub("poll", "/poll ...")).dispatch(null, null, "/dnd 30m").notice();

        assertThat(notice.text())
                .contains("/dnd")                   // which command they asked for
                .contains("nothing was sent")       // the message is gone, not hidden
                .contains("/help")                  // where the real list is
                .contains("\\/leave");              // how to post the text anyway
    }

    @Test
    void rejectionDoesNotEchoAnUnboundedName() {
        // The "name" is whatever precedes the first space, so it can be the whole 8000-char body.
        var huge = "/" + "x".repeat(500) + " tail";
        var notice = service().dispatch(null, null, huge).notice();

        assertThat(notice.text()).hasSizeLessThan(300).contains("…");
    }

    @Test
    void everyMuscleMemoryCommandFromSlackIsRejectedNotBroadcast() {
        // The list from the UX review. None of these exist here; every one of them used to be
        // posted verbatim to the whole channel.
        var svc = service(new Stub("poll", "/poll ..."), new Stub("remind", "/remind ..."));
        for (var name : List.of("leave", "dnd", "away", "invite", "me", "topic",
                                "archive", "mute", "shrug", "status")) {
            var result = svc.dispatch(null, null, "/" + name + " whatever");
            assertThat(result.handled())
                    .describedAs("/%s must not fall through to the post path", name)
                    .isTrue();
            assertThat(result.message()).isNull();
        }
    }

    // ------------------------------------------------------------------- escape hatches ----

    @Test
    void escapeHatchesAreNotTreatedAsCommands() {
        // Each of the three routes ESCAPE_HINT advertises has to actually miss the dispatcher.
        assertThat(SlashCommandService.looksLikeCommand("\\/leave")).isFalse();
        assertThat(SlashCommandService.looksLikeCommand(" /leave")).isFalse();
        assertThat(SlashCommandService.looksLikeCommand("`/leave`")).isFalse();

        var svc = service(new Stub("poll", "/poll ..."));
        for (var body : List.of("\\/poll not a poll", " /poll not a poll", "`/poll` not a poll")) {
            assertThat(svc.dispatch(null, null, body))
                    .describedAs("%s must be posted as ordinary text", body)
                    .isEqualTo(SlashCommandResult.NOT_HANDLED);
        }
    }

    @Test
    void backslashEscapedSlashRendersAsABareSlash() {
        // The hint is only honest if the reader gets "/leave" on screen, not "\/leave".
        var mentions = Mockito.mock(MentionService.class);
        Mockito.when(mentions.resolvedUsernames(Mockito.anyString())).thenReturn(Set.of());
        var html = new MarkdownRenderer(mentions).render("\\/leave the building");

        assertThat(html).contains("/leave the building").doesNotContain("\\/");
    }

    // ------------------------------------------------------------------------- /help ----

    @Test
    void helpListsEveryRegisteredCommandAlphabeticallyWithItsUsageLine() {
        var svc = service(new Stub("remind", "/remind me in 5m to <message>"),
                new Stub("poll", "/poll Question? | A | B"));

        var text = svc.helpText();

        assertThat(text).contains("/poll Question? | A | B")
                .contains("/remind me in 5m to <message>");
        assertThat(text.indexOf("/poll")).isLessThan(text.indexOf("/remind"));
        assertThat(text).contains(SlashCommandService.ESCAPE_HINT);
    }

    @Test
    void helpCommandAnswersPrivatelyAndPostsNothing() {
        SlashCommandService[] holder = new SlashCommandService[1];
        var help = new HelpCommand(provider(() -> holder[0]));
        holder[0] = service(help, new Stub("poll", "/poll Question? | A | B"));

        var result = holder[0].dispatch(null, null, "/help");

        assertThat(result.handled()).isTrue();
        assertThat(result.message()).describedAs("/help must never reach the channel").isNull();
        assertThat(result.notice().level()).isEqualTo("info");
        assertThat(result.notice().text()).contains("/poll Question? | A | B").contains("/help");
    }

    @Test
    void registeredCommandsResultIsPassedThroughUnchanged() {
        var result = service(new Stub("poll", "/poll ...")).dispatch(null, null, "/POLL  a | b ");

        // Case-insensitive name match; args arrive trimmed and without the command word.
        assertThat(result.notice().text()).isEqualTo("ran poll with [a | b]");
    }

    @Test
    void nonCommandBodiesAreNotHandled() {
        var svc = service(new Stub("poll", "/poll ..."));
        assertThat(svc.dispatch(null, null, "hello")).isEqualTo(SlashCommandResult.NOT_HANDLED);
        assertThat(svc.dispatch(null, null, "/123 not a command"))
                .isEqualTo(SlashCommandResult.NOT_HANDLED);
        assertThat(svc.dispatch(null, null, null)).isEqualTo(SlashCommandResult.NOT_HANDLED);
    }

    /** Minimal {@link ObjectProvider} over a supplier — enough for a lazily-resolved singleton. */
    private static <T> ObjectProvider<T> provider(java.util.function.Supplier<T> supplier) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return supplier.get(); }
        };
    }
}
