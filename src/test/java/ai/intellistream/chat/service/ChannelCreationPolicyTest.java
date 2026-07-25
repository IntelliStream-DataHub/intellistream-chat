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

import ai.intellistream.chat.domain.ChannelCreationPolicy;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may create a channel, and how often.
 *
 * <p>Two independent gates, and the tests keep them independent on purpose. The policy answers
 * "is this a workspace where members create channels", which an admin sets. The rate limit answers
 * "is this account behaving", and applies even under the permissive policy, because the abuse case
 * is not a member creating a channel, it is one account creating hundreds of them.
 */
class ChannelCreationPolicyTest {

    private final ChannelRepository channelRepo = mock(ChannelRepository.class);
    private final ChannelMemberRepository memberRepo = mock(ChannelMemberRepository.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final RateLimiter rateLimiter = new RateLimiter();

    private ChannelService service(ChannelCreationPolicy policy) {
        when(settings.channelCreationPolicy()).thenReturn(policy);
        when(channelRepo.findBySlug(any())).thenReturn(Optional.empty());
        when(channelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ChannelService(channelRepo, memberRepo,
                mock(MessageRepository.class), mock(AttachmentRepository.class),
                mock(MessageIndexService.class), mock(AttachmentService.class),
                new ChannelAccessCache(60, 1024), settings, rateLimiter,
                mock(ai.intellistream.chat.moderation.StorageQuotaService.class), null);
    }

    private static User member(String username) {
        return new User("sub-" + username, username, username + "@example.com", username);
    }

    private static User admin(String username) {
        var u = member(username);
        u.setAdmin(true);
        return u;
    }

    // ------------------------------------------------------------------ policy ----

    @Test
    void everyoneIsTheDefaultAndLetsAnOrdinaryMemberCreate() {
        var channel = service(ChannelCreationPolicy.EVERYONE)
                .create("Team news", null, ChannelType.PUBLIC, member("alice"));
        assertThat(channel.getSlug()).isEqualTo("team-news");
    }

    @Test
    void adminsOnlyRefusesAnOrdinaryMember() {
        var svc = service(ChannelCreationPolicy.ADMINS_ONLY);
        assertThatThrownBy(() -> svc.create("Team news", null, ChannelType.PUBLIC, member("bob")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("administrators");
        // Nothing was written: the gate runs before the slug check and before any save.
        verify(channelRepo, never()).save(any());
        verify(memberRepo, never()).save(any());
    }

    @Test
    void adminsOnlyStillAllowsAnAdmin() {
        var channel = service(ChannelCreationPolicy.ADMINS_ONLY)
                .create("Incidents", null, ChannelType.PRIVATE, admin("root"));
        assertThat(channel.getSlug()).isEqualTo("incidents");
    }

    @Test
    void anUnrecognisedStoredValueFallsBackToPermissive() {
        // A hand-edited row or a future value must not lock everybody out of creating channels.
        assertThat(ChannelCreationPolicy.parse("nonsense")).isEqualTo(ChannelCreationPolicy.EVERYONE);
        assertThat(ChannelCreationPolicy.parse(null)).isEqualTo(ChannelCreationPolicy.EVERYONE);
        assertThat(ChannelCreationPolicy.parse(" admins_only ")).isEqualTo(ChannelCreationPolicy.ADMINS_ONLY);
    }

    // -------------------------------------------------------------- rate limit ----

    @Test
    void theBurstLimitStopsAnAccountEvenUnderThePermissivePolicy() {
        var svc = service(ChannelCreationPolicy.EVERYONE);
        var spammer = member("spammer");
        for (int i = 0; i < 10; i++) {
            svc.create("Channel " + i, null, ChannelType.PUBLIC, spammer);
        }
        assertThatThrownBy(() -> svc.create("Channel 11", null, ChannelType.PUBLIC, spammer))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void theLimitIsPerAccountSoOnAbuserDoesNotBlockEveryoneElse() {
        var svc = service(ChannelCreationPolicy.EVERYONE);
        var spammer = member("spammer");
        for (int i = 0; i < 10; i++) svc.create("Spam " + i, null, ChannelType.PUBLIC, spammer);

        // A different account is unaffected. A shared counter here would turn one abuser into an
        // outage for the whole workspace, which is a worse failure than the abuse.
        var innocent = member("carol");
        assertThat(svc.create("Design", null, ChannelType.PUBLIC, innocent).getSlug()).isEqualTo("design");
    }

    @Test
    void aRefusedPolicyCheckDoesNotConsumeRateLimitBudget() {
        // Order matters: the policy is checked first, so a member repeatedly bouncing off an
        // admins-only workspace still has their full allowance if the admin later relaxes it.
        var denied = service(ChannelCreationPolicy.ADMINS_ONLY);
        var user = member("dave");
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> denied.create("X", null, ChannelType.PUBLIC, user))
                    .isInstanceOf(AccessDeniedException.class);
        }
        var allowed = service(ChannelCreationPolicy.EVERYONE);
        assertThat(allowed.create("Finally", null, ChannelType.PUBLIC, user).getSlug()).isEqualTo("finally");
    }
}
