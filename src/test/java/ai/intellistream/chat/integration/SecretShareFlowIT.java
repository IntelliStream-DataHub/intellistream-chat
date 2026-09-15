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
import ai.intellistream.chat.domain.SecretAudience;
import ai.intellistream.chat.domain.SecretShare;
import ai.intellistream.chat.domain.SecretState;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.SecretShareRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.secretshare.SecretShareService;
import ai.intellistream.chat.secretshare.SecretShareService.Outcome;
import ai.intellistream.chat.secretshare.SecretShareSweeper;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.service.AppSettingsService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.web.SecretShareRestController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One-time secrets against a real Postgres: the schema's constraints, the row lock that makes
 * "opens exactly once" true under concurrency, the receipt in the creator's own conversation, and
 * the sweeper.
 *
 * <p>The server never decrypts, so these tests do not encrypt: a payload is random bytes of the
 * right shape and a verifier is 32 random bytes. What the browser does with them is covered where a
 * browser runs.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SecretShareFlowIT {

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
        registry.add("ichat.secrets.max-waiting-per-user", () -> "5");
        registry.add("ichat.secrets.display-seconds", () -> "120");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired SecretShareService secrets;
    @Autowired SecretShareRepository repo;
    @Autowired SecretShareSweeper sweeper;
    @Autowired AppSettingsService settings;
    @Autowired ConversationService conversations;
    @Autowired ConversationMessageRepository convMessages;
    @Autowired SimpMessagingTemplate beanBroker;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration HOUR = Duration.ofHours(1);

    @BeforeEach
    void resetBroker() {
        reset(beanBroker);
    }

    @AfterEach
    void restoreSettings() {
        settings.setAllowPublicSecrets(true);
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-secret-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What a browser would send: a well-formed payload and a verifier, plus the raw ciphertext for comparison. */
    private record Sealed(String payload, String verifier, byte[] payloadBytes) {
        static Sealed random() {
            var payload = new byte[1 + 12 + 16 + 24];
            RANDOM.nextBytes(payload);
            payload[0] = 0x01;
            var verifier = new byte[32];
            RANDOM.nextBytes(verifier);
            return new Sealed(b64(payload), b64(verifier), payload);
        }
    }

    private static String otherVerifier() {
        var v = new byte[32];
        RANDOM.nextBytes(v);
        return b64(v);
    }

    private SecretShareService.Created create(User creator, Sealed sealed, String label, SecretAudience audience) {
        return secrets.create(creator, sealed.payload(), sealed.verifier(), label, HOUR, audience, Instant.now());
    }

    private Conversation selfConversation(User user) {
        return conversations.listForUser(user).stream()
                .filter(Conversation::isSelfDirect)
                .findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- the one-time open ----

    @Test
    void aSecretIsPreviewedThenOpenedOnceAndTheCreatorGetsAReceipt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var sealed = Sealed.random();
        var created = create(alice, sealed, "Staging DB", SecretAudience.SIGNED_IN);
        assertThat(created.publicId()).matches("[A-Za-z0-9_-]{22}");

        var preview = secrets.status(created.publicId(), sealed.verifier(), true, Instant.now());
        assertThat(preview).isInstanceOfSatisfying(Outcome.Ok.class, ok -> {
            var p = (SecretShareService.Preview) ok.value();
            assertThat(p.creatorName()).isEqualTo(alice.getDisplayName());
            assertThat(p.displaySeconds()).isEqualTo(120);
        });
        // Asking twice consumes nothing.
        assertThat(secrets.status(created.publicId(), sealed.verifier(), true, Instant.now())).isInstanceOf(Outcome.Ok.class);

        var opened = secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.signedIn(bob), Instant.now());
        assertThat(opened).isInstanceOfSatisfying(Outcome.Ok.class, ok ->
                assertThat(((SecretShareService.Opened) ok.value()).payload()).isEqualTo(sealed.payloadBytes()));

        var again = secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.signedIn(bob), Instant.now());
        assertThat(again).isInstanceOfSatisfying(Outcome.Gone.class, gone -> {
            assertThat(gone.state()).isEqualTo(SecretState.OPENED);
            assertThat(gone.endedAt()).isNotNull();
        });
        assertThat(secrets.status(created.publicId(), sealed.verifier(), true, Instant.now())).isInstanceOf(Outcome.Gone.class);

        // The ciphertext is gone from the row itself, not only from the answers.
        assertThat(jdbc.queryForObject("select payload is null from secret_shares where public_id = ?",
                Boolean.class, created.publicId())).isTrue();

        // Receipt: in alice's own conversation, unread, naming bob, and announced to her alone.
        var self = selfConversation(alice);
        assertThat(convMessages.findByConversationOrderByCreatedAtDesc(self, PageRequest.of(0, 5)))
                .singleElement()
                .satisfies(m -> assertThat(m.getBodyMarkdown())
                        .contains("“Staging DB”").contains("@" + bob.getUsername()));
        assertThat(conversations.unreadCounts(alice, List.of(self.getId()))).containsEntry(self.getId(), 1L);
        verify(beanBroker).convertAndSendToUser(eq(alice.getUsername()), eq("/queue/conversation-alerts"), any(Object.class));
        verify(beanBroker, never()).convertAndSendToUser(eq(bob.getUsername()), any(String.class), any(Object.class));

        var row = secrets.list(alice, 0, Instant.now()).getContent().getFirst();
        assertThat(row.state()).isEqualTo(SecretState.OPENED);
        assertThat(row.openedByName()).isEqualTo(bob.getUsername());
        assertThat(row.openedAnonymously()).isFalse();
        assertThat(row.openedIp()).isNull();
    }

    @Test
    void aWrongVerifierAndAnUnknownIdGetTheSameAnswerAndConsumeNothing() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.SIGNED_IN);
        var now = Instant.now();

        assertThat(secrets.open(created.publicId(), otherVerifier(), SecretShare.Opener.signedIn(bob), now))
                .isInstanceOf(Outcome.NotFound.class);
        assertThat(secrets.open(created.publicId(), null, SecretShare.Opener.signedIn(bob), now))
                .isInstanceOf(Outcome.NotFound.class);
        assertThat(secrets.open(created.publicId(), "garbage", SecretShare.Opener.signedIn(bob), now))
                .isInstanceOf(Outcome.NotFound.class);
        assertThat(secrets.status(created.publicId(), otherVerifier(), true, now)).isInstanceOf(Outcome.NotFound.class);
        assertThat(secrets.open("ZZZZZZZZZZZZZZZZZZZZZZ", sealed.verifier(), SecretShare.Opener.signedIn(bob), now))
                .isInstanceOf(Outcome.NotFound.class);
        assertThat(secrets.open("../../etc/passwd", sealed.verifier(), SecretShare.Opener.signedIn(bob), now))
                .isInstanceOf(Outcome.NotFound.class);

        // Still there for the person who has the key.
        assertThat(secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.signedIn(bob), now))
                .isInstanceOf(Outcome.Ok.class);
    }

    @Test
    void anEndedSecretIsReportedGoneOnlyToSomeoneWithTheKey() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.SIGNED_IN);
        secrets.revoke(alice, created.publicId(), Instant.now());

        assertThat(secrets.status(created.publicId(), otherVerifier(), true, Instant.now())).isInstanceOf(Outcome.NotFound.class);
        assertThat(secrets.status(created.publicId(), sealed.verifier(), true, Instant.now()))
                .isInstanceOfSatisfying(Outcome.Gone.class, g -> assertThat(g.state()).isEqualTo(SecretState.REVOKED));
    }

    @Test
    void twoConcurrentOpensReleaseThePayloadExactlyOnce() throws Exception {
        var alice = newUser("alice");
        var openers = List.of(newUser("bob"), newUser("carol"), newUser("dave"), newUser("erin"));
        var pool = Executors.newFixedThreadPool(openers.size());
        try {
            for (int round = 0; round < 4; round++) {
                var sealed = Sealed.random();
                var created = create(alice, sealed, "race " + round, SecretAudience.SIGNED_IN);
                var start = new CountDownLatch(1);
                var futures = new ArrayList<Future<Outcome<SecretShareService.Opened>>>();
                for (var opener : openers) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return secrets.open(created.publicId(), sealed.verifier(),
                                SecretShare.Opener.signedIn(opener), Instant.now());
                    }));
                }
                start.countDown();
                int ok = 0;
                int gone = 0;
                for (var f : futures) {
                    var outcome = f.get(30, TimeUnit.SECONDS);
                    if (outcome instanceof Outcome.Ok) ok++;
                    if (outcome instanceof Outcome.Gone) gone++;
                }
                assertThat(ok).as("round %d payload releases", round).isEqualTo(1);
                assertThat(gone).isEqualTo(openers.size() - 1);
            }
        } finally {
            pool.shutdownNow();
        }
        // One receipt per secret, not one per racer.
        assertThat(convMessages.findByConversationOrderByCreatedAtDesc(selfConversation(alice), PageRequest.of(0, 20)))
                .hasSize(4);
    }

    // ---------------------------------------------------------------- audience ----

    @Test
    void aSignedInSecretRefusesASignedOutOpenerWithoutConsumingIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.SIGNED_IN);

        assertThat(secrets.status(created.publicId(), sealed.verifier(), false, Instant.now()))
                .isInstanceOf(Outcome.SignInRequired.class);
        assertThat(secrets.open(created.publicId(), sealed.verifier(),
                SecretShare.Opener.anonymous("203.0.113.7", "Firefox on Windows"), Instant.now()))
                .isInstanceOf(Outcome.SignInRequired.class);

        assertThat(secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.signedIn(bob), Instant.now()))
                .isInstanceOf(Outcome.Ok.class);
    }

    @Test
    void anAnonymousOpenIsRecordedByAddressAndBrowser() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var created = create(alice, sealed, "for the contractor", SecretAudience.ANYONE);

        assertThat(secrets.open(created.publicId(), sealed.verifier(),
                SecretShare.Opener.anonymous("203.0.113.7", "Firefox on Windows"), Instant.now()))
                .isInstanceOf(Outcome.Ok.class);

        var row = secrets.list(alice, 0, Instant.now()).getContent().getFirst();
        assertThat(row.openedAnonymously()).isTrue();
        assertThat(row.openedIp()).isEqualTo("203.0.113.7");
        assertThat(row.openedClient()).isEqualTo("Firefox on Windows");
        assertThat(row.openedByName()).isNull();
        assertThat(convMessages.findByConversationOrderByCreatedAtDesc(selfConversation(alice), PageRequest.of(0, 5)))
                .singleElement()
                .satisfies(m -> assertThat(m.getBodyMarkdown())
                        .contains("someone without an account")
                        // The permanent message must not outlive the retention promise for these.
                        .doesNotContain("203.0.113.7").doesNotContain("Firefox"));
    }

    @Test
    void theWorkspaceSwitchRefusesNewAnyoneSecretsAndMakesExistingOnesAskForSignIn() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var existing = create(alice, sealed, null, SecretAudience.ANYONE);

        settings.setAllowPublicSecrets(false);

        assertThatThrownBy(() -> create(alice, Sealed.random(), null, SecretAudience.ANYONE))
                .isInstanceOf(PublicBadRequestException.class);
        assertThat(secrets.open(existing.publicId(), sealed.verifier(),
                SecretShare.Opener.anonymous("203.0.113.7", null), Instant.now()))
                .isInstanceOf(Outcome.SignInRequired.class);
        assertThat(secrets.status(existing.publicId(), sealed.verifier(), true, Instant.now()))
                .isInstanceOfSatisfying(Outcome.Ok.class, ok ->
                        assertThat(((SecretShareService.Preview) ok.value()).audience()).isEqualTo(SecretAudience.SIGNED_IN));

        // Nothing was deleted: switching back restores the link.
        settings.setAllowPublicSecrets(true);
        assertThat(secrets.open(existing.publicId(), sealed.verifier(),
                SecretShare.Opener.anonymous("203.0.113.7", null), Instant.now()))
                .isInstanceOf(Outcome.Ok.class);
    }

    // ---------------------------------------------------------------- ends ----

    @Test
    void anExpiredSecretIsRefusedBeforeTheSweeperHasTouchedIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.SIGNED_IN);
        var later = Instant.now().plus(2, ChronoUnit.HOURS);

        assertThat(jdbc.queryForObject("select payload is not null from secret_shares where public_id = ?",
                Boolean.class, created.publicId())).isTrue();
        assertThat(secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.signedIn(bob), later))
                .isInstanceOfSatisfying(Outcome.Gone.class, g -> assertThat(g.state()).isEqualTo(SecretState.EXPIRED));
    }

    @Test
    void onlyTheCreatorCanRevokeAndRevokingStopsTheLink() {
        var alice = newUser("alice");
        var mallory = newUser("mallory");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.SIGNED_IN);

        assertThatThrownBy(() -> secrets.revoke(mallory, created.publicId(), Instant.now()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> secrets.revoke(mallory, "ZZZZZZZZZZZZZZZZZZZZZZ", Instant.now()))
                .isInstanceOf(ResourceNotFoundException.class);
        // Mallory cannot see it in her list either.
        assertThat(secrets.list(mallory, 0, Instant.now()).getContent()).isEmpty();

        var view = secrets.revoke(alice, created.publicId(), Instant.now());
        assertThat(view.state()).isEqualTo(SecretState.REVOKED);
        assertThat(secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.signedIn(mallory), Instant.now()))
                .isInstanceOf(Outcome.Gone.class);
        // Revoking an ended secret reports what happened rather than failing.
        assertThat(secrets.revoke(alice, created.publicId(), Instant.now()).state()).isEqualTo(SecretState.REVOKED);
    }

    @Test
    void theSweeperForgetsExpiredCiphertextThenDeletesTheRowAfterRetention() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.ANYONE);
        var afterExpiry = Instant.now().plus(2, ChronoUnit.HOURS);

        var first = sweeper.sweep(afterExpiry);
        assertThat(first.payloadsCleared()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select payload is null from secret_shares where public_id = ?",
                Boolean.class, created.publicId())).isTrue();
        assertThat(repo.findByPublicId(created.publicId())).get()
                .satisfies(s -> assertThat(s.state(afterExpiry)).isEqualTo(SecretState.EXPIRED));

        // Still inside retention: kept, so the creator can see it expired.
        sweeper.sweep(afterExpiry.plus(Duration.ofDays(29)));
        assertThat(repo.findByPublicId(created.publicId())).isPresent();

        sweeper.sweep(afterExpiry.plus(Duration.ofDays(31)));
        assertThat(repo.findByPublicId(created.publicId())).isEmpty();
    }

    @Test
    void anOpenedRowAndItsAnonymousOpenerDetailsAreDeletedAfterRetention() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.ANYONE);
        secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.anonymous("203.0.113.7", "Chrome on Android"), Instant.now());

        sweeper.sweep(Instant.now().plus(Duration.ofDays(31)));

        assertThat(jdbc.queryForObject("select count(*) from secret_shares where opened_ip = '203.0.113.7' and public_id = ?",
                Integer.class, created.publicId())).isZero();
    }

    @Test
    void anOpenersNameSurvivesTheirAccountBeingDeleted() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.SIGNED_IN);
        secrets.open(created.publicId(), sealed.verifier(), SecretShare.Opener.signedIn(bob), Instant.now());

        jdbc.update("delete from users where id = ?", bob.getId());

        var row = secrets.list(alice, 0, Instant.now()).getContent().getFirst();
        assertThat(row.openedByName()).isEqualTo(bob.getUsername());
        assertThat(row.openedAnonymously()).isFalse();
        assertThat(jdbc.queryForObject("select opened_by_id is null from secret_shares where public_id = ?",
                Boolean.class, created.publicId())).isTrue();
    }

    // ---------------------------------------------------------------- creation rules ----

    @Test
    void creationRefusesWhatTheServerCannotVouchFor() {
        var alice = newUser("alice");
        var sealed = Sealed.random();

        assertThatThrownBy(() -> secrets.create(alice, sealed.payload(), sealed.verifier(), null,
                Duration.ofMinutes(42), SecretAudience.SIGNED_IN, Instant.now()))
                .isInstanceOf(PublicBadRequestException.class).hasMessageContaining("lifetime");
        assertThatThrownBy(() -> secrets.create(alice, b64("not a payload".getBytes(StandardCharsets.UTF_8)),
                sealed.verifier(), null, HOUR, SecretAudience.SIGNED_IN, Instant.now()))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() -> secrets.create(alice, sealed.payload(), "short", null, HOUR,
                SecretAudience.SIGNED_IN, Instant.now()))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() -> secrets.create(alice, sealed.payload(), sealed.verifier(), "x".repeat(81), HOUR,
                SecretAudience.SIGNED_IN, Instant.now()))
                .isInstanceOf(PublicBadRequestException.class);
        var tooBig = new byte[1 + 12 + 16 + 16385];
        tooBig[0] = 1;
        assertThatThrownBy(() -> secrets.create(alice, b64(tooBig), sealed.verifier(), null, HOUR,
                SecretAudience.SIGNED_IN, Instant.now()))
                .isInstanceOf(PublicBadRequestException.class);
        assertThat(secrets.list(alice, 0, Instant.now()).getContent()).isEmpty();
    }

    @Test
    void anAccountCanHoldOnlySoManyWaitingSecrets() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var first = Sealed.random();
        var firstCreated = create(alice, first, null, SecretAudience.SIGNED_IN);
        for (int i = 1; i < 5; i++) create(alice, Sealed.random(), null, SecretAudience.SIGNED_IN);

        assertThatThrownBy(() -> create(alice, Sealed.random(), null, SecretAudience.SIGNED_IN))
                .isInstanceOf(PublicBadRequestException.class).hasMessageContaining("5");

        // An opened secret no longer counts.
        secrets.open(firstCreated.publicId(), first.verifier(), SecretShare.Opener.signedIn(bob), Instant.now());
        assertThat(create(alice, Sealed.random(), null, SecretAudience.SIGNED_IN).publicId()).isNotBlank();
    }

    // ---------------------------------------------------------------- the HTTP edge ----

    private SecretShareRestController controller(CurrentUser currentUser) {
        return new SecretShareRestController(secrets, currentUser, new RateLimiter());
    }

    private static MockHttpServletRequest keyHolderRequest(String verifier) {
        var request = new MockHttpServletRequest("POST", "/api/secrets/x/open");
        request.setContentType("application/json");
        request.setContent(("{\"verifier\":\"" + verifier + "\"}").getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0");
        return request;
    }

    @Test
    void aSignedOutOpenOverHttpNeverResolvesAUserAndRecordsTheValidatedAddress() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.ANYONE);
        var currentUser = mock(CurrentUser.class);
        when(currentUser.signedIn(any())).thenReturn(Optional.empty());
        var http = controller(currentUser);

        var status = http.status(created.publicId(), keyHolderRequest(sealed.verifier()), null);
        assertThat(status.getStatusCode().value()).isEqualTo(200);
        assertThat(status.getHeaders().getCacheControl()).contains("no-store");
        assertThat(status.getBody()).containsEntry("signedIn", false).doesNotContainKey("payload");

        var open = http.open(created.publicId(), keyHolderRequest(sealed.verifier()), null);
        assertThat(open.getStatusCode().value()).isEqualTo(200);
        assertThat(open.getHeaders().getCacheControl()).contains("no-store");
        assertThat(open.getBody().get("payload")).isEqualTo(sealed.payload());

        var second = http.open(created.publicId(), keyHolderRequest(sealed.verifier()), null);
        assertThat(second.getStatusCode().value()).isEqualTo(410);
        assertThat(second.getBody()).containsEntry("state", SecretState.OPENED).doesNotContainKey("payload");

        verify(currentUser, never()).resolve(any(org.springframework.security.core.Authentication.class));
        verify(currentUser, never()).resolve(any(java.security.Principal.class));

        var row = secrets.list(alice, 0, Instant.now()).getContent().getFirst();
        assertThat(row.openedIp()).isEqualTo("203.0.113.9");
        assertThat(row.openedClient()).isEqualTo("Firefox on Linux");
    }

    @Test
    void aForgedForwardedAddressIsRecordedAsUnknownNotVerbatim() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.ANYONE);
        var currentUser = mock(CurrentUser.class);
        when(currentUser.signedIn(any())).thenReturn(Optional.empty());
        var request = keyHolderRequest(sealed.verifier());
        request.setRemoteAddr("[evil](https://evil.example)");

        controller(currentUser).open(created.publicId(), request, null);

        var row = secrets.list(alice, 0, Instant.now()).getContent().getFirst();
        assertThat(row.state()).isEqualTo(SecretState.OPENED);
        assertThat(row.openedIp()).isNull();
    }

    @Test
    void httpRefusalsAreDistinctForSignInAndIdenticalForUnknownAndWrongKey() {
        var alice = newUser("alice");
        var sealed = Sealed.random();
        var created = create(alice, sealed, null, SecretAudience.SIGNED_IN);
        var currentUser = mock(CurrentUser.class);
        when(currentUser.signedIn(any())).thenReturn(Optional.empty());
        var http = controller(currentUser);

        var signIn = http.open(created.publicId(), keyHolderRequest(sealed.verifier()), null);
        assertThat(signIn.getStatusCode().value()).isEqualTo(401);
        assertThat(signIn.getBody()).containsEntry("code", "sign_in_required");

        var wrongKey = http.status(created.publicId(), keyHolderRequest(otherVerifier()), null);
        var unknown = http.status("ZZZZZZZZZZZZZZZZZZZZZZ", keyHolderRequest(sealed.verifier()), null);
        assertThat(wrongKey.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getStatusCode()).isEqualTo(wrongKey.getStatusCode());
        assertThat(unknown.getBody()).isEqualTo(wrongKey.getBody());
        assertThat(unknown.getHeaders()).isEqualTo(wrongKey.getHeaders());

        // Still waiting after all of that.
        assertThat(secrets.status(created.publicId(), sealed.verifier(), true, Instant.now())).isInstanceOf(Outcome.Ok.class);
    }
}
