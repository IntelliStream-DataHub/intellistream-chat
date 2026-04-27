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

package ai.intellistream.radiance.service;

import ai.intellistream.radiance.domain.ChannelType;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.ChannelMemberRepository;
import ai.intellistream.radiance.repository.ChannelRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelServiceUnitTest {

    @Test
    void createNormalizesNameIntoSlug() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        when(channelRepo.findBySlug(any())).thenReturn(Optional.empty());
        when(channelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var service = new ChannelService(channelRepo, memberRepo);
        var creator = new User("sub", "alice", "a@e", "Alice");

        var channel = service.create("  Hello, World!  ", "description", ChannelType.PUBLIC, creator);

        assertThat(channel.getSlug()).isEqualTo("hello-world");
        var captor = ArgumentCaptor.forClass(ai.intellistream.radiance.domain.ChannelMember.class);
        verify(memberRepo).save(captor.capture());
        assertThat(captor.getValue().getRole().name()).isEqualTo("ADMIN");
    }

    @Test
    void createRejectsNonAlphaName() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        when(channelRepo.findBySlug(any())).thenReturn(Optional.empty());

        var service = new ChannelService(channelRepo, memberRepo);
        var creator = new User("sub", "alice", "a@e", "Alice");

        assertThatThrownBy(() -> service.create("!!!", null, ChannelType.PUBLIC, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
