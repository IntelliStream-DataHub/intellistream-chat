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
import ai.intellistream.chat.service.UserFileService;
import ai.intellistream.chat.web.dto.ConversationEvent;
import ai.intellistream.chat.web.dto.MessageEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;

/**
 * The file manager's API. Both endpoints are scoped to the signed-in account and nothing else:
 * the listing takes no owner parameter at all, and the delete takes an attachment id whose owner is
 * re-derived from the database — an id from the client is a request, never a claim.
 *
 * @see UserFileService for the delete policy and why the search is a SQL predicate rather than a
 *      Lucene query
 */
@RestController
@RequestMapping("/api/files")
public class UserFileRestController {

    private final UserFileService files;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;

    public UserFileRestController(UserFileService files,
                                  CurrentUser currentUser,
                                  SimpMessagingTemplate broker,
                                  RateLimiter rateLimiter) {
        this.files = files;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    /**
     * One page of the caller's own uploads. {@code q} filters on filename; {@code page} is
     * zero-based.
     *
     * <p>There is no {@code user} parameter by design. Adding one would create the exact endpoint
     * this feature must not have, and "it always checks that the id is yours" is a check somebody
     * eventually edits.
     */
    @GetMapping
    public UserFileService.FilePage list(@RequestParam(value = "q", required = false) String query,
                                         @RequestParam(value = "page", defaultValue = "0") int page,
                                         Principal principal) {
        var me = currentUser.resolve(principal);
        // Typing in the search box issues a request per keystroke (debounced client-side); the
        // limit is well above that and only bites on a scripted hammer.
        requireRate(me.getUsername(), "file-manager-list", 120);
        return files.list(me, query, page);
    }

    /**
     * Delete one of the caller's files. {@code scope} is {@code channel} or {@code conversation} —
     * the two attachment tables have independent id sequences, so the pair is the identity.
     *
     * <p>Answers 404 when the file is not the caller's, which is the same answer it gives when the
     * file does not exist: whether a stranger's attachment id is in use is not this endpoint's to
     * disclose. Refusals under the delete policy come back as 409 with a message written for the
     * user (see {@code ApiExceptionHandler}).
     *
     * <p>The broadcast is after the service call, not inside it: {@code delete} is
     * {@code @Transactional} and has committed by the time it returns, so publishing here cannot
     * announce a removal that a rollback then undid.
     */
    @DeleteMapping("/{scope}/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String scope,
                                                      @PathVariable Long id,
                                                      Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me.getUsername(), "file-manager-delete", 30);
        var deleted = files.delete(me, UserFileService.Scope.parse(scope), id);

        if (deleted.scope() == UserFileService.Scope.CHANNEL) {
            broker.convertAndSend("/topic/channels/" + deleted.channelId(),
                    MessageEvent.deleted(deleted.messageId(), deleted.channelId(),
                            deleted.parentMessageId()));
        } else {
            broker.convertAndSend("/topic/conversations/" + deleted.conversationId(),
                    // parentMessageId is what the conversation client decrements a thread indicator
                    // by; it comes through the same field the channel branch above uses, and is
                    // null until UserFileService starts populating it for the conversation scope.
                    ConversationEvent.messageDeleted(deleted.conversationId(), deleted.messageId(),
                            deleted.parentMessageId()));
        }
        // What was actually freed, echoed back so a caller that is not the file-manager page (a
        // script, a future bulk delete) learns it without re-listing. The page itself re-lists —
        // the totals, the rows and whether a pager is needed have all just changed together.
        return ResponseEntity.ok(Map.of(
                "filename", deleted.filename(),
                "bytesFreed", deleted.bytesFreed()));
    }

    private void requireRate(String username, String action, int perMinute) {
        if (!rateLimiter.tryAcquire(username, action, perMinute, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException(action + " rate exceeded");
        }
    }
}
