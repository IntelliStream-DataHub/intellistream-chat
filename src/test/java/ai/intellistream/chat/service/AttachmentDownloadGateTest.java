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
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.security.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The download gate on {@link AttachmentService#requireForDownload}.
 *
 * <p>Membership was the only check here until moderation landed, which left an attachment on a
 * removed message reachable by direct id — the message vanished from the feed and its photos kept
 * being served to anyone holding the URL. These tests pin the two halves of the rule that replaced
 * it: a removed message's files are not downloadable <em>by anyone</em>, and the removal is checked
 * before membership so it also holds on PUBLIC channels, where the membership check short-circuits.
 */
class AttachmentDownloadGateTest {

    private AttachmentRepository attachmentRepository;
    private ChannelService channelService;
    private AttachmentService service;

    private final User alice = user(1L, "alice");
    private final User bob = user(2L, "bob");

    @BeforeEach
    void setUp(@TempDir Path storageDir) {
        attachmentRepository = mock(AttachmentRepository.class);
        channelService = mock(ChannelService.class);
        service = new AttachmentService(attachmentRepository,
                mock(MessageRepository.class),
                channelService,
                mock(StorageQuotaService.class),
                mock(MessageService.class),
                storageDir.toString());
    }

    @Test
    void aLiveMessagesAttachmentIsReturnedOnceTheViewerMayReadTheChannel() {
        var channel = channel(ChannelType.PUBLIC);
        var attachment = attachmentOn(new Message(channel, alice, "here you go"));
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(attachment));

        assertThat(service.requireForDownload(7L, bob)).isSameAs(attachment);

        verify(channelService).requireMember(channel, bob);
    }

    @Test
    void anAttachmentOnARemovedMessageIsNotFound() {
        var message = new Message(channel(ChannelType.PUBLIC), alice, "regrettable");
        message.softDelete(bob);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(attachmentOn(message)));

        assertThatThrownBy(() -> service.requireForDownload(7L, bob))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theRemovalIsCheckedBeforeMembershipSoItAlsoBindsOnPublicChannels() {
        // requireMember short-circuits to "allowed" for PUBLIC, so an order swap here would leave
        // the gate open on precisely the channels with the widest audience.
        var message = new Message(channel(ChannelType.PUBLIC), alice, "regrettable");
        message.softDelete(bob);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(attachmentOn(message)));

        assertThatThrownBy(() -> service.requireForDownload(7L, bob))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(channelService, never()).requireMember(any(), any());
    }

    @Test
    void anAdminIsRefusedARemovedAttachmentTheSameAsAnyoneElse() {
        // Deliberate: the supported way for an admin to reach removed content is
        // MessageModerationService.restoreOne, which is audited. A silent admin-only download
        // would make the one access nobody can see afterwards the one aimed at removed content.
        var admin = user(3L, "root");
        admin.setAdmin(true);
        var message = new Message(channel(ChannelType.PRIVATE), alice, "regrettable");
        message.softDelete(admin);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(attachmentOn(message)));

        assertThatThrownBy(() -> service.requireForDownload(7L, admin))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aRemovedAttachmentReadsAsMissingRatherThanForbidden() {
        // 404 and not 403: the soft delete exists to make removal reversible, not to become an
        // oracle that tells a caller "this used to exist" about content that was taken down.
        var message = new Message(channel(ChannelType.PUBLIC), alice, "regrettable");
        message.softDelete(bob);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(attachmentOn(message)));

        assertThatThrownBy(() -> service.requireForDownload(7L, bob))
                .isNotInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Attachment not found: 7");
    }

    @Test
    void membershipIsStillEnforcedOnALiveMessage() {
        var channel = channel(ChannelType.PRIVATE);
        when(attachmentRepository.findById(7L))
                .thenReturn(Optional.of(attachmentOn(new Message(channel, alice, "private"))));
        doThrow(new AccessDeniedException("Not a member of this channel."))
                .when(channelService).requireMember(channel, bob);

        assertThatThrownBy(() -> service.requireForDownload(7L, bob))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnknownAttachmentIdIsNotFound() {
        when(attachmentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireForDownload(7L, bob))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static Attachment attachmentOn(Message message) {
        return new Attachment(message, "cat.png", "image/png", 1234L, "storage-key");
    }

    private Channel channel(ChannelType type) {
        var channel = new Channel("general", "General", null, type, alice);
        ReflectionTestUtils.setField(channel, "id", 10L);
        return channel;
    }

    private static User user(long id, String username) {
        var user = new User("sub-" + username, username, username + "@example.test", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
