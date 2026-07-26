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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A file posted in a conversation is readable only by that conversation's members.
 *
 * <p>This is the rule people actually mean when they say a direct message is private: not just
 * that the text is unreachable, but that the file attached to it is too. The text is protected by
 * the message endpoints; the file has its own URL, and a URL is the thing that gets forwarded,
 * pasted into a ticket, or guessed at.
 *
 * <p>Every refusal here is the SAME refusal — {@link NoSuchElementException}, which the controller
 * renders as 404. "That file exists but is not yours" and "no such file" have to be
 * indistinguishable, or a stranger can enumerate ids to learn what a workspace has been sharing.
 *
 * <p>The third test pins the load-bearing detail: membership is evaluated against the
 * conversation that OWNS the attachment, never the one named in the URL. Verified by mutation —
 * swapping those two makes it fail, which is the realistic mistake, because the resulting code
 * still has a membership check sitting right there and reads as correct.
 *
 * <p>Deleting the URL-consistency check alone does NOT make it fail, and that is worth knowing
 * rather than assuming: the two checks overlap on purpose. The id in the URL is a consistency
 * assertion, not the thing access is decided on.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationAttachmentAccessIT {

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
    @Autowired ConversationService conversations;
    @Autowired ConversationAttachmentService attachments;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-att-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n));
    }

    private ConversationAttachment upload(Conversation conversation, User uploader, String name)
            throws IOException {
        return attachments.upload(conversation, uploader, name, "application/octet-stream", 64,
                AttachmentBytes.DEFAULT_MAX_BYTES, "", new ByteArrayInputStream(new byte[64]));
    }

    @Test
    void aParticipantCanDownloadAFileFromTheirOwnConversation() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var file = upload(dm, alice, "contract.pdf");

        // The control. Without it, the refusals below also pass when uploads are broken.
        assertThat(attachments.requireForDownload(dm.getId(), file.getId(), bob))
                .describedAs("the other participant can read it")
                .isNotNull();
        assertThat(attachments.requireForDownload(dm.getId(), file.getId(), alice)).isNotNull();
    }

    @Test
    void anOutsiderCannotDownloadAFileFromAConversationTheyAreNotIn() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var outsider = newUser("outsider");
        var dm = conversations.directBetween(alice, bob);
        var file = upload(dm, alice, "contract.pdf");

        assertThatThrownBy(() -> attachments.requireForDownload(dm.getId(), file.getId(), outsider))
                .describedAs("a file in someone else's conversation is not readable")
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anAttachmentIdCannotBeSmuggledThroughAConversationYouAreIn() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var outsider = newUser("outsider");

        var theirs = conversations.directBetween(alice, bob);
        var secret = upload(theirs, alice, "salaries.xlsx");
        // The outsider has a perfectly legitimate conversation of their own.
        var mine = conversations.directBetween(outsider, alice);

        // Membership holds for `mine` and not for `theirs`. Evaluating it against the URL's
        // conversation would therefore pass and hand over somebody else's file.
        assertThatThrownBy(() -> attachments.requireForDownload(mine.getId(), secret.getId(), outsider))
                .describedAs("the attachment must belong to the conversation named in the URL")
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aMissingFileAndAForbiddenOneAreIndistinguishable() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var outsider = newUser("outsider");
        var dm = conversations.directBetween(alice, bob);
        var file = upload(dm, alice, "contract.pdf");
        var mine = conversations.directBetween(outsider, alice);

        // Same exception type for a file that does not exist and one that exists but is not
        // theirs. Anything that distinguishes them turns an id scan into an inventory.
        var forbidden = catchType(() -> attachments.requireForDownload(dm.getId(), file.getId(), outsider));
        var absent = catchType(() -> attachments.requireForDownload(mine.getId(), 987_654_321L, outsider));
        assertThat(forbidden).isEqualTo(absent).isEqualTo(NoSuchElementException.class);
    }

    private static Class<?> catchType(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e.getClass();
        }
    }
}
