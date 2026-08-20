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
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.web.dto.ChannelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The typed-name confirmation and the destroy → broadcast → revoke order, pinned once because two
 * controllers now share this and either could be reached by a script with the wrong id.
 */
class ChannelDestructionTest {

    private ChannelService channelService;
    private SimpMessagingTemplate broker;
    private ChannelDestruction destruction;
    private Channel channel;
    private User admin;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        broker = mock(SimpMessagingTemplate.class);
        destruction = new ChannelDestruction(channelService, broker);
        admin = new User("sub-admin", "admin", "admin@example.test", "Admin");
        channel = new Channel("ops-archive", "Ops Archive", null, ChannelType.PRIVATE, admin);
        ReflectionTestUtils.setField(channel, "id", 42L);
    }

    @Test
    void wrongNameIsRefusedBeforeAnythingHappens() {
        assertThatThrownBy(() -> destruction.destroy(channel, admin, "Ops"))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() -> destruction.destroy(channel, admin, null))
                .isInstanceOf(PublicBadRequestException.class);
        verifyNoInteractions(channelService);
        verifyNoInteractions(broker);
    }

    @Test
    void nameIsAStatementOfIntentNotATypingTest() {
        assertThat(ChannelDestruction.confirms(channel, "  ops archive ")).isTrue();
        assertThat(ChannelDestruction.confirms(channel, "OPS ARCHIVE")).isTrue();
        assertThat(ChannelDestruction.confirms(channel, "ops-archive")).isFalse();
    }

    @Test
    void destroysThenBroadcastsThenRevokesInThatOrder() {
        destruction.destroy(channel, admin, "Ops Archive");

        var order = inOrder(channelService, broker);
        order.verify(channelService).destroy(channel, admin);
        order.verify(broker).convertAndSend(eq("/topic/channels/42"), any(ChannelEvent.class));
        order.verify(channelService).revokeAllSubscriptions(42L);
        verify(channelService, never()).revokeAllSubscriptions(0L);
    }
}
