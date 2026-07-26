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
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.repository.ConversationMemberRepository;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchServiceUnitTest {

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private MessageRepository messages;
    private ChannelMemberRepository members;
    private ChannelRepository channelRepository;
    private ChannelService channels;
    private MessageIndexService index;
    private ConversationMessageRepository conversationMessages;
    private ConversationMemberRepository conversationMembers;
    private ConversationService conversations;

    private SearchService newService() {
        messages = mock(MessageRepository.class);
        members = mock(ChannelMemberRepository.class);
        channelRepository = mock(ChannelRepository.class);
        channels = mock(ChannelService.class);
        index = mock(MessageIndexService.class);
        conversationMessages = mock(ConversationMessageRepository.class);
        conversationMembers = mock(ConversationMemberRepository.class);
        conversations = mock(ConversationService.class);
        return new SearchService(messages, members, channelRepository, channels, index,
                conversationMessages, conversationMembers, conversations);
    }

    @Test
    void blankQueryReturnsEmptyWithoutHittingIndex() {
        var service = newService();
        var user = new User("sub", "u", "u@e", "U");

        assertThat(service.searchAccessible(user, "  ", 10)).isEmpty();
        assertThat(service.searchAccessible(user, null, 10)).isEmpty();
        assertThat(service.searchAccessible(user, "x", 10)).isEmpty(); // <2 chars

        verifyNoInteractions(index);
        verifyNoInteractions(messages);
        verifyNoInteractions(conversationMessages);
    }

    @Test
    void noAccessibleChannelOrConversationReturnsEmptyWithoutQueryingTheIndex() {
        // The guard that matters: with nothing to filter on, the query must not run at all.
        // The failure mode being prevented is an empty id set degrading into "no filter",
        // which would search every channel and every private conversation in the workspace.
        var service = newService();
        when(members.findChannelsForUser(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(conversationMembers.findConversationIdsForUser(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        var user = new User("sub", "u", "u@e", "U");

        assertThat(service.searchAccessible(user, "hello", 10)).isEmpty();
        verifyNoInteractions(index);
        verifyNoInteractions(messages);
        verifyNoInteractions(conversationMessages);
    }

    @Test
    void accessibleSearchPassesTheViewersReadableSetIntoTheQuery() {
        // The ACL must reach the index as query input. If this ever stops holding — if the
        // service starts calling an unrestricted search and trimming the results — the
        // membership sets stop being arguments and the leak is back.
        //
        // The channel side is joined ∪ every public channel, which is the read rule
        // ChannelService.requireMember applies. Both halves are stubbed distinctly so the
        // assertion fails if either is dropped rather than passing on the union by accident.
        var service = newService();
        var user = new User("sub", "u", "u@e", "U");
        // Channel is deliberately immutable (no id setter — see ChannelImmutabilityTest), so a
        // stub stands in for one that has been persisted.
        var joined = mock(Channel.class);
        when(joined.getId()).thenReturn(7L);
        when(members.findChannelsForUser(user)).thenReturn(List.of(joined));
        when(channelRepository.findIdsByType(ChannelType.PUBLIC)).thenReturn(List.of(8L, 9L));
        when(conversationMembers.findConversationIdsForUser(user)).thenReturn(List.of(11L, 12L));
        when(index.searchAccessiblePage(List.of(7L, 8L, 9L), List.of(11L, 12L), "hello",
                Set.of(), Set.of(), 0, 10))
                .thenReturn(MessageIndexService.Page.EMPTY);

        assertThat(service.searchAccessible(user, "hello", 10)).isEmpty();

        verify(index).searchAccessiblePage(List.of(7L, 8L, 9L), List.of(11L, 12L), "hello",
                Set.of(), Set.of(), 0, 10);
        verify(index, never()).searchEverywherePage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aPrivateChannelTheViewerHasNotJoinedNeverReachesTheFilter() {
        // The one id set that must not grow when the scope did. findIdsByType is asked for PUBLIC
        // and only PUBLIC; a private room the viewer is not in has no route into the query at all.
        var service = newService();
        var user = new User("sub", "u", "u@e", "U");
        when(members.findChannelsForUser(user)).thenReturn(List.of());
        when(channelRepository.findIdsByType(ChannelType.PUBLIC)).thenReturn(List.of(8L));
        when(conversationMembers.findConversationIdsForUser(user)).thenReturn(List.of());
        when(index.searchAccessiblePage(List.of(8L), List.of(), "hello", Set.of(), Set.of(), 0, 10))
                .thenReturn(MessageIndexService.Page.EMPTY);

        assertThat(service.searchAccessible(user, "hello", 10)).isEmpty();

        verify(channelRepository).findIdsByType(ChannelType.PUBLIC);
        verify(channelRepository, never()).findIdsByType(ChannelType.PRIVATE);
        verify(index).searchAccessiblePage(List.of(8L), List.of(), "hello", Set.of(), Set.of(), 0, 10);
    }

    @Test
    void searchEverywhereWithoutAdminRoleIsRejected() {
        var service = newService();
        var user = new User("sub", "u", "u@e", "U");

        var auth = new TestingAuthenticationToken("u", "n/a", "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> service.searchEverywhere(user, "hello", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void searchEverywhereWithoutAuthenticationIsRejected() {
        var service = newService();
        var user = new User("sub", "u", "u@e", "U");

        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.searchEverywhere(user, "hello", 10))
                .isInstanceOf(AccessDeniedException.class);
    }
}
