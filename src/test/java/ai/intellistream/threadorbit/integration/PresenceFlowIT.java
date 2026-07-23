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

import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.repository.UserPresenceRepository;
import ai.intellistream.threadorbit.repository.UserRepository;
import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.service.PresenceService;
import ai.intellistream.threadorbit.service.PresenceTracker;
import ai.intellistream.threadorbit.web.PresenceEventListener;
import ai.intellistream.threadorbit.web.PresenceRestController;
import ai.intellistream.threadorbit.web.dto.PresenceDto;
import ai.intellistream.threadorbit.web.dto.SetStatusRequest;
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
        controller = new PresenceRestController(presenceService, currentUser, broker,
                new ai.intellistream.threadorbit.security.RateLimiter());
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

        tracker.connect(alice.getUsername(), "session-solo");
        assertThat(presenceService.presenceFor(alice).online()).isTrue();

        tracker.disconnect(alice.getUsername(), "session-solo");
        assertThat(presenceService.presenceFor(alice).online()).isFalse();
    }

    @Test
    void multiTabConnectsCollapseToASingleOnlineTransition() {
        // First connect flips 0→1 (the broadcast trigger). Second tab re-connecting must NOT
        // re-trigger; only the final disconnect flips 1→0.
        assertThat(tracker.connect("alice", "tab-1")).isTrue();
        assertThat(tracker.connect("alice", "tab-2")).isFalse();
        assertThat(tracker.disconnect("alice", "tab-1")).isFalse();
        assertThat(tracker.disconnect("alice", "tab-2")).isTrue();
    }

    // ---------- Event listener ----------

    @Test
    void connectEventBroadcastsOnlinePresenceWithStatus() {
        var alice = newUser("alice");
        presenceService.setStatus(alice, "🍕", "lunch", null);

        listener.onConnect(connectEventFor(alice.getUsername()));

        var captor = ArgumentCaptor.forClass(PresenceDto.class);
        verify(broker).convertAndSend(eq("/topic/presence"), captor.capture());
        assertThat(captor.getValue().username()).isEqualTo(alice.getUsername());
        assertThat(captor.getValue().online()).isTrue();
        assertThat(captor.getValue().statusEmoji()).isEqualTo("🍕");
    }

    @Test
    void secondConnectFromSameUserDoesNotReBroadcast() {
        var alice = newUser("alice");

        listener.onConnect(connectEventFor(alice.getUsername(), "tab-1"));
        listener.onConnect(connectEventFor(alice.getUsername(), "tab-2"));

        verify(broker).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }

    @Test
    void disconnectEventBroadcastsOfflineOnlyOnLastSession() {
        var alice = newUser("alice");
        listener.onConnect(connectEventFor(alice.getUsername(), "tab-1"));
        listener.onConnect(connectEventFor(alice.getUsername(), "tab-2"));

        // First disconnect: not the last session yet — no broadcast for offline.
        listener.onDisconnect(disconnectEventFor(alice.getUsername(), "tab-1"));
        // Last session out — broadcast offline.
        listener.onDisconnect(disconnectEventFor(alice.getUsername(), "tab-2"));

        var captor = ArgumentCaptor.forClass(PresenceDto.class);
        verify(broker, org.mockito.Mockito.times(2))
                .convertAndSend(eq("/topic/presence"), captor.capture());
        var events = captor.getAllValues();
        assertThat(events.get(0).online()).isTrue();   // initial connect
        assertThat(events.get(1).online()).isFalse();  // final disconnect
    }

    @Test
    void unknownPrincipalIsIgnored() {
        listener.onConnect(connectEventFor("not-a-real-user"));
        listener.onDisconnect(disconnectEventFor("not-a-real-user"));
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
        tracker.connect(alice.getUsername(), "session-solo");
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

    // ---------- Manual presence kind (Slack-style Active/Away/DND/Offline) ----------

    @Test
    void connectedUserDefaultsToActiveKind() {
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");

        var dto = presenceService.presenceFor(alice);

        assertThat(dto.online()).isTrue();
        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.ACTIVE);
    }

    @Test
    void disconnectedUserWithoutOverrideIsOffline() {
        var alice = newUser("alice");

        var dto = presenceService.presenceFor(alice);

        assertThat(dto.online()).isFalse();
        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.OFFLINE);
    }

    @Test
    void manualAwayBeatsConnectedAutoState() {
        // User is genuinely connected via STOMP, but they've manually set themselves to Away.
        // The override wins; the auto green dot is replaced with the yellow Away dot, and
        // the backwards-compat boolean reads as NOT online so old clients dim the avatar.
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");

        var dto = presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.AWAY);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
        assertThat(dto.online()).isFalse();
    }

    @Test
    void manualDndAndOfflineAreAlsoApplied() {
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");

        assertThat(presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.DND).kind())
                .isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.DND);
        assertThat(presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.OFFLINE).kind())
                .isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.OFFLINE);
    }

    @Test
    void clearKindReturnsToActiveForConnectedUser() {
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");
        presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.AWAY);

        var dto = presenceService.clearKind(alice);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.ACTIVE);
        assertThat(dto.online()).isTrue();
    }

    @Test
    void settingActiveAlsoClearsOverride() {
        // Treating ACTIVE as "no override" lets a single endpoint (PUT /kind) handle both
        // setting and clearing — UI flips to Active just by sending ACTIVE.
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");
        presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.DND);

        var dto = presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.ACTIVE);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.ACTIVE);
        assertThat(presenceRepo.findById(alice.getId()).orElseThrow().getManualKind()).isNull();
    }

    @Test
    void recentlyActiveConnectedUserStaysActive() {
        // Sanity: a freshly-created user (lastActiveAt = now) on a live tracker session
        // shouldn't trip the auto-AWAY check even though the threshold is short.
        var alice = newUser("alice");
        alice.touchActive(java.time.Instant.now());
        users.save(alice);
        tracker.connect(alice.getUsername(), "session-solo");

        var dto = presenceService.presenceFor(alice);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.ACTIVE);
    }

    @Test
    void idleConnectedUserAutoFlipsToAway() {
        // User is connected via STOMP but their last_active_at is older than the away
        // threshold — the auto-AWAY rule fires without anyone setting a manual override.
        var alice = newUser("alice");
        alice.touchActive(java.time.Instant.now()
                .minus(presenceService.awayThreshold().plusMinutes(1)));
        users.save(alice);
        tracker.connect(alice.getUsername(), "session-solo");

        var dto = presenceService.presenceFor(alice);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
        assertThat(dto.online()).isFalse(); // backwards-compat boolean: only true for ACTIVE
    }

    @Test
    void manualOverrideBeatsAutoAway() {
        // User is genuinely idle (would auto-AWAY) AND they've set themselves DND.
        // Manual override always wins — we should see DND, not AWAY.
        var alice = newUser("alice");
        alice.touchActive(java.time.Instant.now()
                .minus(presenceService.awayThreshold().plusMinutes(1)));
        users.save(alice);
        tracker.connect(alice.getUsername(), "session-solo");
        presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.DND);

        var dto = presenceService.presenceFor(alice);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.DND);
    }

    @Test
    void disconnectedIdleUserStillReportsOffline() {
        // Auto-AWAY is for connected idle users. If they're disconnected, OFFLINE wins
        // regardless of how stale lastActiveAt is.
        var alice = newUser("alice");
        alice.touchActive(java.time.Instant.now()
                .minus(presenceService.awayThreshold().plusMinutes(1)));
        users.save(alice);

        var dto = presenceService.presenceFor(alice);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.OFFLINE);
    }

    @Test
    void batchPresenceLookupComputesAutoAway() {
        // Same auto-AWAY logic must apply to the batch endpoint that the topbar /
        // sidebar avatars hit. Two users: one fresh-active, one idle-but-connected.
        var fresh = newUser("fresh");
        var idle = newUser("idle");
        idle.touchActive(java.time.Instant.now()
                .minus(presenceService.awayThreshold().plusMinutes(1)));
        users.save(idle);
        tracker.connect(fresh.getUsername(), "session-solo");
        tracker.connect(idle.getUsername(), "session-solo");

        var result = presenceService.presenceFor(
                java.util.List.of(fresh.getUsername(), idle.getUsername()));

        var byUsername = result.stream().collect(java.util.stream.Collectors.toMap(
                p -> p.username(), p -> p.kind()));
        assertThat(byUsername.get(fresh.getUsername())).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.ACTIVE);
        assertThat(byUsername.get(idle.getUsername())).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
    }

    @Test
    void manualOverridePersistsAcrossDisconnect() {
        // Simulate reconnect: connect → set Away → disconnect → reconnect → still Away.
        // The override survives because it lives in the DB row, not the in-memory tracker.
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");
        presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
        tracker.disconnect(alice.getUsername(), "session-solo");
        tracker.connect(alice.getUsername(), "session-solo");

        var dto = presenceService.presenceFor(alice);
        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
    }

    @Test
    void customStatusEmojiAndManualKindCoexist() {
        // The lunch emoji and the AWAY override are independent dimensions of presence —
        // the user can be Away with a 🍕 status badge.
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");
        presenceService.setStatus(alice, "🍕", "lunch", null);
        presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.AWAY);

        var dto = presenceService.presenceFor(alice);

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
        assertThat(dto.statusEmoji()).isEqualTo("🍕");
        assertThat(dto.statusText()).isEqualTo("lunch");
    }

    @Test
    void clearStatusKeepsManualKind() {
        // Clearing the lunch emoji shouldn't accidentally also flip the user back to Active.
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");
        presenceService.setStatus(alice, "🍕", "lunch", null);
        presenceService.setKind(alice, ai.intellistream.threadorbit.domain.PresenceKind.AWAY);

        var afterClear = presenceService.clearStatus(alice);

        assertThat(afterClear.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
        assertThat(afterClear.statusEmoji()).isNull();
    }

    @Test
    void putKindEndpointBroadcasts() {
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var dto = controller.setKind(
                new ai.intellistream.threadorbit.web.dto.SetPresenceKindRequest(
                        ai.intellistream.threadorbit.domain.PresenceKind.AWAY),
                mock(Principal.class));

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.AWAY);
        verify(broker).convertAndSend(eq("/topic/presence"), eq(dto));
    }

    @Test
    void deleteKindEndpointClearsOverrideAndBroadcasts() {
        var alice = newUser("alice");
        tracker.connect(alice.getUsername(), "session-solo");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        controller.setKind(new ai.intellistream.threadorbit.web.dto.SetPresenceKindRequest(
                ai.intellistream.threadorbit.domain.PresenceKind.DND), mock(Principal.class));

        var dto = controller.clearKind(mock(Principal.class));

        assertThat(dto.kind()).isEqualTo(ai.intellistream.threadorbit.domain.PresenceKind.ACTIVE);
        // setKind broadcast plus the clear broadcast — at least 2 events.
        verify(broker, org.mockito.Mockito.atLeast(2)).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }

    // ---------- helpers ----------

    // The principal name on the STOMP handshake is preferred_username, not the OIDC sub —
    // the OIDC client config sets user-name-attribute: preferred_username, and
    // KeycloakRolesConverter pins the JWT principal name to the same claim. So the listener
    // resolves users by username, and these helpers feed username strings.
    private static SessionConnectedEvent connectEventFor(String principalName) {
        return connectEventFor(principalName, "session-" + principalName);
    }

    private static SessionConnectedEvent connectEventFor(String principalName, String sessionId) {
        // The listener reads the STOMP session id off the message headers, so set it there.
        var accessor = org.springframework.messaging.simp.stomp.StompHeaderAccessor.create(
                org.springframework.messaging.simp.stomp.StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        Message<byte[]> msg = org.springframework.messaging.support.MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        return new SessionConnectedEvent(new Object(), msg, () -> principalName);
    }

    private static SessionDisconnectEvent disconnectEventFor(String principalName) {
        return disconnectEventFor(principalName, "session-" + principalName);
    }

    private static SessionDisconnectEvent disconnectEventFor(String principalName, String sessionId) {
        Message<byte[]> msg = new GenericMessage<>(new byte[0]);
        return new SessionDisconnectEvent(new Object(), msg, sessionId,
                org.springframework.web.socket.CloseStatus.NORMAL, () -> principalName);
    }
}
