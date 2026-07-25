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

import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Pure-logic tests for handle extraction — trailing punctuation (N22) and code exclusion (N21). */
class MentionServiceTest {

    private final MentionService svc = new MentionService(
            mock(UserRepository.class), mock(MessageMentionRepository.class),
            mock(ChannelMemberRepository.class));

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
}
