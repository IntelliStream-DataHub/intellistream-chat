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

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.security.UploadTooLargeException;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MentionService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.AttachmentRestController;
import ai.intellistream.chat.web.UserRestController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the security boundaries that exist specifically because the app is exposed
 * on the public internet — anything an authenticated attacker (or one who briefly
 * obtains a single valid session) might try to exploit.
 *
 * <p>Companion to {@code SecurityBoundaryIT}; that file covers the auth/authz invariants
 * the codebase has always asserted. This file covers the additions made for internet
 * exposure: per-user upload caps via Keycloak claim, GET rate limits against
 * enumeration / bandwidth DoS, Lucene wildcard refusal.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class InternetExposureSecurityIT {

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
    @Autowired UserService userService;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired AttachmentService attachments;
    @Autowired ConversationService conversations;
    @Autowired ConversationAttachmentService convAttachments;
    @Autowired MarkdownRenderer markdown;
    @Autowired SearchService search;
    @Autowired MentionService mentionService;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private RateLimiter rateLimiter;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        rateLimiter = new RateLimiter();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-int-" + prefix + i, prefix + "-" + i,
                prefix + i + "@e", prefix + " " + i));
    }

    // ---------- Per-user upload cap (Keycloak claim) ----------

    @Test
    void uploadCap_defaultsTo50MiB_whenNoClaim() {
        // OIDC/JWT not present in the test principal → CurrentUser falls back to default.
        var bareCurrent = new CurrentUser(userService);
        var dummy = new TestingAuthenticationToken("anon", "n/a", "ROLE_USER");
        dummy.setAuthenticated(true);

        var cap = bareCurrent.uploadCapBytes(dummy);

        assertThat(cap).isEqualTo(AttachmentBytes.DEFAULT_MAX_BYTES);
        assertThat(cap).isEqualTo(50L * 1024 * 1024);
    }

    @Test
    void uploadCap_admin_isUnlimited() {
        var bareCurrent = new CurrentUser(userService);
        var admin = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");
        admin.setAuthenticated(true);

        assertThat(bareCurrent.uploadCapBytes(admin)).isEqualTo(AttachmentBytes.UNLIMITED);
    }

    @Test
    void uploadCap_jwtClaim_overridesDefault() {
        var bareCurrent = new CurrentUser(userService);
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("kc-foo")
                .claim("preferred_username", "foo")
                .claim("chat_max_upload_bytes", 200L * 1024 * 1024)  // 200 MiB
                .build();
        var auth = new JwtAuthenticationToken(jwt);

        assertThat(bareCurrent.uploadCapBytes(auth)).isEqualTo(200L * 1024 * 1024);
    }

    @Test
    void uploadCap_jwtClaimAsString_alsoParses() {
        // Keycloak serialises long claims as strings under some mapper configs — both must work.
        var bareCurrent = new CurrentUser(userService);
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("kc-bar").claim("preferred_username", "bar")
                .claim("chat_max_upload_bytes", "104857600")  // 100 MiB as string
                .build();
        var auth = new JwtAuthenticationToken(jwt);

        assertThat(bareCurrent.uploadCapBytes(auth)).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    void channelUpload_oversizedUnderCap_throwsUploadTooLarge() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var smallCap = 1024L; // 1 KiB cap to keep the test fast

        // Streaming throw: 4 KiB of bytes, cap at 1 KiB
        var oversize = new java.io.ByteArrayInputStream(new byte[4096]);

        assertThatThrownBy(() -> attachments.upload(
                room, alice, "big.bin", "application/octet-stream", -1L, smallCap, "", oversize))
                .isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void channelUpload_declaredOversize_rejectedBeforeStreaming() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var cap = 1024L;
        // Pretend the client says it's 5 KiB — service should refuse before reading the stream.
        var anyStream = new java.io.ByteArrayInputStream(new byte[1]);

        assertThatThrownBy(() -> attachments.upload(
                room, alice, "claim.bin", "application/octet-stream", 5 * 1024L, cap, "", anyStream))
                .isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void dmUpload_oversizedUnderCap_throwsUploadTooLarge() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var smallCap = 1024L;
        var oversize = new java.io.ByteArrayInputStream(new byte[4096]);

        assertThatThrownBy(() -> convAttachments.upload(
                conv, alice, "big.bin", "application/octet-stream", -1L, smallCap, "", oversize))
                .isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void uploadUnderCap_succeeds() throws Exception {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var ok = attachments.upload(
                room, alice, "small.txt", "text/plain", -1L, 1024L, "",
                new ByteArrayInputStream("hi".getBytes()));
        assertThat(ok.getId()).isNotNull();
        assertThat(ok.getSizeBytes()).isEqualTo(2L);
    }

    // ---------- GET rate limits (anti-enumeration, anti-bandwidth-DoS) ----------

    @Test
    void userProfileLookup_isRateLimited() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var controller = new UserRestController(userService, currentUser, rateLimiter,
                channels, conversations, mentionService);

        // 120/min is the configured cap. Hammer past it and assert the 121st throws.
        for (int i = 0; i < 120; i++) {
            controller.profile(bob.getUsername(), mock(Principal.class));
        }
        assertThatThrownBy(() -> controller.profile(bob.getUsername(), mock(Principal.class)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void userProfileLookup_perUserKeysAreIndependent() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var controller = new UserRestController(userService, currentUser, rateLimiter,
                channels, conversations, mentionService);

        // Alice exhausts her quota looking up carol.
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        for (int i = 0; i < 120; i++) {
            controller.profile(carol.getUsername(), mock(Principal.class));
        }
        // Bob's quota is unaffected — different rate-limit key.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        controller.profile(carol.getUsername(), mock(Principal.class)); // no throw
    }

    /**
     * The @-mention typeahead answers prefix queries, so an unbounded one is a name-enumeration
     * tool. Its budget is its own action (a typeahead would drain the 20/min {@code user-lookup}
     * budget mid-sentence) but it is still a budget.
     */
    @Test
    void mentionCandidates_isRateLimited() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var controller = new UserRestController(userService, currentUser, rateLimiter,
                channels, conversations, mentionService);

        for (int i = 0; i < 120; i++) {
            controller.mentionCandidates(room.getId(), null, "ali", 8, mock(Principal.class));
        }
        assertThatThrownBy(() ->
                controller.mentionCandidates(room.getId(), null, "ali", 8, mock(Principal.class)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    // ---------- Lucene wildcard refusal ----------

    @Test
    void search_userSuppliedWildcard_returnsNoResultsWithoutFanout() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        // Plant several distinct terms so a runaway `*` would otherwise match many.
        messages.post(room, alice, "alpha");
        messages.post(room, alice, "alphabet");
        messages.post(room, alice, "altitude");
        messages.post(room, alice, "anything");

        // Authenticate alice for SearchService's authorization checks.
        var auth = new TestingAuthenticationToken(alice.getUsername(), "n/a", "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Trailing wildcard — would be a TermsEnumeration in stock Lucene QueryParser.
        var hits = search.searchChannel(room, alice, "a*", 50);
        // Refused at the parser level → no results, no DoS.
        assertThat(hits).isEmpty();

        // Also reject naked-prefix and regex syntax.
        assertThat(search.searchChannel(room, alice, "alph?", 50)).isEmpty();
        assertThat(search.searchChannel(room, alice, "/al.+/", 50)).isEmpty();
    }

    @Test
    void search_normalQueryStillWorks() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        messages.post(room, alice, "the quick brown fox");

        var auth = new TestingAuthenticationToken(alice.getUsername(), "n/a", "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        var hits = search.searchChannel(room, alice, "brown", 10);
        assertThat(hits).hasSize(1);
    }
}
