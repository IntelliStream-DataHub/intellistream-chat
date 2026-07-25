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

package ai.intellistream.chat.domain;

/**
 * Effective presence kind shown on a user's avatar — the four states Slack /
 * Mattermost surface in their status menu. {@link #ACTIVE} is the default
 * auto-derived state when the user has at least one live STOMP session and
 * no manual override; the other three are user-chosen overrides that beat
 * the auto state (so a connected user who sets themselves to {@code OFFLINE}
 * appears offline to everyone).
 *
 * <p>Persisted to the {@code user_presence.manual_status_kind} column for
 * {@code AWAY} / {@code DND} / {@code OFFLINE} only. {@code ACTIVE} is never
 * stored as a manual override — clearing the override is how a user goes
 * back to the auto-derived "active when connected" behaviour.
 */
public enum PresenceKind {

    /** Connected and not overriding — shows the green dot. Never persisted. */
    ACTIVE,
    /** Manual: "I'll respond when I'm back". Yellow dot. */
    AWAY,
    /** Manual: notifications suppressed, "Do Not Disturb". Red dot. */
    DND,
    /** Manual: appear offline even while connected. Gray dot (or hidden). */
    OFFLINE;

    /** True when this kind is one of the persistable manual overrides. */
    public boolean isManual() {
        return this != ACTIVE;
    }
}
