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
    // Rename. The authorization rule and the collision rule, without a database — both are pure
    // decisions taken before anything is written, and both are easy to get wrong in a way no
    // integration test would notice until it was in front of a user.
    // ---------------------------------------------------------------------------------------

    @Test
    void renameRefusesASlugAnotherChannelAlreadyHas() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var channel = withId(new Channel("old", "Old", null, ChannelType.PUBLIC, alice),
                Channel.class, 7L);
        adminOf(memberRepo, channel, alice);
        when(channelRepo.existsBySlugAndIdNot("taken", 7L)).thenReturn(true);

        // Same rule and the same exception as create's duplicate check, so a name you could not have
        // created is not reachable by editing into it either.
        assertThatThrownBy(() -> service.rename(channel, "Taken", null, alice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("taken");
        verify(channelRepo, org.mockito.Mockito.never())
                .renameById(any(), any(), any(), any());
    }

    @Test
    void renamingToTheSameSlugIsAllowed() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var channel = withId(new Channel("deploys", "deploys", null, ChannelType.PUBLIC, alice),
                Channel.class, 7L);
        adminOf(memberRepo, channel, alice);
        when(channelRepo.findById(7L)).thenReturn(Optional.of(channel));
        // The channel's own slug is excluded from the collision check, which is the whole reason
        // the query is existsBySlugAndIdNot rather than findBySlug: capitalising a name must not be
        // refused for colliding with the channel being renamed.
        when(channelRepo.existsBySlugAndIdNot("deploys", 7L)).thenReturn(false);

        service.rename(channel, "Deploys", "  ", alice);

        // Blank description stores NULL, not "": one representation of "no description", so the
        // header does not render an empty separator beside the name.
        verify(channelRepo).renameById(7L, "deploys", "Deploys", null);
    }

    @Test
    void aPlainMemberCannotRenameTheChannel() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var bob = withId(new User("sub2", "bob", "b@e", "Bob"), User.class, 2L);
        var channel = withId(new Channel("room", "Room", null, ChannelType.PUBLIC, alice),
                Channel.class, 7L);
        memberOf(memberRepo, channel, bob);

        // Any member may invite; only an admin may change what the channel is. Same split the
        // role-change endpoint already draws.
        assertThatThrownBy(() -> service.rename(channel, "Bob's Room", null, bob))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(channelRepo, org.mockito.Mockito.never())
                .renameById(any(), any(), any(), any());
    }

    @Test
    void renameRejectsANameWithNothingToSlugify() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var channel = withId(new Channel("room", "Room", null, ChannelType.PUBLIC, alice),
                Channel.class, 7L);
        adminOf(memberRepo, channel, alice);

        assertThatThrownBy(() -> service.rename(channel, "!!!", null, alice))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Archiving. Two rules that are pure decisions and belong here rather than behind a database:
    // who may archive, and — the one that would go wrong quietly — that the archived check is
    // consulted BEFORE the write-access cache is.
    // ---------------------------------------------------------------------------------------

    @Test
    void aCachedWriteDecisionDoesNotSurviveArchiving() {
        var cache = new ChannelAccessCache(60, 1024);
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(mock(ChannelRepository.class), memberRepo, cache);
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var archived = withId(new Channel("done", "Done", null, ChannelType.PUBLIC, alice),
                Channel.class, 7L);
        setField(archived, "archivedAt", java.time.Instant.now());
        // Alice is a warm cache entry — she is the population that posts most, which is exactly the
        // population whose entries are warm and would keep posting into an archived channel if the
        // check sat after the short-circuit instead of before it.
        cache.rememberWriteAccess(7L, 1L);

        assertThatThrownBy(() -> service.requireWriteAccessCached(archived, alice))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // And the check never got as far as asking the database, which is how you can tell it fired
        // ahead of the short-circuit rather than because the cache happened to miss.
        verifyNoInteractions(memberRepo);
    }

    @Test
    void aWorkspaceAdminMayArchiveAChannelTheyDoNotAdminister() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var root = withId(new User("sub2", "root", "r@e", "Root"), User.class, 2L);
        var channel = withId(new Channel("orphan", "Orphan", null, ChannelType.PRIVATE, alice),
                Channel.class, 7L);
        when(memberRepo.findByChannelAndUser(channel, root)).thenReturn(Optional.empty());
        when(channelRepo.findById(7L)).thenReturn(Optional.of(channel));

        // Without a SecurityContext this is a plain non-member and must be refused. That default
        // matters: it is what the service-layer integration tests run under, so nothing there is
        // accidentally permitted by the absence of a principal.
        assertThatThrownBy(() -> service.archive(channel, root))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        // With ROLE_ADMIN on the live request it is allowed — the escape hatch for a channel whose
        // own admins have all left, which cannot be joined and so cannot acquire a new one.
        withWorkspaceAdmin(() -> service.archive(channel, root));
        verify(channelRepo).setArchivedById(org.mockito.ArgumentMatchers.eq(7L),
                any(java.time.Instant.class), org.mockito.ArgumentMatchers.eq(root),
                org.mockito.ArgumentMatchers.eq("root"));
    }

    @Test
    void theWorkspaceAdminCheckReadsSpringsAuthorityNotTheCachedColumn() {
        var channelRepo = mock(ChannelRepository.class);
        var memberRepo = mock(ChannelMemberRepository.class);
        var service = channelService(channelRepo, memberRepo, new ChannelAccessCache(60, 1024));
        var alice = withId(new User("sub", "alice", "a@e", "Alice"), User.class, 1L);
        var pretender = withId(new User("sub3", "pretender", "p@e", "P"), User.class, 3L);
        // User.admin is a login-time cache and its own javadoc says never to make an access decision
        // from it alone. Setting it here must change nothing.
        pretender.setAdmin(true);
        var channel = withId(new Channel("room", "Room", null, ChannelType.PUBLIC, alice),
                Channel.class, 7L);
        when(memberRepo.findByChannelAndUser(channel, pretender)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archive(channel, pretender))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
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

    /** Make {@code user} an ADMIN of {@code channel} as far as the membership repository is concerned. */
    private static void adminOf(ChannelMemberRepository memberRepo, Channel channel, User user) {
        when(memberRepo.findByChannelAndUser(channel, user)).thenReturn(Optional.of(
                new ai.intellistream.chat.domain.ChannelMember(channel, user,
                        ai.intellistream.chat.domain.ChannelRole.ADMIN)));
    }

    /** …and a plain MEMBER. */
    private static void memberOf(ChannelMemberRepository memberRepo, Channel channel, User user) {
        when(memberRepo.findByChannelAndUser(channel, user)).thenReturn(Optional.of(
                new ai.intellistream.chat.domain.ChannelMember(channel, user,
                        ai.intellistream.chat.domain.ChannelRole.MEMBER)));
    }

    /** Ids are assigned by the database; a unit test has to plant them. */
    private static <T> T withId(T entity, Class<?> type, long id) {
        setField(entity, "id", id);
        return entity;
    }

    /**
     * Plant a field the entity has no setter for — which, on {@link Channel}, is all of them. That
     * is the point of {@code ChannelImmutabilityTest}, and it means a test that needs an archived
     * channel has to reach past the type exactly as Hibernate does.
     */
    private static void setField(Object entity, String name, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Run {@code body} with a {@code ROLE_ADMIN} authentication in the security context, and take it
     * away again afterwards. The workspace-admin check reads Spring's live authority rather than
     * {@code User.isAdmin()}, so this is the only way to exercise that branch — and clearing the
     * context in a finally block is what stops it leaking into the next test in the class.
     */
    private static void withWorkspaceAdmin(Runnable body) {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "root", "n/a",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
        try {
            body.run();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
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
