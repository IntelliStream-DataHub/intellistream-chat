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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The forwarded body, as pure text assembly — the branches an integration test would have to build
 * a 3,000-character message to reach.
 */
class MessageForwardBodyTest {

    private static Message source(String body) {
        var channel = mock(Channel.class);
        when(channel.getId()).thenReturn(7L);
        when(channel.getName()).thenReturn("planning");
        var author = mock(User.class);
        when(author.getUsername()).thenReturn("alice");
        var message = mock(Message.class);
        when(message.getId()).thenReturn(42L);
        when(message.getChannel()).thenReturn(channel);
        when(message.getAuthor()).thenReturn(author);
        when(message.getBodyMarkdown()).thenReturn(body);
        when(message.getCreatedAt()).thenReturn(Instant.parse("2026-07-26T10:15:30Z"));
        return message;
    }

    @Test
    void theQuoteCarriesAuthorChannelDateAndAPermalink() {
        var body = MessageForwardService.buildBody(source("ship it"), null);
        assertThat(body).isEqualTo(
                "> **@alice** in [#planning](/channels/7?m=42#m=42) · 26 Jul 2026\n"
                + ">\n"
                + "> ship it");
    }

    @Test
    void aCommentGoesAboveTheQuoteWithABlankLineBetween() {
        var body = MessageForwardService.buildBody(source("ship it"), "  relevant to us  ");
        assertThat(body).startsWith("relevant to us\n\n> **@alice**");
    }

    /**
     * Every line gets its own marker. Markdown's lazy continuation would otherwise fold a quoted
     * list or heading into the surrounding quote and change what the original said.
     */
    @Test
    void everyLineIsPrefixedIncludingBlankOnes() {
        var body = MessageForwardService.buildBody(source("one\n\ntwo"), null);
        assertThat(body).contains("> one\n> \n> two");
    }

    @Test
    void aVeryLongOriginalIsTruncatedWithAnEllipsis() {
        var body = MessageForwardService.buildBody(source("x".repeat(5000)), null);
        assertThat(body).endsWith("…");
        // Bounded well inside the 8000-character message limit, with room for a comment.
        assertThat(body.length()).isLessThan(4000);
    }

    @Test
    void anOverlongCommentIsRefusedRatherThanSilentlyCut() {
        var message = source("hi");
        var tooLong = "y".repeat(2001);
        assertThatThrownBy(() -> MessageForwardService.buildBody(message, tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyANonPublicSourceNeedsAnAcknowledgement() {
        var open = mock(Channel.class);
        when(open.getType()).thenReturn(ChannelType.PUBLIC);
        var shut = mock(Channel.class);
        when(shut.getType()).thenReturn(ChannelType.PRIVATE);

        assertThat(MessageForwardService.requiresDisclosureAcknowledgement(open)).isFalse();
        assertThat(MessageForwardService.requiresDisclosureAcknowledgement(shut)).isTrue();
    }
}
