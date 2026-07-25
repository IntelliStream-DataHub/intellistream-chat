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

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelMember;
import ai.intellistream.chat.domain.ChannelRole;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.metrics.WritePathMetrics;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.MessageReactionRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.security.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Storage usage used to be a number that only ever went up: {@code StorageQuotaService} was charged
 * on upload and credited nowhere, so an account that deleted everything it had ever posted still
 * could not upload again. These tests pin the credit-back at the two hard-delete sites in the
 * channel world — one message (with its thread replies) and a whole channel.
 *
 * <p>What they are really guarding is the <em>gather early, apply late</em> shape. The uploader and
 * the size live only on the attachment rows; the file on disk names neither. Read them after the
 * delete and there is nothing to read, apply the credit before the commit and a rollback hands back
 * bytes that are still stored. Both halves are easy to break with a well-meaning tidy-up, and
 * neither failure is visible until an account is mysteriously full.
 */
class StorageCreditBackTest {

    private final User alice = user(1L, "alice");
    private final User bob = user(2L, "bob");

    // ------------------------------------------------------------ MessageService.delete

    @Test
    void deletingAMessageCreditsItsAuthorAndEveryReplyAuthor() {
        var fixture = new MessageFixture();

        fixture.service.delete(100L, alice);

        // A thread's attachments belong to whoever posted each one, so one delete can free bytes
        // for several accounts — a single-uploader assumption here would silently under-credit.
        verify(fixture.quotas).releaseAll(Map.of(1L, 300L, 2L, 700L));
    }

    @Test
    void theCreditHappensInsideTheDeletingTransactionNotAfterIt() {
        var fixture = new MessageFixture();

        fixture.service.delete(100L, alice);

        // The credit is applied before the post-commit hooks run, because it is part of the
        // deleting transaction rather than a callback registered against it. That is what makes
        // the refund and the row deletion commit or roll back together.
        //
        // The earlier arrangement credited after the commit and this test asserted it went last,
        // on the theory that a hook which throws is logged and skipped, so the one whose loss
        // nothing can repair should run at the end. Ordering was the wrong lever: a post-commit
        // credit that fails charges the account forever for bytes that are gone, and user_storage
        // exposes only an atomic delta, so no ordering makes that recoverable. Doing it in the
        // transaction removes the failure mode instead of scheduling around it.
        var ordered = inOrder(fixture.quotas, fixture.messageIndex, fixture.attachmentService);
        ordered.verify(fixture.quotas).releaseAll(any());
        ordered.verify(fixture.messageIndex).deleteAll(anyList());
        ordered.verify(fixture.attachmentService).deleteFiles(anyList());
    }

