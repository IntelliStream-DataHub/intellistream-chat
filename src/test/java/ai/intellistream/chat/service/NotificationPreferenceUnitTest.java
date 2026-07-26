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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelMember;
import ai.intellistream.chat.domain.ChannelRole;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import org.junit.jupiter.api.Test;

import static ai.intellistream.chat.domain.NotificationLevel.ALL;
import static ai.intellistream.chat.domain.NotificationLevel.DEFAULT;
import static ai.intellistream.chat.domain.NotificationLevel.MENTIONS;
import static ai.intellistream.chat.domain.NotificationLevel.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The inheritance rule, tested where it lives: on the enum and the two entities, with no database.
 * The integration sibling ({@code NotificationLevelIT}) proves the same behaviour survives being
 * written to and read back from Postgres.
 */
class NotificationPreferenceUnitTest {

    private static User user() {
        return new User("sub-1", "alice", "alice@example.com", "Alice");
    }

    private static ChannelMember membership(User u) {
        var channel = new Channel("general", "General", null, ChannelType.PUBLIC, u);
        return new ChannelMember(channel, u, ChannelRole.MEMBER);
    }

    @Test
    void shipsAsMentionsAndInheriting() {
        var alice = user();
        assertThat(alice.getNotifyDefault()).isEqualTo(MENTIONS);

        var member = membership(alice);
        assertThat(member.getNotifyLevel()).isEqualTo(DEFAULT);
        assertThat(member.followsAccountDefault()).isTrue();
        assertThat(member.effectiveNotifyLevel(alice.getNotifyDefault())).isEqualTo(MENTIONS);
    }

    /**
     * The whole point of storing {@code DEFAULT} rather than a snapshot: change the account
     * default and an un-overridden channel moves with it, without anything touching the
     * membership row.
     */
    @Test
    void changingTheAccountDefaultMovesAnInheritingChannel() {
        var alice = user();
        var member = membership(alice);

        alice.chooseNotifyDefault(ALL);

        assertThat(member.getNotifyLevel()).isEqualTo(DEFAULT);
        assertThat(member.effectiveNotifyLevel(alice.getNotifyDefault())).isEqualTo(ALL);
    }

    /** ...and an explicitly-set channel does not move. */
    @Test
    void changingTheAccountDefaultLeavesAnOverriddenChannelAlone() {
        var alice = user();
        var member = membership(alice);
        member.chooseNotifyLevel(MENTIONS);

        alice.chooseNotifyDefault(ALL);

        assertThat(member.getNotifyLevel()).isEqualTo(MENTIONS);
        assertThat(member.followsAccountDefault()).isFalse();
        assertThat(member.effectiveNotifyLevel(alice.getNotifyDefault())).isEqualTo(MENTIONS);
    }

    /** Mute is NONE, and NONE is an override like any other — nothing at account level unmutes it. */
    @Test
    void mutedChannelStaysMutedWhateverTheAccountDefaultBecomes() {
        var alice = user();
        var member = membership(alice);
        member.chooseNotifyLevel(NONE);

        for (var level : new NotificationLevel[]{ALL, MENTIONS, NONE}) {
            alice.chooseNotifyDefault(level);
            assertThat(member.effectiveNotifyLevel(alice.getNotifyDefault())).isEqualTo(NONE);
        }
    }

    @Test
    void choosingDefaultOnAChannelGoesBackToInheriting() {
        var alice = user();
        alice.chooseNotifyDefault(ALL);
        var member = membership(alice);
        member.chooseNotifyLevel(NONE);

        member.chooseNotifyLevel(DEFAULT);

        assertThat(member.followsAccountDefault()).isTrue();
        assertThat(member.effectiveNotifyLevel(alice.getNotifyDefault())).isEqualTo(ALL);

        member.chooseNotifyLevel(NONE);
        member.followAccountDefault();
        assertThat(member.effectiveNotifyLevel(alice.getNotifyDefault())).isEqualTo(ALL);
    }

    @Test
    void accountDefaultRefusesInherit() {
        var alice = user();
        assertThatThrownBy(() -> alice.chooseNotifyDefault(DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing above it");
        assertThatThrownBy(() -> alice.chooseNotifyDefault(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(alice.getNotifyDefault()).isEqualTo(MENTIONS);
    }

    @Test
    void channelLevelRefusesNull() {
        var member = membership(user());
        assertThatThrownBy(() -> member.chooseNotifyLevel(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A concrete level is its own answer; a missing/incoherent account default falls back safely. */
    @Test
    void resolutionIsTotal() {
        assertThat(ALL.resolvedAgainst(NONE)).isEqualTo(ALL);
        assertThat(NONE.resolvedAgainst(ALL)).isEqualTo(NONE);
        assertThat(MENTIONS.resolvedAgainst(null)).isEqualTo(MENTIONS);
        assertThat(DEFAULT.resolvedAgainst(null)).isEqualTo(MENTIONS);
        assertThat(DEFAULT.resolvedAgainst(DEFAULT)).isEqualTo(MENTIONS);
        assertThat(NotificationLevel.ACCOUNT_FALLBACK).isEqualTo(MENTIONS);
    }
}
