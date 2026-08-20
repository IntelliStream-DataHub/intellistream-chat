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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.PresenceKind;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.domain.UserPresence;
import ai.intellistream.chat.repository.UserPresenceRepository;
import ai.intellistream.chat.web.dto.PresenceDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines the in-memory {@link PresenceTracker} (online/offline) with persisted custom
 * statuses ({@link UserPresence}) into the {@link PresenceDto} clients consume.
 *
 * <p>Status auto-clears are applied lazily on read: when {@code statusClearAt} is in the past,
 * the row's emoji/text are returned as {@code null}. We don't actively wipe the DB row — a
 * later {@code setStatus} or {@code clearStatus} call resets it. This keeps the service free
 * of scheduled jobs.
 */
@Service
public class PresenceService {

    private static final int MAX_EMOJI_LEN = 16;
    private static final int MAX_TEXT_LEN = 120;

    private final UserPresenceRepository repo;
    private final PresenceTracker tracker;
    private final Duration awayThreshold;

    public PresenceService(UserPresenceRepository repo,
                           PresenceTracker tracker,
                           @Value("${ichat.presence.away-after-minutes:1}") int awayAfterMinutes) {
        this.repo = repo;
        this.tracker = tracker;
        this.awayThreshold = Duration.ofMinutes(Math.max(1, awayAfterMinutes));
    }

    /**
     * The idle threshold before auto-AWAY kicks in ({@code ichat.presence.away-after-minutes}).
     *
     * <p>One minute, not ten. Ten was chosen when idleness was inferred from
     * {@code users.last_active_at} — the last authenticated HTTP request — which needed a long
     * window because it was so lossy: a person actively chatting produces no HTTP requests at all,
     * so a short threshold turned them yellow mid-conversation. Now that the browser reports real
     * input ({@link PresenceTracker#noteActivity}), the signal means what it says and the window
     * can be as short as the thing it describes. A minute away from the keyboard is a minute away.
     */
    public Duration awayThreshold() {
        return awayThreshold;
    }

    /** Persist (or update) the custom status row for {@code user}. Returns the resulting DTO. */
    @Transactional
    public PresenceDto setStatus(User user, String emoji, String text, Instant clearAt) {
        var trimmedEmoji = emoji == null ? null : emoji.trim();
        var trimmedText = text == null ? null : text.trim();
        if (trimmedEmoji != null && trimmedEmoji.length() > MAX_EMOJI_LEN) {
            throw new IllegalArgumentException("Status emoji too long");
        }
        if (trimmedText != null && trimmedText.length() > MAX_TEXT_LEN) {
            throw new IllegalArgumentException("Status text too long (max " + MAX_TEXT_LEN + " chars)");
        }
        if ((trimmedEmoji == null || trimmedEmoji.isEmpty())
                && (trimmedText == null || trimmedText.isEmpty())) {
            // Treat "set with no content" as a clear so callers don't have to special-case.
            return clearStatus(user);
        }
        // Ensure the row exists race-free (N1) so two concurrent first-time status writes don't
        // both INSERT and abort the tx; then update the loaded entity.
        repo.insertRowIgnore(user.getId());
        var row = repo.findById(user.getId()).orElseThrow();
        row.setStatus(emptyToNull(trimmedEmoji), emptyToNull(trimmedText), clearAt);
        var saved = repo.save(row);
        return toDto(user.getUsername(), saved, tracker.isOnline(user.getUsername()), Instant.now());
    }

    @Transactional
    public PresenceDto clearStatus(User user) {
        var row = repo.findById(user.getId()).orElse(null);
        if (row != null) {
            row.clearStatus();
            row = repo.save(row);
        }
        // Custom-status emoji is gone but the manual KIND override (Away/DND/Offline)
        // stays — those are independent. Recompute the effective DTO so a user who's
        // marked themselves Away keeps the yellow dot after clearing their lunch emoji.
        return toDto(user.getUsername(), row, tracker.isOnline(user.getUsername()), Instant.now());
    }

    /**
     * Apply a manual presence override. Passing {@link PresenceKind#ACTIVE} (or null)
     * clears the override, taking the user back to the auto-derived state. The other
     * three values are persisted to {@code user_presence.manual_status_kind}.
     */
    @Transactional
    public PresenceDto setKind(User user, PresenceKind kind) {
        // Ensure the row exists race-free (N1) before updating the loaded entity.
        repo.insertRowIgnore(user.getId());
        var row = repo.findById(user.getId()).orElseThrow();
        row.setManualKind(kind);
        var saved = repo.save(row);
        return toDto(user.getUsername(), saved, tracker.isOnline(user.getUsername()), Instant.now());
    }