    @Test
    void aMessageWithNoAttachmentsCreditsNothing() {
        var fixture = new MessageFixture();
        when(fixture.attachmentRepository.findByMessageOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        fixture.service.delete(100L, alice);

        verify(fixture.quotas).releaseAll(Map.of());
    }

    /** MessageService.delete's collaborators, wired so one message with one reply comes back. */
    private final class MessageFixture {
        final MessageRepository messageRepository = mock(MessageRepository.class);
        final AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        final AttachmentService attachmentService = mock(AttachmentService.class);
        final MessageIndexService messageIndex = mock(MessageIndexService.class);
        final ChannelService channelService = mock(ChannelService.class);
        final StorageQuotaService quotas = mock(StorageQuotaService.class);
        final MessageService service;

        MessageFixture() {
            var channel = channel();
            // alice's message with a 300-byte file; bob replied with a 700-byte one.
            var message = message(100L, channel, alice);
            var reply = message(101L, channel, bob);
            when(messageRepository.findByIdWithChannelAndAuthor(100L)).thenReturn(Optional.of(message));
            when(messageRepository.findRepliesIncludingDeleted(message)).thenReturn(List.of(reply));
            when(attachmentRepository.findByMessageOrderByCreatedAtAsc(message))
                    .thenReturn(List.of(attachment(message, "own-key", 300L)));
            when(attachmentRepository.findByMessageOrderByCreatedAtAsc(reply))
                    .thenReturn(List.of(attachment(reply, "reply-key", 700L)));
            // Two instances, the inner standing in for the Spring transactional proxy: the credit
            // goes through `self` so it lands in a REQUIRES_NEW transaction rather than joining the
            // already-committed one. Same shape as MessageModerationRetentionPurgeTest.
            service = messageService(messageService(null));
        }

        private MessageService messageService(MessageService self) {
            return new MessageService(messageRepository, attachmentRepository,
                    mock(MessageReactionRepository.class), mock(MessageMentionRepository.class),
                    attachmentService, channelService, mock(MentionService.class), messageIndex,
                    mock(WritePathMetrics.class), mock(MessageWriteBehind.class), quotas, self);
        }
    }

    // ------------------------------------------------------------ ChannelService.destroy

    @Test
    void destroyingAChannelCreditsEveryoneWhoEverUploadedIntoIt() {
        var fixture = new ChannelFixture();

        fixture.service.destroy(fixture.channel, alice);

        // Unlike one message's attachments, a channel's belong to the whole membership; the map is
        // the only reason a destroy doesn't leave every past uploader charged forever.
        verify(fixture.quotas).releaseAll(Map.of(1L, 500L, 2L, 250L));
    }

    @Test
    void destroyReapsTheSameFilesItCreditsFor() {
        var fixture = new ChannelFixture();

        fixture.service.destroy(fixture.channel, alice);

        // Both halves come from the one pre-delete read: two queries could disagree if an upload
        // landed between them, and then a file is either reaped uncredited or credited unreaped.
        verify(fixture.attachmentService).deleteFiles(List.of("a-key", "b-key"));
    }

    /** ChannelService.destroy's collaborators, with two uploaders' files in the doomed channel. */
    private final class ChannelFixture {
        final ChannelRepository channelRepository = mock(ChannelRepository.class);
        final AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        final AttachmentService attachmentService = mock(AttachmentService.class);
        final StorageQuotaService quotas = mock(StorageQuotaService.class);
        final Channel channel = channel();
        final ChannelService service;

        ChannelFixture() {
            var memberRepository = mock(ChannelMemberRepository.class);
            when(memberRepository.findByChannelAndUser(channel, alice))
                    .thenReturn(Optional.of(new ChannelMember(channel, alice, ChannelRole.ADMIN)));
            when(attachmentRepository.findByChannelWithAuthor(channel)).thenReturn(List.of(
                    attachment(message(1L, channel, alice), "a-key", 500L),
                    attachment(message(2L, channel, bob), "b-key", 250L)));
            // Inner instance stands in for the transactional proxy behind `self` — see MessageFixture.
            service = channelService(memberRepository, channelService(memberRepository, null));
        }

        private ChannelService channelService(ChannelMemberRepository memberRepository,
                                              ChannelService self) {
            return new ChannelService(channelRepository, memberRepository,
                    mock(MessageRepository.class), attachmentRepository,
                    mock(MessageIndexService.class), attachmentService,
                    new ChannelAccessCache(60, 1024), mock(AppSettingsService.class),
                    new RateLimiter(), quotas);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Attachment attachment(Message message, String storageKey, long sizeBytes) {
        return new Attachment(message, "file.bin", "application/octet-stream", sizeBytes, storageKey);
    }

    private static Message message(long id, Channel channel, User author) {
        var message = new Message(channel, author, "body");
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private Channel channel() {
        var channel = new Channel("general", "General", null, ChannelType.PUBLIC, alice);
        ReflectionTestUtils.setField(channel, "id", 10L);
        return channel;
    }

    private static User user(long id, String username) {
        var user = new User("sub-" + username, username, username + "@example.test", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
