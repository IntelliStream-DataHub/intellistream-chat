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

package ai.intellistream.threadorbit.integration;

import ai.intellistream.threadorbit.attachments.AttachmentBytes;
import ai.intellistream.threadorbit.domain.ChannelType;
import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.repository.AttachmentRepository;
import ai.intellistream.threadorbit.repository.MessageRepository;
import ai.intellistream.threadorbit.repository.UserRepository;
import ai.intellistream.threadorbit.service.AttachmentService;
import ai.intellistream.threadorbit.service.ChannelService;
import ai.intellistream.threadorbit.service.MessageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "threadorbit.attachments.dir=build/test-attachments-edit-delete"
)
@Transactional
class MessageEditDeleteReplyIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired MessageRepository messageRepo;
    @Autowired AttachmentService attachments;
    @Autowired AttachmentRepository attachmentRepo;
    @PersistenceContext EntityManager em;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    // ---------- Edit ----------

    @Test
    void authorCanEditOwnMessage() {
        var alice = newUser("alice");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var posted = messages.post(room, alice, "first version");
        em.flush();

        var edited = messages.edit(posted.getId(), alice, "second version");

        assertThat(edited.getBodyMarkdown()).isEqualTo("second version");
        assertThat(edited.getEditedAt()).isNotNull();
    }

    @Test
    void nonAuthorCannotEditEvenAdmin() {
        var alice = newUser("alice");      // channel admin
        var bob = newUser("bob");          // member who posts
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var posted = messages.post(room, bob, "bob writes");
        em.flush();

        // Alice is admin but not the author — must still be rejected.
        assertThatThrownBy(() -> messages.edit(posted.getId(), alice, "alice meddles"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void editRejectsBlankAndOversizedBody() {
        var alice = newUser("alice");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var posted = messages.post(room, alice, "anything");
        em.flush();

        assertThatThrownBy(() -> messages.edit(posted.getId(), alice, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.edit(posted.getId(), alice, "x".repeat(8001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Delete ----------

    @Test
    void authorCanDeleteOwnMessage() {
        var alice = newUser("alice");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var posted = messages.post(room, alice, "byebye");
        em.flush();

        var summary = messages.delete(posted.getId(), alice);
        em.flush();

        assertThat(summary.id()).isEqualTo(posted.getId());
        assertThat(summary.channelId()).isEqualTo(room.getId());
        assertThat(summary.parentId()).isNull();
        assertThat(messageRepo.findById(posted.getId())).isEmpty();
    }

    @Test
    void channelAdminCanDeleteAnyMessage() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var bobsMsg = messages.post(room, bob, "made by bob");
        em.flush();

        messages.delete(bobsMsg.getId(), alice);
        em.flush();

        assertThat(messageRepo.findById(bobsMsg.getId())).isEmpty();
    }

    @Test
    void nonAuthorNonAdminCannotDelete() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var alicesMsg = messages.post(room, alice, "untouchable");
        em.flush();

        assertThatThrownBy(() -> messages.delete(alicesMsg.getId(), bob))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(messageRepo.findById(alicesMsg.getId())).isPresent();
    }

    @Test
    void deletingParentCascadesToThreadReplies() {
        var alice = newUser("alice");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var parent = messages.post(room, alice, "parent");
        var r1 = messages.replyInThread(parent.getId(), alice, "reply 1");
        var r2 = messages.replyInThread(parent.getId(), alice, "reply 2");
        em.flush();

        messages.delete(parent.getId(), alice);
        em.flush();

        assertThat(messageRepo.findById(parent.getId())).isEmpty();
        assertThat(messageRepo.findById(r1.getId())).isEmpty();
        assertThat(messageRepo.findById(r2.getId())).isEmpty();
    }

    @Test
    void deletingMessageCleansUpAttachmentFiles() throws Exception {
        var alice = newUser("alice");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        var attachment = attachments.upload(
                room, alice, "hello.txt", "text/plain", -1L, AttachmentBytes.UNLIMITED, "caption",
                new ByteArrayInputStream("payload bytes".getBytes(StandardCharsets.UTF_8)));
        em.flush();

        var path = attachments.resolve(attachment);
        var attachmentId = attachment.getId();
        var messageId = attachment.getMessage().getId();
        assertThat(Files.exists(path)).isTrue();

        // delete() defers file deletion to afterCommit so a rolled-back DB delete
        // never strands the file. Tx.commit() flushes the test tx so the hook fires.
        messages.delete(messageId, alice);
        Tx.commit();

        assertThat(Files.exists(path)).isFalse();
        assertThat(attachmentRepo.findById(attachmentId)).isEmpty();
    }

    // ---------- Thread reply ----------

    @Test
    void replyInThreadIsVisibleViaThreadReplies() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        var parent = messages.post(room, alice, "let's chat");
        var reply1 = messages.replyInThread(parent.getId(), bob, "yes!");
        var reply2 = messages.replyInThread(parent.getId(), alice, "great");
        em.flush();

        var replies = messages.threadReplies(parent.getId(), bob);
        assertThat(replies).extracting(m -> m.getId()).containsExactly(reply1.getId(), reply2.getId());

        // recent() shows only top-level messages — replies are excluded from the channel timeline.
        var top = messages.recent(room, bob, 50);
        assertThat(top).extracting(m -> m.getId()).containsExactly(parent.getId());
    }

    @Test
    void cannotReplyToAReply() {
        var alice = newUser("alice");
        var room = channels.create("general-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var parent = messages.post(room, alice, "hi");
        var reply = messages.replyInThread(parent.getId(), alice, "first");
        em.flush();

        assertThatThrownBy(() -> messages.replyInThread(reply.getId(), alice, "nested"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonMemberCannotReplyToPrivateThread() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var secret = channels.create("secret-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        var parent = messages.post(secret, alice, "internal");
        em.flush();

        assertThatThrownBy(() -> messages.replyInThread(parent.getId(), bob, "spy"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
