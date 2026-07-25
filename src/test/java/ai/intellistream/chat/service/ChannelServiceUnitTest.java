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

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
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

        var service = new ChannelService(channelRepo, memberRepo,
                mock(ai.intellistream.chat.repository.MessageRepository.class),
                mock(ai.intellistream.chat.repository.AttachmentRepository.class),
                mock(ai.intellistream.chat.search.MessageIndexService.class),
                mock(ai.intellistream.chat.service.AttachmentService.class),
                new ai.intellistream.chat.service.ChannelAccessCache(60, 1024),
                permissiveSettings(),
                new ai.intellistream.chat.security.RateLimiter(),
                mock(ai.intellistream.chat.moderation.StorageQuotaService.class),
                null);
        var creator = new User("sub", "alice", "a@e", "Alice");

        var channel = service.create("  Hello, World!  ", "description", ChannelType.PUBLIC, creator);

        assertThat(channel.getSlug()).isEqualTo("hello-world");
        var captor = ArgumentCaptor.forClass(ai.intellistream.chat.domain.ChannelMember.class);
        verify(memberRepo).save(captor.capture());
        assertThat(captor.getValue().getRole().name()).isEqualTo("ADMIN");
    }

    @Test
    void createRejectsNonAlphaName() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        when(channelRepo.findBySlug(any())).thenReturn(Optional.empty());

        var service = new ChannelService(channelRepo, memberRepo,
                mock(ai.intellistream.chat.repository.MessageRepository.class),
                mock(ai.intellistream.chat.repository.AttachmentRepository.class),
                mock(ai.intellistream.chat.search.MessageIndexService.class),
                mock(ai.intellistream.chat.service.AttachmentService.class),
                new ai.intellistream.chat.service.ChannelAccessCache(60, 1024),
                permissiveSettings(),
                new ai.intellistream.chat.security.RateLimiter(),
                mock(ai.intellistream.chat.moderation.StorageQuotaService.class),
                null);
        var creator = new User("sub", "alice", "a@e", "Alice");

        assertThatThrownBy(() -> service.create("!!!", null, ChannelType.PUBLIC, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Settings stub that permits channel creation. These tests are about slug rules, not about the
     * creation policy, so they assert the permissive default rather than restating the gate.
     */
    private static ai.intellistream.chat.service.AppSettingsService permissiveSettings() {
        var settings = mock(ai.intellistream.chat.service.AppSettingsService.class);
        when(settings.channelCreationPolicy())
                .thenReturn(ai.intellistream.chat.domain.ChannelCreationPolicy.EVERYONE);
        return settings;
    }
}
