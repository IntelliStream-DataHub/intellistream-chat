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

package ai.intellistream.chat.web;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.web.dto.SearchHitDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the wire shape of a {@code GET /api/search} row. The frontend branches on {@code scope}
 * and follows {@code url}; both are part of the contract, and the union's "exactly one side is
 * populated" rule is the sort of thing that quietly stops holding.
 */
class SearchHitDtoTest {

    private static User user(String username, String displayName) {
        return new User("kc-" + username, username, username + "@example.com", displayName);
    }

    @Test
    void aChannelHitCarriesChannelIdentityAndAChannelPermalink() {
        var alice = user("alice", "Alice A");
        var channel = mock(Channel.class);
        when(channel.getId()).thenReturn(7L);
        when(channel.getName()).thenReturn("General");
        var message = mock(Message.class);
        when(message.getId()).thenReturn(42L);
        when(message.getChannel()).thenReturn(channel);
        when(message.getAuthor()).thenReturn(alice);
        when(message.getBodyMarkdown()).thenReturn("hello **world**");

        var dto = SearchHitDto.ofChannel(message, true, "<p>hello <strong>world</strong></p>",
                "hello <mark>world</mark>");

        assertThat(dto.scope()).isEqualTo("channel");
        assertThat(dto.channelId()).isEqualTo(7L);
        assertThat(dto.channelName()).isEqualTo("General");
        assertThat(dto.url()).isEqualTo("/channels/7?m=42#m=42");
        assertThat(dto.conversationId()).isNull();
        assertThat(dto.conversationType()).isNull();
        assertThat(dto.conversationTitle()).isNull();
        assertThat(dto.authorUsername()).isEqualTo("alice");
        assertThat(dto.authorDisplayName()).isEqualTo("Alice A");
        assertThat(dto.bodySnippet()).isEqualTo("hello <mark>world</mark>");
        assertThat(dto.channelJoined()).isTrue();
    }

    @Test
    void aChannelHitFromARoomTheViewerHasNotJoinedSaysSo() {
        // Search spans every public channel, so this is a routine row rather than an edge case,
        // and the flag is the only thing on the wire that lets the UI mark it.
        var alice = user("alice", "Alice A");
        var channel = mock(Channel.class);
        when(channel.getId()).thenReturn(8L);
        when(channel.getName()).thenReturn("Incidents");
        var message = mock(Message.class);
        when(message.getId()).thenReturn(43L);
        when(message.getChannel()).thenReturn(channel);
        when(message.getAuthor()).thenReturn(alice);
        when(message.getBodyMarkdown()).thenReturn("outage postmortem");

        var dto = SearchHitDto.ofChannel(message, false, "<p>outage postmortem</p>", null);

        assertThat(dto.channelJoined()).isFalse();
        assertThat(dto.url()).isEqualTo("/channels/8?m=43#m=43");
    }

    @Test
    void aConversationHitCarriesConversationIdentityAndAConversationPermalink() {
        var bob = user("bob", "Bob B");
        var conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(9L);
        when(conversation.getType()).thenReturn(ConversationType.DIRECT);
        var message = mock(ConversationMessage.class);
        when(message.getId()).thenReturn(5L);
        when(message.getConversation()).thenReturn(conversation);
        when(message.getAuthor()).thenReturn(bob);
        when(message.getBodyMarkdown()).thenReturn("lunch?");

        var dto = SearchHitDto.ofConversation(message, "Bob B", "<p>lunch?</p>", null);

        assertThat(dto.scope()).isEqualTo("conversation");
        assertThat(dto.conversationId()).isEqualTo(9L);
        assertThat(dto.conversationType()).isEqualTo("DIRECT");
        assertThat(dto.conversationTitle()).isEqualTo("Bob B");
        assertThat(dto.url()).isEqualTo("/conversations/9#m=5");
        assertThat(dto.channelId()).isNull();
        assertThat(dto.channelName()).isNull();
        // A conversation you can see is one you are in — there is no non-member tier for a DM.
        assertThat(dto.channelJoined()).isTrue();
    }

    @Test
    void aGroupConversationHitReportsItsType() {
        var bob = user("bob", "Bob B");
        var conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(11L);
        when(conversation.getType()).thenReturn(ConversationType.GROUP);
        var message = mock(ConversationMessage.class);
        when(message.getId()).thenReturn(6L);
        when(message.getConversation()).thenReturn(conversation);
        when(message.getAuthor()).thenReturn(bob);
        when(message.getBodyMarkdown()).thenReturn("standup in five");

        var dto = SearchHitDto.ofConversation(message, "Platform team", "<p>standup in five</p>", null);

        assertThat(dto.conversationType()).isEqualTo("GROUP");
        assertThat(dto.conversationTitle()).isEqualTo("Platform team");
        assertThat(dto.url()).isEqualTo("/conversations/11#m=6");
    }
}
