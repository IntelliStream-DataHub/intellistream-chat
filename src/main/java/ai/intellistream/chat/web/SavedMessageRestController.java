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

import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.SavedMessageService;
import ai.intellistream.chat.web.dto.SavedMessageDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

/**
 * Saved messages, the private reading queue.
 *
 * <p>Rooted at {@code /api/saved} rather than hanging off {@code /api/messages/{id}/save}, because
 * the resource being created is the <em>caller's</em> bookmark, not a property of the message: two
 * people saving the same message create two unrelated rows, and nothing about the message changes.
 * The URL says whose list is being edited by saying nothing — there is no "whose saves" parameter
 * to forget to check, exactly as on {@code /files}.
 *
 * <p>Both message kinds live here, under {@code /messages/{id}} and
 * {@code /conversation-messages/{id}}. Channel messages and DM messages have independent id
 * sequences, so a single path would need a discriminator anyway, and one that is part of the route
 * cannot be omitted by a caller.
 *
 * <p>PUT rather than POST: saving is idempotent — the row is inserted with {@code ON CONFLICT DO
 * NOTHING} — and a double-click should be one save and no error.
 */
@RestController
@RequestMapping("/api/saved")
public class SavedMessageRestController {

    private final SavedMessageService saved;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public SavedMessageRestController(SavedMessageService saved,
                                      CurrentUser currentUser,
                                      RateLimiter rateLimiter) {
        this.saved = saved;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<SavedMessageDto> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size,
                                      Principal principal) {
        return saved.listFor(currentUser.resolve(principal), page, size);
    }

    /**
     * The viewer's saved message ids in one channel. The channel page asks once on load and marks
     * its rows from the answer — a message carries no per-viewer state otherwise, and putting a
     * `saved` flag on every MessageDto would mean a join on the feed's hot read path to serve a
     * fact about one reader.
     */
    @GetMapping("/ids")
    public List<Long> idsInChannel(@RequestParam Long channelId, Principal principal) {
        return saved.savedIdsInChannel(currentUser.resolve(principal), channelId);
    }

    @PutMapping("/messages/{id}")
    public ResponseEntity<Void> save(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        requireBudget(me.getUsername());
        saved.saveChannelMessage(id, me);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/messages/{id}")
    public ResponseEntity<Void> unsave(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        requireBudget(me.getUsername());
        saved.unsaveChannelMessage(id, me);
        return ResponseEntity.noContent().build();
    }

    /**
     * Saving a DM. The endpoint exists and is enforced; the DM page does not yet offer the button —
     * its action row lives in {@code static/js/conversation.js}, which this change does not touch.
     * Saved DMs already render on {@code /saved}, so the remaining work is one button.
     */
    @PutMapping("/conversation-messages/{id}")
    public ResponseEntity<Void> saveConversationMessage(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        requireBudget(me.getUsername());
        saved.saveConversationMessage(id, me);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/conversation-messages/{id}")
    public ResponseEntity<Void> unsaveConversationMessage(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        requireBudget(me.getUsername());
        saved.unsaveConversationMessage(id, me);
        return ResponseEntity.noContent().build();
    }

    private void requireBudget(String username) {
        if (!rateLimiter.tryAcquire(username, "message-save", 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("save rate exceeded");
        }
    }
}
