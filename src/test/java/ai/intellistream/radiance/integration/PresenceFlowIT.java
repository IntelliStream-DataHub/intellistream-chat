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

package ai.intellistream.radiance.integration;

import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.UserPresenceRepository;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.security.CurrentUser;
import ai.intellistream.radiance.service.PresenceService;
import ai.intellistream.radiance.service.PresenceTracker;
import ai.intellistream.radiance.web.PresenceEventListener;
import ai.intellistream.radiance.web.PresenceRestController;
import ai.intellistream.radiance.web.dto.PresenceDto;
import ai.intellistream.radiance.web.dto.SetStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cumulative coverage for presence:
 * <ul>
 *   <li>setStatus / clearStatus persist and round-trip through the batch lookup;</li>
 *   <li>STOMP connect/disconnect events drive the in-memory session counter and emit
 *       exactly one /topic/presence broadcast per online↔offline transition (multi-tab safe);</li>
 *   <li>statusClearAt expiry hides the custom status from the DTO without deleting the row;</li>
 *   <li>POST/DELETE /api/presence/status broadcasts and returns the new state.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class PresenceFlowIT {

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
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired UserPresenceRepository presenceRepo;
    @Autowired PresenceService presenceService;
    @Autowired PresenceTracker tracker;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private PresenceRestController controller;
    private PresenceEventListener listener;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        tracker.resetForTests();
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        controller = new PresenceRestController(presenceService, currentUser, broker);
        listener = new PresenceEventListener(tracker, presenceService, users, broker);
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-pres-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix.toUpperCase() + " " + i));
    }

    // ---------- Service / persistence ----------

    @Test
    void setStatusPersistsAndShowsUpInBatchLookup() {
        var alice = newUser("alice");

        var dto = presenceService.setStatus(alice, "🍕", "lunch", null);
        assertThat(dto.statusEmoji()).isEqualTo("🍕");
        assertThat(dto.statusText()).isEqualTo("lunch");
        assertThat(dto.online()).isFalse();

        var batch = presenceService.presenceFor(List.of(alice.getUsername()));
        assertThat(batch).singleElement()
                .satisfies(p -> {
                    assertThat(p.statusEmoji()).isEqualTo("🍕");
                    assertThat(p.statusText()).isEqualTo("lunch");
                });
    }

    @Test
    void clearStatusWipesEmojiAndTextButPreservesRow() {
        var alice = newUser("alice");
        presenceService.setStatus(alice, "🍕", "lunch", null);

        presenceService.clearStatus(alice);

        var batch = presenceService.presenceFor(List.of(alice.getUsername()));
        assertThat(batch).singleElement()
                .satisfies(p -> {
                    assertThat(p.statusEmoji()).isNull();
                    assertThat(p.statusText()).isNull();
                });
        // Row is kept for re-use; only the columns are nulled.
        assertThat(presenceRepo.findById(alice.getId())).isPresent();
    }

    @Test
    void expiredClearAtScrubsStatusFromDto() {
        var alice = newUser("alice");
        var inThePast = Instant.now().minus(1, ChronoUnit.MINUTES);
        presenceService.setStatus(alice, "🍕", "lunch", inThePast);

        var batch = presenceService.presenceFor(List.of(alice.getUsername()));
        assertThat(batch).singleElement()
                .satisfies(p -> {
                    assertThat(p.statusEmoji()).isNull();
                    assertThat(p.statusText()).isNull();
                });
    }

    @Test
    void emptyStatusInputIsTreatedAsClear() {
        var alice = newUser("alice");
        presenceService.setStatus(alice, "🍕", "lunch", null);

        // Sending blank fields should clear, not error.
        presenceService.setStatus(alice, "", "  ", null);
        var batch = presenceService.presenceFor(List.of(alice.getUsername()));
        assertThat(batch).singleElement()
                .satisfies(p -> {
                    assertThat(p.statusEmoji()).isNull();
                    assertThat(p.statusText()).isNull();
                });
    }

    @Test
    void onlineFlagFollowsTracker() {
        var alice = newUser("alice");
        assertThat(presenceService.presenceFor(alice).online()).isFalse();

        tracker.connect(alice.getUsername());
        assertThat(presenceService.presenceFor(alice).online()).isTrue();

        tracker.disconnect(alice.getUsername());
        assertThat(presenceService.presenceFor(alice).online()).isFalse();
    }

    @Test
    void multiTabConnectsCollapseToASingleOnlineTransition() {
        // First connect flips 0→1 (the broadcast trigger). Second tab re-connecting must NOT
        // re-trigger; only the final disconnect flips 1→0.
        assertThat(tracker.connect("alice")).isTrue();
        assertThat(tracker.connect("alice")).isFalse();
        assertThat(tracker.disconnect("alice")).isFalse();
        assertThat(tracker.disconnect("alice")).isTrue();
    }

    // ---------- Event listener ----------

    @Test
    void connectEventBroadcastsOnlinePresenceWithStatus() {
        var alice = newUser("alice");
        presenceService.setStatus(alice, "🍕", "lunch", null);

        listener.onConnect(connectEventFor(alice.getSubject()));

        var captor = ArgumentCaptor.forClass(PresenceDto.class);
        verify(broker).convertAndSend(eq("/topic/presence"), captor.capture());
        assertThat(captor.getValue().username()).isEqualTo(alice.getUsername());
        assertThat(captor.getValue().online()).isTrue();
        assertThat(captor.getValue().statusEmoji()).isEqualTo("🍕");
    }

    @Test
    void secondConnectFromSameUserDoesNotReBroadcast() {
        var alice = newUser("alice");

        listener.onConnect(connectEventFor(alice.getSubject()));
        listener.onConnect(connectEventFor(alice.getSubject()));

        verify(broker).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }

    @Test
    void disconnectEventBroadcastsOfflineOnlyOnLastSession() {
        var alice = newUser("alice");
        listener.onConnect(connectEventFor(alice.getSubject()));
        listener.onConnect(connectEventFor(alice.getSubject()));

        // First disconnect: not the last session yet — no broadcast for offline.
        listener.onDisconnect(disconnectEventFor(alice.getSubject()));
        // Last session out — broadcast offline.
        listener.onDisconnect(disconnectEventFor(alice.getSubject()));

        var captor = ArgumentCaptor.forClass(PresenceDto.class);
        verify(broker, org.mockito.Mockito.times(2))
                .convertAndSend(eq("/topic/presence"), captor.capture());
        var events = captor.getAllValues();
        assertThat(events.get(0).online()).isTrue();   // initial connect
        assertThat(events.get(1).online()).isFalse();  // final disconnect
    }

    @Test
    void unknownPrincipalIsIgnored() {
        listener.onConnect(connectEventFor("not-a-real-subject"));
        listener.onDisconnect(disconnectEventFor("not-a-real-subject"));
        verify(broker, never()).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }

    // ---------- REST endpoint ----------

    @Test
    void postStatusRoundTripsThroughControllerAndBroadcasts() {
        var alice = newUser("alice");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var dto = controller.setStatus(new SetStatusRequest("🛠️", "deploying", null),
                mock(Principal.class));

        assertThat(dto.statusEmoji()).isEqualTo("🛠️");
        assertThat(dto.statusText()).isEqualTo("deploying");
        verify(broker).convertAndSend(eq("/topic/presence"), eq(dto));
    }

    @Test
    void deleteStatusBroadcastsOfflineOrPlainOnline() {
        var alice = newUser("alice");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        controller.setStatus(new SetStatusRequest("🍕", "lunch", null), mock(Principal.class));

        var dto = controller.clearStatus(mock(Principal.class));

        assertThat(dto.statusEmoji()).isNull();
        assertThat(dto.statusText()).isNull();
        verify(broker).convertAndSend(eq("/topic/presence"), eq(dto));
    }

    @Test
    void getReturnsPresenceForListedUsernamesPreservingOrder() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        tracker.connect(alice.getUsername());
        presenceService.setStatus(bob, "💤", "deep work", null);

        var result = controller.get(alice.getUsername() + "," + bob.getUsername());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).username()).isEqualTo(alice.getUsername());
        assertThat(result.get(0).online()).isTrue();
        assertThat(result.get(1).username()).isEqualTo(bob.getUsername());
        assertThat(result.get(1).statusEmoji()).isEqualTo("💤");
    }

    @Test
    void getReturnsEmptyListForBlankParam() {
        assertThat(controller.get(null)).isEmpty();
        assertThat(controller.get("")).isEmpty();
    }

    // ---------- helpers ----------

    private static SessionConnectedEvent connectEventFor(String subject) {
        Message<byte[]> msg = new GenericMessage<>(new byte[0]);
        return new SessionConnectedEvent(new Object(), msg, () -> subject);
    }

    private static SessionDisconnectEvent disconnectEventFor(String subject) {
        Message<byte[]> msg = new GenericMessage<>(new byte[0]);
        return new SessionDisconnectEvent(new Object(), msg, "session-" + subject,
                org.springframework.web.socket.CloseStatus.NORMAL, () -> subject);
    }
}
