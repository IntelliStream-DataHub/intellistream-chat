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

import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.PresenceService;
import ai.intellistream.chat.service.PresenceTracker;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes the moment somebody goes idle.
 *
 * <p>Every other presence transition has an event behind it — a connect, a disconnect, a click on
 * the status menu, an activity ping — and can be broadcast from the thing that caused it. Going
 * AWAY is the exception: nothing happens. It is the <em>absence</em> of anything happening for a
 * minute, and absence does not fire a listener.
 *
 * <p>Without this the yellow dot appeared whenever the viewer's own 60-second poll next came round,
 * which with a one-minute threshold meant somewhere between one and two minutes late and different
 * for every person looking. That was tolerable when the threshold was ten minutes and is not now.
 *
 * <p>The sweep is cheap by construction: it walks an in-memory set of online usernames and compares
 * an {@code Instant}, and only reaches the database when a user's derived state actually changed —
 * at most twice per person per idle cycle. {@link #published} is what makes that true; without it,
 * every tick would re-broadcast every idle user forever.
 *
 * <p><b>Single-instance only</b>, like the other schedulers here: {@code @EnableScheduling} runs on
 * every node, and {@link PresenceTracker} is per-process anyway, so a second node would sweep its
 * own connections. Presence as a whole needs shared state before it goes multi-node.
 */
@Component
public class PresenceAwaySweeper {

    /**
     * Four times the shortest useful threshold. The dot is therefore at most this late, which is
     * the resolution the feature is worth: a person who stepped away fifteen seconds ago and a
     * person who stepped away a minute ago are the same person to anybody reading the sidebar.
     */
    private static final long SWEEP_MS = 15_000;

    private final PresenceTracker tracker;
    private final PresenceService presenceService;
    private final UserRepository users;
    private final SimpMessagingTemplate broker;

    /**
     * The last idle-state we told everybody about, per user. Not the last state we computed —
     * the last one that went out on the wire, so a restarted broadcast cannot be skipped and a
     * repeated one cannot be sent.
     */
    private final ConcurrentHashMap<String, Boolean> published = new ConcurrentHashMap<>();

    public PresenceAwaySweeper(PresenceTracker tracker,
                               PresenceService presenceService,
                               UserRepository users,
                               SimpMessagingTemplate broker) {
        this.tracker = tracker;
        this.presenceService = presenceService;
        this.users = users;
        this.broker = broker;
    }

    @Scheduled(fixedDelay = SWEEP_MS, initialDelay = SWEEP_MS)
    public void sweep() {
        var online = tracker.onlineUsernames();
        // Somebody who disconnected is handled by PresenceEventListener, which broadcasts OFFLINE.
        // All this has to do is forget them, so that reconnecting starts from a clean slate rather
        // than from whatever they were when they vanished.
        published.keySet().removeIf(username -> !online.contains(username));

        var now = Instant.now();
        var threshold = presenceService.awayThreshold();
        for (var username : online) {
            var idle = tracker.isIdle(username, threshold, now);
            var previous = published.put(username, idle);
            if (previous != null && previous == idle) continue;
            // First sighting of an active user is not news — everyone already drew them green
            // when they connected. Only publish a real edge, and only the one this class owns.
            if (previous == null && !idle) continue;
            users.findByUsernameIgnoreCase(username).ifPresent(user ->
                    broker.convertAndSend("/topic/presence", presenceService.presenceFor(user)));
        }
    }
}
