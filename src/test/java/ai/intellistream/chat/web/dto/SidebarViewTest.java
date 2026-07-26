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

package ai.intellistream.chat.web.dto;

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.NotificationLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sidebar's ordering and its subscription set — the two things a rendering change must not
 *  be able to change on its own. */
class SidebarViewTest {

    private static ChannelSidebarDto row(long id, String name) {
        return new ChannelSidebarDto(id, name.toLowerCase(), name, ChannelType.PUBLIC, true, false,
                0, 0, NotificationLevel.DEFAULT);
    }

    @Test
    void orderingIsCaseInsensitiveByNameAndTotal() {
        var rows = new ArrayList<>(List.of(
                row(3, "zebra"), row(1, "Alpha"), row(2, "beta"), row(9, "alpha"), row(4, "ALPHA")));

        rows.sort(ChannelSidebarDto.BY_NAME);

        // Same name, different ids: the id tiebreak makes the order total, so it cannot shuffle
        // between page loads.
        assertThat(rows).extracting(ChannelSidebarDto::id)
                .containsExactly(1L, 4L, 9L, 2L, 3L);
    }

    @Test
    void theSubscriptionSetIsEveryChannelInTheView() {
        var view = new SidebarView(List.of(row(4, "a"), row(7, "b"), row(11, "c")),
                NotificationLevel.MENTIONS);

        assertThat(view.channelIds()).isEqualTo("4,7,11");
    }

    @Test
    void anEmptyViewHasAnEmptySubscriptionSet() {
        var view = new SidebarView(List.of(), NotificationLevel.MENTIONS);

        assertThat(view.isEmpty()).isTrue();
        assertThat(view.channelIds()).isEmpty();
    }
}
