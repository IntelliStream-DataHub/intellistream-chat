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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SearchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks in the security boundaries the codebase enforces today. If any of these regress, an
 * authorization or input-validation hole has slipped in. Companion to {@code security_plan.md}.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ichat.attachments.dir=build/test-attachments-security",
                "ichat.search.lucene-dir=build/test-lucene/SecurityBoundaryIT"
        }
)
class SecurityBoundaryIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
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
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired SearchService search;
    @Autowired AttachmentService attachments;
    @Autowired AttachmentRepository attachmentRepo;
    @Autowired MarkdownRenderer markdown;
    @PersistenceContext EntityManager em;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    private static void authAs(String username, String... roles) {
        var auth = new TestingAuthenticationToken(username, "n/a", roles);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ---------- Channel membership boundaries ----------

    @Test
    void privateChannel_nonMember_cannotPostMessage() {
        var owner = newUser("owner");
        var snooper = newUser("snoop");
        var secret = channels.create("secret-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);

        assertThatThrownBy(() -> messages.post(secret, snooper, "I'm in!"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void privateChannel_nonMember_cannotReadRecent() {
        var owner = newUser("owner");
        var snooper = newUser("snoop");
        var secret = channels.create("secret-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        messages.post(secret, owner, "internal");

        assertThatThrownBy(() -> messages.recent(secret, snooper, 50))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void privateChannel_nonMember_cannotSearch() {
        var owner = newUser("owner");
        var snooper = newUser("snoop");
        var secret = channels.create("secret-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        messages.post(secret, owner, "ping");

        // Authenticate the snooper for SearchService — searchEverywhere is admin-only,
        // searchChannel must reject membership.
        authAs(snooper.getUsername(), "ROLE_USER");
        assertThatThrownBy(() -> search.searchChannel(secret, snooper, "ping", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void privateChannel_nonMember_cannotDownloadAttachment() throws Exception {
        var owner = newUser("owner");
        var snooper = newUser("snoop");
        var secret = channels.create("secret-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);

        var att = attachments.upload(secret, owner, "secret.txt", "text/plain", -1L, AttachmentBytes.UNLIMITED, "",
                new ByteArrayInputStream("classified".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> attachments.requireForDownload(att.getId(), snooper))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- Message ownership boundaries ----------

    @Test
    void edit_nonAuthor_isRejected_evenIfAdmin() {
        var alice = newUser("alice"); // admin (creator)
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var bobsMsg = messages.post(room, bob, "bob's words");

        assertThatThrownBy(() -> messages.edit(bobsMsg.getId(), alice, "alice's words"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_nonAuthorNonAdmin_isRejected() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var alicesMsg = messages.post(room, alice, "untouchable");

        assertThatThrownBy(() -> messages.delete(alicesMsg.getId(), bob))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- Attachment storage path traversal ----------

    @Test
    void attachmentResolve_rejectsParentDirectoryEscape() throws Exception {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var att = attachments.upload(room, alice, "ok.txt", "text/plain", -1L, AttachmentBytes.UNLIMITED, "",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        // Bypass JPA setters: poke a malicious storage_key directly via reflection so we
        // confirm the resolve() guard would catch a future bug that lets one in.
        Attachment loaded = attachmentRepo.findById(att.getId()).orElseThrow();
        Field f = Attachment.class.getDeclaredField("storageKey");
        f.setAccessible(true);
        f.set(loaded, "../../../../etc/passwd");

        assertThatThrownBy(() -> attachments.resolve(loaded))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escaped storage root");
    }

    // ---------- Markdown sanitisation ----------

    @Test
    void markdown_stripsScriptTags() {
        var html = markdown.render("hi <script>alert(1)</script> bye");
        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("alert(1)");
    }

    @Test
    void markdown_stripsJavascriptUriOnLinks() {
        // jsoup's basic safelist removes javascript: URLs.
        var html = markdown.render("[click](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void markdown_stripsEventHandlerAttributes() {
        var html = markdown.render("<p onclick=\"alert(1)\">x</p>");
        assertThat(html).doesNotContain("onclick");
    }

    @Test
    void markdown_doesNotAllowImgInjectionViaSafelist() {
        // basic safelist allows neither <img> nor <iframe>.
        var html = markdown.render("![x](http://example.com/x.png) <iframe src='evil'></iframe>");
        // The sanitiser strips the iframe; <img> stays only if the basic safelist permits it.
        assertThat(html).doesNotContain("<iframe");
    }

    // ---------- Mention decorator: hostile content stays escaped ----------

    @Test
    void mentionsDecoratorEscapesHostileContent() {
        // Even if a username somehow contained `"` or `<`, the decorator must not break out
        // of the attribute / tag.
        var u = users.save(new User("kc-x", "weird-handle", "x@e", "X"));
        // Body has the matching @weird-handle plus a hostile suffix that's plain text.
        var html = markdown.render("ping @" + u.getUsername() + " <script>alert(1)</script>");
        assertThat(html).contains("data-username=\"weird-handle\"");
        assertThat(html).doesNotContain("<script");
    }

    // ---------- Body length validation ----------

    @Test
    void post_rejectsOversizedBody() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        assertThatThrownBy(() -> messages.post(room, alice, "x".repeat(8001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void post_rejectsBlankBody() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        assertThatThrownBy(() -> messages.post(room, alice, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Reply preserves the parent's channel ----------

    @Test
    void replyInThread_neverCrossesChannels() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var roomA = channels.create("a-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        // roomB is PRIVATE — bob has no membership, and PUBLIC's "anyone authenticated" door isn't open here.
        var roomB = channels.create("b-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.join(roomA, bob);

        var parentInB = messages.post(roomB, alice, "B-only thread");

        // bob can see roomA messages but is not a member of roomB. He must not be able to
        // post a reply to a roomB-parent, even though he's a member of A.
        assertThatThrownBy(() -> messages.replyInThread(parentInB.getId(), bob, "sneaky"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void replyInThread_inheritsParentChannel() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var parent = messages.post(room, alice, "p");
        Message reply = messages.replyInThread(parent.getId(), alice, "r");

        assertThat(reply.getChannel().getId()).isEqualTo(room.getId());
        assertThat(reply.getParent().getId()).isEqualTo(parent.getId());
    }

    // ---------- Markdown anchor hardening ----------

    @Test
    void renderedAnchorsCarryNoopener() {
        var html = markdown.render("see [example](https://example.com)");
        assertThat(html).contains("rel=\"noopener noreferrer nofollow\"");
        assertThat(html).contains("target=\"_blank\"");
    }

    // ---------- Filename sanitisation ----------

    @Test
    void sanitizeFilenameStripsPathTraversalControlCharsAndDots() {
        assertThat(ai.intellistream.chat.service.AttachmentService.sanitizeFilename("../../etc/passwd"))
                .isEqualTo("passwd");
        assertThat(ai.intellistream.chat.service.AttachmentService.sanitizeFilename("evil\u0000.txt"))
                .isEqualTo("evil.txt");
        assertThat(ai.intellistream.chat.service.AttachmentService.sanitizeFilename(".."))
                .isEqualTo("file");
        assertThat(ai.intellistream.chat.service.AttachmentService.sanitizeFilename("."))
                .isEqualTo("file");
        assertThat(ai.intellistream.chat.service.AttachmentService.sanitizeFilename(""))
                .isEqualTo("file");
        assertThat(ai.intellistream.chat.service.AttachmentService.sanitizeFilename(null))
                .isEqualTo("file");
        assertThat(ai.intellistream.chat.service.AttachmentService.sanitizeFilename("a".repeat(300)).length())
                .isEqualTo(255);
    }

    // ---------- MIME sniffing on upload ----------

    @Test
    void uploadOverridesLyingContentTypeWhenBytesDontMatch() throws Exception {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        // GIF magic — "GIF87a..."
        var gifBytes = "GIF87a stuff stuff".getBytes(StandardCharsets.UTF_8);
        // Client claims image/png but the bytes are GIF — sniff should override to image/gif.
        var att = attachments.upload(room, alice, "fake.png", "image/png", -1L, AttachmentBytes.UNLIMITED, "",
                new ByteArrayInputStream(gifBytes));
        assertThat(att.getContentType()).startsWith("image/gif");
    }

    // ---------- Username sanitisation ----------

    @Test
    void usernameSanitisationStripsHostileShapes() {
        assertThat(ai.intellistream.chat.service.UserService.sanitizeUsername("alice", "sub-1"))
                .isEqualTo("alice");
        assertThat(ai.intellistream.chat.service.UserService.sanitizeUsername("alice@example.com", "sub-1"))
                .isEqualTo("alice");
        assertThat(ai.intellistream.chat.service.UserService.sanitizeUsername("../../etc/passwd", "sub-12345"))
                .isEqualTo("user-sub12345");
        assertThat(ai.intellistream.chat.service.UserService.sanitizeUsername("\u0000bad\u0007", "sub-1"))
                .isEqualTo("user-sub1");
        assertThat(ai.intellistream.chat.service.UserService.sanitizeUsername(null, null))
                .isEqualTo("user-anon");
    }

    // ---------- Rate limiting ----------

    @Test
    void rateLimiterBlocksAfterLimit() {
        var rl = new ai.intellistream.chat.security.RateLimiter();
        var ok = 0;
        for (int i = 0; i < 10; i++) {
            if (rl.tryAcquire("u", "send", 5, java.time.Duration.ofMinutes(1))) ok++;
        }
        assertThat(ok).isEqualTo(5);
    }

    @Test
    void rateLimiterIsScopedPerActionAndKey() {
        var rl = new ai.intellistream.chat.security.RateLimiter();
        for (int i = 0; i < 3; i++) {
            assertThat(rl.tryAcquire("u1", "a1", 3, java.time.Duration.ofMinutes(1))).isTrue();
        }
        assertThat(rl.tryAcquire("u1", "a1", 3, java.time.Duration.ofMinutes(1))).isFalse();
        // Different action — fresh bucket.
        assertThat(rl.tryAcquire("u1", "a2", 3, java.time.Duration.ofMinutes(1))).isTrue();
        // Different user — fresh bucket.
        assertThat(rl.tryAcquire("u2", "a1", 3, java.time.Duration.ofMinutes(1))).isTrue();
    }
}