    /** Clear the manual override; equivalent to {@code setKind(user, ACTIVE)}. */
    @Transactional
    public PresenceDto clearKind(User user) {
        return setKind(user, PresenceKind.ACTIVE);
    }

    /** Single-user lookup, used by the WS connect listener to attach status to the broadcast. */
    @Transactional(readOnly = true)
    public PresenceDto presenceFor(User user) {
        var now = Instant.now();
        var online = tracker.isOnline(user.getUsername());
        var row = repo.findById(user.getId()).orElse(null);
        return toDto(user.getUsername(), row, online, now);
    }

    /**
     * Batch lookup for the topbar / sidebar avatars. {@code usernames} is matched
     * case-insensitively. Users without a presence row come back as a plain {@code online} or
     * {@code offline} DTO with no custom status; expired statuses are scrubbed.
     */
    @Transactional(readOnly = true)
    public List<PresenceDto> presenceFor(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) return List.of();
        var lc = usernames.stream().filter(s -> s != null && !s.isBlank())
                .map(String::toLowerCase).distinct().toList();
        if (lc.isEmpty()) return List.of();
        var rows = repo.findByUsernames(lc);
        var byUsername = new HashMap<String, UserPresence>(rows.size());
        for (var r : rows) {
            byUsername.put(r.getUser().getUsername().toLowerCase(), r);
        }
        // No second query for last-activity: idleness now comes from PresenceTracker, which holds
        // it in memory next to the session set. This used to fetch every named User row purely to
        // read users.last_active_at — a whole extra SELECT on a poll that every open tab repeats
        // once a minute, for a column that was answering the wrong question anyway.
        var now = Instant.now();
        var out = new ArrayList<PresenceDto>(lc.size());
        for (var username : usernames) {
            if (username == null || username.isBlank()) continue;
            var row = byUsername.get(username.toLowerCase());
            out.add(toDto(username, row, tracker.isOnline(username), now));
        }
        return out;
    }

    /**
     * The three-way derivation, in one place.
     *
     * <ul>
     *   <li><b>OFFLINE means there is no live WebSocket</b>, and nothing else does. A person with
     *       a window open is never grey — they are green or yellow.</li>
     *   <li><b>AWAY means connected but not doing anything</b> for {@link #awayThreshold}, where
     *       "doing anything" is real input in the browser, reported over the socket. It used to be
     *       "no authenticated HTTP request lately", which is a different question with a different
     *       answer: someone chatting over STOMP makes no HTTP requests, so the busiest person in
     *       the room went yellow while they typed.</li>
     *   <li><b>A manual override beats both</b>, always. Someone who set themselves DND stays DND
     *       through idleness, activity and a reconnect — see {@code manualDndSurvives…} in
     *       PresenceFlowIT.</li>
     * </ul>
     */
    private PresenceDto toDto(String username, UserPresence row, boolean online, Instant now) {
        var manual = row == null ? null : row.getManualKind();
        PresenceKind kind;
        if (manual != null) {
            kind = manual;
        } else if (!online) {
            kind = PresenceKind.OFFLINE;
        } else if (tracker.isIdle(username, awayThreshold, now)) {
            kind = PresenceKind.AWAY;
        } else {
            kind = PresenceKind.ACTIVE;
        }
        // Backwards-compat boolean: only true when truly active (auto, no override, recent).
        var onlineFlag = kind == PresenceKind.ACTIVE;
        if (row == null || !row.hasActiveStatus(now)) {
            return new PresenceDto(username, onlineFlag, kind, null, null, null);
        }
        return new PresenceDto(username, onlineFlag, kind,
                row.getStatusEmoji(), row.getStatusText(), row.getStatusClearAt());
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Snapshot of every user that currently has a live STOMP session AND a persisted status.
     * Used by the page-load endpoint so the sidebar can paint dots + statuses without waiting
     * for a {@code /topic/presence} broadcast to fire.
     */
    @Transactional(readOnly = true)
    public Map<String, PresenceDto> snapshotOnline() {
        var online = tracker.onlineUsernames();
        if (online.isEmpty()) return Map.of();
        var dtos = presenceFor(online);
        var out = new HashMap<String, PresenceDto>(dtos.size());
        for (var d : dtos) out.put(d.username(), d);
        return out;
    }
}
