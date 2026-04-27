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

package com.example.chat.service;

import com.example.chat.domain.User;
import com.example.chat.repository.ChannelMemberRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.search.MessageIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchServiceUnitTest {

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blankQueryReturnsEmptyWithoutHittingIndex() {
        var messages = mock(MessageRepository.class);
        var members = mock(ChannelMemberRepository.class);
        var channels = mock(ChannelService.class);
        var index = mock(MessageIndexService.class);

        var service = new SearchService(messages, members, channels, index);
        var user = new User("sub", "u", "u@e", "U");

        assertThat(service.searchAllJoined(user, "  ", 10)).isEmpty();
        assertThat(service.searchAllJoined(user, null, 10)).isEmpty();
        assertThat(service.searchAllJoined(user, "x", 10)).isEmpty(); // <2 chars

        verifyNoInteractions(index);
        verifyNoInteractions(messages);
    }

    @Test
    void emptyJoinedSetReturnsEmpty() {
        var messages = mock(MessageRepository.class);
        var members = mock(ChannelMemberRepository.class);
        var channels = mock(ChannelService.class);
        var index = mock(MessageIndexService.class);
        when(members.findChannelsForUser(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        var service = new SearchService(messages, members, channels, index);
        var user = new User("sub", "u", "u@e", "U");

        assertThat(service.searchAllJoined(user, "hello", 10)).isEmpty();
        verifyNoInteractions(index);
        verifyNoInteractions(messages);
    }

    @Test
    void searchEverywhereWithoutAdminRoleIsRejected() {
        var service = new SearchService(
                mock(MessageRepository.class),
                mock(ChannelMemberRepository.class),
                mock(ChannelService.class),
                mock(MessageIndexService.class));
        var user = new User("sub", "u", "u@e", "U");

        var auth = new TestingAuthenticationToken("u", "n/a", "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> service.searchEverywhere(user, "hello", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void searchEverywhereWithoutAuthenticationIsRejected() {
        var service = new SearchService(
                mock(MessageRepository.class),
                mock(ChannelMemberRepository.class),
                mock(ChannelService.class),
                mock(MessageIndexService.class));
        var user = new User("sub", "u", "u@e", "U");

        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.searchEverywhere(user, "hello", 10))
                .isInstanceOf(AccessDeniedException.class);
    }
}
