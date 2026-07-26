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
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.NotificationPreferenceService;
import ai.intellistream.chat.web.dto.NotifyLevelDto;
import ai.intellistream.chat.web.dto.SetNotifyLevelRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * The notification control: one account-wide default, and a per-channel override.
 *
 * <ul>
 *   <li>{@code GET  /api/channels/{id}/notify} → {@code {"level":"DEFAULT|ALL|MENTIONS|NONE"}}</li>
 *   <li>{@code PUT  /api/channels/{id}/notify} — same body, same response</li>
 *   <li>{@code GET  /api/profile/notify-default} → {@code {"level":"ALL|MENTIONS|NONE"}}</li>
 *   <li>{@code PUT  /api/profile/notify-default} — same body, same response</li>
 *   <li>{@code GET/PUT /api/profile/notify-dm-default} — the same pair for conversations</li>
 * </ul>
 *
 * <p>The channel value is raw: {@code DEFAULT} means "follows the account default" and is returned
 * as such, so a picker can show <em>Default</em> selected rather than pre-selecting whatever it
 * resolves to today. The account default admits no {@code DEFAULT} — there is nothing above it —
 * and sending one is a 400.
 *
 * <p>Both channel endpoints require actual membership. An unknown channel is 404 (via
 * {@link ChannelService#requireById}); a non-member is 403, from the service. Read and write are
 * gated identically: a non-member has no preference to read, so a readable-but-not-writable
 * variant would only be an oracle for who is in which private channel.
 *
 * <p>These four live in their own controller rather than being split across
 * {@code ChannelRestController} and {@code ProfileController} because they are one feature with one
 * service behind them, and because both halves of the split would otherwise gain a constructor
 * dependency — {@code ChannelRestController}'s is already thirteen collaborators long and is
 * hand-constructed by two integration tests.
 */
@RestController
public class NotificationPreferenceRestController {

    private final NotificationPreferenceService preferences;
    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final CurrentUser currentUser;

    public NotificationPreferenceRestController(NotificationPreferenceService preferences,
                                                ChannelService channelService,
                                                ConversationService conversationService,
                                                CurrentUser currentUser) {
        this.preferences = preferences;
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/channels/{id}/notify")
    public NotifyLevelDto channelLevel(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        return NotifyLevelDto.of(preferences.levelFor(channel, me));
    }

    @PutMapping("/api/channels/{id}/notify")
    public NotifyLevelDto setChannelLevel(@PathVariable Long id,
                                          @RequestBody @Valid SetNotifyLevelRequest body,
                                          Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        return NotifyLevelDto.of(preferences.setLevelFor(channel, me, body.level()));
    }

    /**
     * The same control for a direct or group conversation. Same raw-value contract, same account
     * default underneath, same membership requirement — a conversation is private to its
     * participants, so there is not even a public tier to relax it to.
     */
    @GetMapping("/api/conversations/{id}/notify")
    public NotifyLevelDto conversationLevel(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        var conversation = conversationService.requireById(id);
        return NotifyLevelDto.of(preferences.levelFor(conversation, me));
    }

    @PutMapping("/api/conversations/{id}/notify")
    public NotifyLevelDto setConversationLevel(@PathVariable Long id,
                                               @RequestBody @Valid SetNotifyLevelRequest body,
                                               Principal principal) {
        var me = currentUser.resolve(principal);
        var conversation = conversationService.requireById(id);
        return NotifyLevelDto.of(preferences.setLevelFor(conversation, me, body.level()));
    }

    @GetMapping("/api/profile/notify-default")
    public NotifyLevelDto accountDefault(Principal principal) {
        var me = currentUser.resolve(principal);
        return NotifyLevelDto.of(preferences.accountDefault(me));
    }

    /**
     * Change the account-wide default. Every channel the user has not explicitly overridden moves
     * with it — those memberships store {@code DEFAULT}, not a copy of the old value — so this is
     * one row written, not one per channel.
     */
    @PutMapping("/api/profile/notify-default")
    public NotifyLevelDto setAccountDefault(@RequestBody @Valid SetNotifyLevelRequest body,
                                            Principal principal) {
        var me = currentUser.resolve(principal);
        return NotifyLevelDto.of(preferences.setAccountDefault(me, body.level()));
    }

    @GetMapping("/api/profile/notify-dm-default")
    public NotifyLevelDto accountDmDefault(Principal principal) {
        var me = currentUser.resolve(principal);
        return NotifyLevelDto.of(preferences.accountDmDefault(me));
    }

    /**
     * Change the account-wide default for direct and group conversations. Separate from the channel
     * default because the two want different answers — see {@code ConversationAlertPublisher} — and
     * separate here rather than as a mode on one endpoint so a client cannot set one while meaning
     * the other.
     */
    @PutMapping("/api/profile/notify-dm-default")
    public NotifyLevelDto setAccountDmDefault(@RequestBody @Valid SetNotifyLevelRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        return NotifyLevelDto.of(preferences.setAccountDmDefault(me, body.level()));
    }
}
