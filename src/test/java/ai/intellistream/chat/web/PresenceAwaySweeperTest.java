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

package ai.intellistream.chat.web;

import ai.intellistream.chat.domain.PresenceKind;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.PresenceService;
import ai.intellistream.chat.service.PresenceTracker;
import ai.intellistream.chat.web.dto.PresenceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweeper owns exactly one transition — going idle — and must publish it exactly once. Both
 * halves matter: a sweep that re-broadcasts every idle user every tick is a fan-out storm, and one
 * that also announces first sightings duplicates what {@code PresenceEventListener} already said
 * on connect, which since the idle-ms header can itself be AWAY.
 */
class PresenceAwaySweeperTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(1);

    private final PresenceTracker tracker = new PresenceTracker();
    private final PresenceService presenceService = mock(PresenceService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private final PresenceAwaySweeper sweeper = new PresenceAwaySweeper(tracker, presenceService, users, broker);
    private final User bob = new User("kc-bob", "bob", "bob@example.com", "Bob");

    @BeforeEach
    void stub() {
        when(presenceService.awayThreshold()).thenReturn(THRESHOLD);
        when(users.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(bob));
        when(presenceService.presenceFor(bob))
                .thenReturn(new PresenceDto("bob", false, PresenceKind.AWAY, null, null, null));
    }

    @Test
    void firstSightingOfAnActiveUserIsNotPublished() {
        tracker.connect("bob", "tab-1");

        sweeper.sweep();

        verify(broker, never()).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }

    @Test
    void firstSightingOfAnAlreadyIdleUserIsNotPublishedEither() {
        // A redialled background tab: the connect listener announced AWAY from the idle-ms header.
        tracker.connect("bob", "tab-1", Instant.now().minus(THRESHOLD.plusMinutes(1)));

        sweeper.sweep();

        verify(broker, never()).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }

    @Test
    void goingIdleIsPublishedOnceAndOnlyOnce() {
        tracker.connect("bob", "tab-1");
        sweeper.sweep(); // records "active", publishes nothing

        tracker.noteActivity("bob", Instant.now().minus(THRESHOLD.plusSeconds(1)));
        sweeper.sweep(); // the edge
        sweeper.sweep(); // still idle — nothing new
        sweeper.sweep();

        verify(broker, times(1)).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }

    @Test
    void aUserWhoLeftAndCameBackStartsFromAcleanSlate() {
        tracker.connect("bob", "tab-1");
        sweeper.sweep();
        tracker.noteActivity("bob", Instant.now().minus(THRESHOLD.plusSeconds(1)));
        sweeper.sweep(); // published idle
        tracker.disconnect("bob", "tab-1");
        sweeper.sweep(); // forgets bob

        tracker.connect("bob", "tab-2"); // fresh page load, announced by the listener
        sweeper.sweep();                 // first sighting again — not news

        verify(broker, times(1)).convertAndSend(eq("/topic/presence"), any(PresenceDto.class));
    }
}
