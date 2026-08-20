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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.web.dto.ChannelEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The one way a channel is destroyed from the web layer, shared by the channel page's
 * {@code DELETE /api/channels/{id}} and the admin console's form. Two entry points because a
 * workspace admin who is not a member of a PRIVATE channel never sees the channel page's controls
 * — one implementation because the three steps below have an order that is easy to get wrong, and
 * a second copy would drift.
 *
 * <p>{@code confirmName} is the typed-confirmation check, and it is enforced here rather than only in
 * the UI. Not because a caller with a bearer token needs protecting from themselves, but because this
 * is the one action in the application where a mistaken {@code id} cannot be walked back: an
 * off-by-one in a script deletes the wrong room and there is nothing to restore it from. Requiring
 * the name means the request has to agree with itself about which channel it means. Compared
 * case-insensitively and after trimming — the confirmation is a statement of intent, not a typing
 * test.
 *
 * <p>The order of the three steps is deliberate. Destroy first, so nothing is announced that did not
 * happen. Then broadcast, so open clients learn about it. Then revoke, because the frame that tells
 * them travels on the subscription being revoked — the reverse order silently cuts a client off and
 * leaves it showing a channel that no longer exists.
 *
 * <p>Authorisation is {@link ChannelService#destroy}'s: workspace admin only, never the channel role.
 */
@Component
public class ChannelDestruction {

    private final ChannelService channelService;
    private final SimpMessagingTemplate broker;

    public ChannelDestruction(ChannelService channelService, SimpMessagingTemplate broker) {
        this.channelService = channelService;
        this.broker = broker;
    }

    /**
     * @throws PublicBadRequestException when {@code confirmName} does not name {@code channel}
     * @throws org.springframework.security.access.AccessDeniedException when {@code me} is not a
     *         workspace admin (from {@link ChannelService#destroy})
     */
    public void destroy(Channel channel, User me, String confirmName) {
        if (!confirms(channel, confirmName)) {
            throw new PublicBadRequestException("Type the channel's name exactly to confirm deletion.");
        }
        var id = channel.getId();
        channelService.destroy(channel, me);
        broker.convertAndSend("/topic/channels/" + id, ChannelEvent.deleted(id));
        channelService.revokeAllSubscriptions(id);
    }

    static boolean confirms(Channel channel, String confirmName) {
        return confirmName != null
                && confirmName.trim().equalsIgnoreCase(channel.getName().trim());
    }
}
