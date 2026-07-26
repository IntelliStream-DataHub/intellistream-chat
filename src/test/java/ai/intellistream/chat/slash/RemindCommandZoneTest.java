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

package ai.intellistream.chat.slash;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ReminderRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * "at 14:00" resolved in the server's zone, so a reminder set by anyone sitting elsewhere fired at
 * the wrong hour — and the confirmation, computed the same wrong way, agreed with it. These pin the
 * resolution order (user's choice, then their IdP's claim, then the configured default) and prove
 * the parse happens somewhere other than wherever the build runs.
 */
class RemindCommandZoneTest {

    /** 2026-07-26T00:00:00Z — 09:00 in Tokyo, 20:00 the previous day in New York. */
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final ChannelService channels = mock(ChannelService.class);
    private final UserService users = mock(UserService.class);
    private final ReminderRepository reminders = mock(ReminderRepository.class);

    private RemindCommand command(ZoneId defaultZone) {
        return new RemindCommand(channels, users, reminders,
                Clock.fixed(NOW, ZoneId.of("UTC")), defaultZone);
    }

    private static User user() {
        return user(1L, "alice", "Alice");
    }

    /** Ids matter: {@code parse} compares target to caller by id to spot "@myself". */
    private static User user(long id, String username, String displayName) {
        var u = new User("kc-" + username, username, username + "@example.com", displayName);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    // ------------------------------------------------------------- resolution order ----

    @Test
    void atTimeResolvesInTheZoneTheUserChose() {
        var alice = user();
        alice.chooseZone(TOKYO.getId());

        var parsed = command(ZoneId.of("UTC")).parse("at 14:00 to standup", alice);

        assertThat(parsed.fireAt().atZone(TOKYO).getHour()).isEqualTo(14);
        // Same instant, stated absolutely: 14:00+09:00 is 05:00Z, not 14:00Z.
        assertThat(parsed.fireAt()).isEqualTo(Instant.parse("2026-07-26T05:00:00Z"));
    }

    @Test
    void withoutAChoiceTheIdentityProvidersZoneIsUsed() {
        var alice = user();
        assertThat(alice.noteOidcZone(TOKYO.getId())).isTrue();

        var parsed = command(ZoneId.of("UTC")).parse("at 14:00 to standup", alice);

        assertThat(parsed.fireAt()).isEqualTo(Instant.parse("2026-07-26T05:00:00Z"));
    }

    @Test
    void anExplicitChoiceOutranksTheIdentityProvider() {
        // The user moved, or the IdP's guess was wrong. Their own answer has to win, and keep
        // winning across logins — which is why the two are stored separately.
        var alice = user();
        alice.noteOidcZone(TOKYO.getId());
        alice.chooseZone("UTC");

        var parsed = command(TOKYO).parse("at 14:00 to standup", alice);

        assertThat(parsed.fireAt()).isEqualTo(Instant.parse("2026-07-26T14:00:00Z"));
    }

    @Test
    void withNoZoneAnywhereTheConfiguredDefaultDecides() {
        var parsed = command(NEW_YORK).parse("at 14:00 to standup", user());

        // 14:00 EDT (UTC-4) on the 25th: at 20:00 local on the 25th, 14:00 has already gone by,
        // so it rolls to the 26th.
        assertThat(parsed.fireAt().atZone(NEW_YORK).getHour()).isEqualTo(14);
        assertThat(parsed.fireAt()).isEqualTo(Instant.parse("2026-07-26T18:00:00Z"));
    }

    @Test
    void relativeDurationsAreZoneIndependent() {
        // "in 5m" is an offset from an instant; no wall clock involved, so no zone can change it.
        var alice = user();
        alice.chooseZone(TOKYO.getId());

        assertThat(command(NEW_YORK).parse("in 5m to stretch", alice).fireAt())
                .isEqualTo(NOW.plusSeconds(300));
    }

    // ----------------------------------------------------------------- stored zones ----

    @Test
    void unknownZoneNamesFromTheIdentityProviderAreIgnoredNotThrown() {
        // This runs on every sign-in. A mis-typed Keycloak attribute must not lock anyone out.
        var alice = user();
        assertThat(alice.noteOidcZone("Mars/Olympus")).isFalse();
        assertThat(alice.noteOidcZone("")).isFalse();
        assertThat(alice.noteOidcZone(null)).isFalse();
        assertThat(alice.getOidcZoneId()).isNull();
        assertThat(alice.effectiveZone(NEW_YORK)).isEqualTo(NEW_YORK);
    }

    @Test
    void theSameClaimTwiceIsNotASecondWrite() {
        var alice = user();
        assertThat(alice.noteOidcZone(TOKYO.getId())).isTrue();
        assertThat(alice.noteOidcZone(TOKYO.getId())).isFalse();
    }

    @Test
    void chooseZoneRejectsWhatTheProfilePageCouldNotHaveOffered() {
        var alice = user();
        assertThatThrownBy(() -> alice.chooseZone("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class);
        // A fixed offset is not a zone: whoever picked it stops observing their own DST.
        assertThatThrownBy(() -> alice.chooseZone("+02:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearingTheChoiceGoesBackToAutomatic() {
        var alice = user();
        alice.noteOidcZone(TOKYO.getId());
        alice.chooseZone(NEW_YORK.getId());
        assertThat(alice.effectiveZone(ZoneId.of("UTC"))).isEqualTo(NEW_YORK);

        alice.chooseZone("   ");

        assertThat(alice.getZoneId()).isNull();
        assertThat(alice.effectiveZone(ZoneId.of("UTC"))).isEqualTo(TOKYO);
    }

    @Test
    void theConfiguredDefaultFallsBackToTheServerZoneWhenUnsetOrUnparseable() {
        // The promise the migration makes: an install that configures nothing keeps its behaviour.
        assertThat(User.zoneOrSystemDefault(null)).isEqualTo(ZoneId.systemDefault());
        assertThat(User.zoneOrSystemDefault("")).isEqualTo(ZoneId.systemDefault());
        assertThat(User.zoneOrSystemDefault("Mars/Olympus")).isEqualTo(ZoneId.systemDefault());
        assertThat(User.zoneOrSystemDefault(" Asia/Tokyo ")).isEqualTo(TOKYO);
    }

    // ---------------------------------------------------------------- confirmation ----

    @Test
    void theConfirmationIsPrivateAndNamesTheZoneItUsed() {
        var alice = user();
        alice.chooseZone(TOKYO.getId());

        var result = command(ZoneId.of("UTC")).execute(mock(Channel.class), alice,
                "me at 14:00 to standup");

        assertThat(result.message()).describedAs("nothing reaches the channel").isNull();
        assertThat(result.notice().level()).isEqualTo("info");
        assertThat(result.notice().text())
                .contains("today at 14:00")
                .contains("Asia/Tokyo")
                .contains("standup")
                .contains("direct message")
                .doesNotContain("will tag");
    }

    @Test
    void theConfirmationSaysWhoTheDirectMessageGoesTo() {
        var alice = user();
        var bob = user(2L, "bob", "Bob");
        org.mockito.Mockito.when(users.requireByUsername("bob")).thenReturn(bob);

        var text = command(ZoneId.of("UTC"))
                .execute(mock(Channel.class), alice, "@bob in 1h to review the PR")
                .notice().text();

        assertThat(text).contains("@bob").contains("direct message from you");
    }

    @Test
    void aTimeAlreadyPastTodayIsDescribedAsTomorrow() {
        var alice = user();
        alice.chooseZone(TOKYO.getId());   // 09:00 local at the frozen instant

        var text = command(ZoneId.of("UTC"))
                .execute(mock(Channel.class), alice, "me at 8am to early standup")
                .notice().text();

        assertThat(text).contains("tomorrow at 08:00");
    }
}
