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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChannelServiceUnitTest {

    @Test
    void createNormalizesNameIntoSlug() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        when(channelRepo.findBySlug(any())).thenReturn(Optional.empty());
        when(channelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
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

        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
        var creator = new User("sub", "alice", "a@e", "Alice");

        assertThatThrownBy(() -> service.create("!!!", null, ChannelType.PUBLIC, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------
    // SUBSCRIBE authorization. One frame per channel the user is a member of, so the cost of this
    // check is now multiplied by membership count on every connect.
    // ---------------------------------------------------------------------------------------

    @Test
    void subscribingToAPublicChannelAsksTheDatabaseNothing() {
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(mock(ChannelRepository.class), memberRepo,
                new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var channel = withId(new Channel(
                "open", "open", null, ChannelType.PUBLIC, alice), Channel.class, 7L);

        service.requireMemberCached(channel, alice);
        service.requireMemberCached(channel, alice);

        // requireMember short-circuits for PUBLIC before any query, so there is nothing to cache
        // and nothing to spend. This is why 200 public subscriptions cost 200 map lookups.
        verifyNoInteractions(memberRepo);
    }

    @Test
    void subscribingToAPrivateChannelChecksMembershipOnceThenCaches() {
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(mock(ChannelRepository.class), memberRepo,
                new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var channel = withId(new Channel(
                "secret", "secret", null, ChannelType.PRIVATE, alice), Channel.class, 7L);
        when(memberRepo.existsByChannelAndUser(channel, alice)).thenReturn(true);

        service.requireMemberCached(channel, alice);
        service.requireMemberCached(channel, alice);
        service.requireMemberCached(channel, alice);

        verify(memberRepo, times(1)).existsByChannelAndUser(channel, alice);
    }

    @Test
    void aRefusedSubscriptionIsNeverCached() {
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(mock(ChannelRepository.class), memberRepo,
                new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var bob = withId(new User("sub2", "bob", "b@e", "Bob"), User.class, 2L);
        var channel = withId(new Channel(
                "secret", "secret", null, ChannelType.PRIVATE, alice), Channel.class, 7L);
        when(memberRepo.existsByChannelAndUser(channel, bob)).thenReturn(false, true);

        assertThatThrownBy(() -> service.requireMemberCached(channel, bob))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // Negatives are deliberately not stored, so an invite that lands a moment later takes
        // effect immediately rather than after the TTL.
        service.requireMemberCached(channel, bob);

        verify(memberRepo, times(2)).existsByChannelAndUser(channel, bob);
    }

    /**
     * One place that knows {@link ChannelService}'s constructor. The tests here care about three of
     * its collaborators; spelling the rest out per test made the interesting arguments hard to find
     * and meant every added dependency touched every test.
     */
    private static ChannelService channelService(ChannelRepository channelRepo,
                                                 ChannelMemberRepository memberRepo,
                                                 ChannelAccessCache cache) {
        return new ChannelService(channelRepo, memberRepo,
                mock(ai.intellistream.chat.repository.MessageRepository.class),
                mock(ai.intellistream.chat.repository.AttachmentRepository.class),
                mock(ai.intellistream.chat.search.MessageIndexService.class),
                mock(ai.intellistream.chat.service.AttachmentService.class),
                cache,
                permissiveSettings(),
                new ai.intellistream.chat.security.RateLimiter(),
                mock(ai.intellistream.chat.moderation.StorageQuotaService.class),
                // No broker in a unit test; ifAvailable() on an empty provider is a no-op.
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(ChannelSubscriptionRevoker.class));
    }

    /** Ids are assigned by the database; a unit test has to plant them. */
    private static <T> T withId(T entity, Class<?> type, long id) {
        try {
            var field = type.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
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
