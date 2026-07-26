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
import ai.intellistream.chat.web.dto.ChannelSidebarDto.UnreadCue;
import org.junit.jupiter.api.Test;

import static ai.intellistream.chat.domain.NotificationLevel.ALL;
import static ai.intellistream.chat.domain.NotificationLevel.DEFAULT;
import static ai.intellistream.chat.domain.NotificationLevel.MENTIONS;
import static ai.intellistream.chat.domain.NotificationLevel.NONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The badge-versus-bold decision, and in particular the muted cases — which is where it stops being
 * obvious. There are two renderers of this table (Thymeleaf and index.js) and only one of them can
 * be unit-tested, so this is where the rule is pinned down.
 */
class ChannelSidebarDtoTest {

    private static ChannelSidebarDto row(long unread, long mentions, NotificationLevel level) {
        return new ChannelSidebarDto(1L, "deploys", "deploys", ChannelType.PUBLIC, true, false,
                unread, mentions, level);
    }

    @Test
    void nothingUnreadSaysNothing() {
        assertThat(row(0, 0, DEFAULT).unreadCue(MENTIONS)).isEqualTo(UnreadCue.NONE);
    }

    @Test
    void ordinaryUnreadIsBoldAndCarriesNoNumber() {
        assertThat(row(37, 0, DEFAULT).unreadCue(MENTIONS)).isEqualTo(UnreadCue.BOLD);
        assertThat(row(1, 0, ALL).unreadCue(MENTIONS)).isEqualTo(UnreadCue.BOLD);
        // An inherited ALL is still just unread — "notify me about everything" is about
        // interrupting, not about putting a count in the sidebar.
        assertThat(row(1, 0, DEFAULT).unreadCue(ALL)).isEqualTo(UnreadCue.BOLD);
    }

    @Test
    void aMentionGetsTheNumber() {
        assertThat(row(9, 2, DEFAULT).unreadCue(MENTIONS)).isEqualTo(UnreadCue.COUNT);
        assertThat(row(1, 1, MENTIONS).unreadCue(MENTIONS)).isEqualTo(UnreadCue.COUNT);
    }

    @Test
    void aMutedChannelKeepsItsCountAndStopsShouting() {
        var muted = row(120, 0, NONE);

        assertThat(muted.muted(MENTIONS)).isTrue();
        assertThat(muted.unreadCue(MENTIONS))
                .describedAs("no bold, no badge — but the count is still there on the row")
                .isEqualTo(UnreadCue.NONE);
        assertThat(muted.unreadCount())
                .describedAs("muting means stop telling me, not pretend nothing happened")
                .isEqualTo(120);
    }

    @Test
    void muteIsInheritedFromTheAccountDefaultToo() {
        // The channel says "follow my account default" and the account default is NONE. Resolving
        // is the whole reason a row's raw level is meaningless on its own.
        var row = row(4, 0, DEFAULT);

        assertThat(row.muted(NONE)).isTrue();
        assertThat(row.unreadCue(NONE)).isEqualTo(UnreadCue.NONE);
    }

    @Test
    void aMentionInAMutedChannelStillGetsItsBadge() {
        var muted = row(50, 3, NONE);

        // Deliberate exception: a badge makes no sound and raises no toast, so it does not
        // interrupt — it makes the thing findable later, which is exactly what you want when
        // somebody has called you by name in a channel you muted. The renderer dims it.
        assertThat(muted.muted(MENTIONS)).isTrue();
        assertThat(muted.unreadCue(MENTIONS)).isEqualTo(UnreadCue.COUNT);
    }

    @Test
    void anUnmutedChannelIsNeverReportedAsMuted() {
        assertThat(row(0, 0, MENTIONS).muted(NONE))
                .describedAs("an explicit per-channel level wins over the account default")
                .isFalse();
        assertThat(row(0, 0, ALL).muted(NONE)).isFalse();
        assertThat(row(0, 0, DEFAULT).muted(MENTIONS)).isFalse();
    }
}
