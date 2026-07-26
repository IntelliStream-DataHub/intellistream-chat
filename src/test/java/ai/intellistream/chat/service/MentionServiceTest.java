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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Pure-logic tests for handle extraction — trailing punctuation (N22) and code exclusion (N21). */
class MentionServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final MentionService svc = new MentionService(
            users, mock(MessageMentionRepository.class),
            mock(ChannelMemberRepository.class), new PresenceTracker());

    @Test
    void plainMentionExtracted() {
        assertThat(svc.extractHandles("hey @alice how are you")).containsExactly("alice");
    }

    @Test
    void trailingSentencePunctuationIsNotPartOfTheHandle() {
        assertThat(svc.extractHandles("thanks @bob.")).containsExactly("bob");           // N22
        assertThat(svc.extractHandles("cc @alice, @bob!")).containsExactly("alice", "bob");
    }

    @Test
    void midHandleDotAndHyphenAreKept() {
        assertThat(svc.extractHandles("ping @a.b-c now")).containsExactly("a.b-c");
    }

    @Test
    void mentionInsideInlineCodeIsIgnored() {
        assertThat(svc.extractHandles("run `@bob` in the shell")).isEmpty();             // N21
    }

    @Test
    void mentionInsideFencedCodeIsIgnored() {
        assertThat(svc.extractHandles("```\n@bob = whoami\n```")).isEmpty();             // N21
    }

    @Test
    void emailAddressDoesNotTriggerAMention() {
        assertThat(svc.extractHandles("mail me at foo@bar.com")).isEmpty();
    }

    // ---------- Broadcast handles ----------

    @Test
    void broadcastHandlesAreRecognised() {
        assertThat(MentionService.broadcastFor("channel")).isEqualTo(MentionService.Broadcast.CHANNEL);
        assertThat(MentionService.broadcastFor("here")).isEqualTo(MentionService.Broadcast.HERE);
        assertThat(MentionService.broadcastFor("everyone")).isEqualTo(MentionService.Broadcast.EVERYONE);
        // Case-insensitive like every other handle, and nothing else qualifies.
        assertThat(MentionService.broadcastFor("Channel")).isEqualTo(MentionService.Broadcast.CHANNEL);
        assertThat(MentionService.broadcastFor("channels")).isNull();
        assertThat(MentionService.broadcastFor("alice")).isNull();
    }

    /** {@code @everyone} borrows {@code @channel}'s audience — the whole of the synonym decision. */
    @Test
    void everyoneAddressesTheChannelAudience() {
        assertThat(MentionService.Broadcast.EVERYONE.audience())
                .isEqualTo(MentionService.Broadcast.CHANNEL);
        assertThat(MentionService.Broadcast.HERE.audience()).isEqualTo(MentionService.Broadcast.HERE);
        assertThat(MentionService.Broadcast.CHANNEL.handle()).isEqualTo("channel");
    }

    /** One fan-out per message: the widest audience wins, so @channel absorbs a co-occurring @here. */
    @Test
    void channelWinsOverHereWhenBothAppear() {
        assertThat(MentionService.broadcastAmong(Set.of("here", "channel")))
                .isEqualTo(MentionService.Broadcast.CHANNEL);
        assertThat(MentionService.broadcastAmong(Set.of("here")))
                .isEqualTo(MentionService.Broadcast.HERE);
        assertThat(MentionService.broadcastAmong(Set.of("alice", "bob"))).isNull();
    }

    /**
     * Reserved: even when a user has registered the username "channel", @channel is the broadcast
     * and never that person. Otherwise one unlucky account would silently swallow every announcement
     * in the workspace — and the lookup isn't even attempted, so nothing depends on who exists.
     */
    @Test
    void broadcastHandleIsNotResolvedAsAUsername() {
        var impostor = new User("kc-channel", "channel", "channel@example.com", "Channel Person");
        Mockito.when(users.findByUsernameIgnoreCase("channel")).thenReturn(Optional.of(impostor));

        assertThat(svc.resolvedUsernames("heads up @channel")).isEmpty();
        Mockito.verify(users, Mockito.never()).findByUsernameIgnoreCase("channel");
    }

    /** N21 holds for broadcasts too: documenting "@channel" in a code span must not notify a room. */
    @Test
    void broadcastInsideCodeIsIgnored() {
        assertThat(svc.extractHandles("type `@channel` to notify everyone")).isEmpty();
        assertThat(svc.extractHandles("```\n@here\n```")).isEmpty();
    }

    /** The pattern is agnostic — broadcasts are ordinary handles until something classifies them. */
    @Test
    void broadcastHandlesAreExtractedLikeAnyOtherHandle() {
        assertThat(svc.extractHandles("@channel and @here and @everyone"))
                .containsExactly("channel", "here", "everyone");
    }
}
