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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.DateStyle;
import ai.intellistream.chat.domain.HourCycle;
import ai.intellistream.chat.i18n.TimeFormats;
import ai.intellistream.chat.i18n.ZoneSource;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V15 columns against a real Postgres: that they exist with the shapes JPA validates against,
 * that the enum check constraints accept every value the enums can produce, and that a detection
 * lands in its own column without disturbing an explicit choice.
 *
 * <p>Worth a container rather than a mocked repository because most of what can go wrong here is
 * schema-shaped — a missing default on a NOT NULL column, a check constraint that disagrees with
 * the enum, {@code ddl-auto=validate} refusing the mapping — and none of it is visible until an
 * application actually starts against the migration.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class UserTimePreferencesIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-time-prefs");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserService users;
    @Autowired UserRepository userRepository;

    private final TimeFormats formats = new TimeFormats("UTC");

    private ai.intellistream.chat.domain.User newUser() {
        var n = SEQ.incrementAndGet();
        return users.upsert("kc-tz-" + n, "tz" + n, "tz" + n + "@example.com", "Zone " + n, false);
    }

    @Test
    void aNewAccountShipsFollowingItsLocale() {
        var me = newUser();

        assertThat(me.getZoneId()).isNull();
        assertThat(me.getDetectedZoneId()).isNull();
        assertThat(me.getHourCycle()).isEqualTo(HourCycle.AUTO);
        assertThat(me.getDateStyle()).isEqualTo(DateStyle.AUTO);
        assertThat(me.isZonePromptDismissed()).isFalse();

        // Nothing stored, so Accept-Language is all there is — and for `nb` that is enough.
        assertThat(formats.zoneFor(me, Locale.forLanguageTag("nb")))
                .isEqualTo(ZoneId.of("Europe/Oslo"));
        assertThat(formats.sourceFor(me, Locale.forLanguageTag("nb")))
                .isEqualTo(ZoneSource.LOCALE);
    }

    @Test
    void aDetectionIsStoredAndRetiresThePrompt() {
        var me = newUser();

        assertThat(users.recordDetectedZone(me, "Asia/Tokyo")).isTrue();

        var reloaded = userRepository.findById(me.getId()).orElseThrow();
        assertThat(reloaded.getDetectedZoneId()).isEqualTo("Asia/Tokyo");
        // A zone we can now name is a question that no longer needs asking.
        assertThat(reloaded.isZonePromptDismissed()).isTrue();
        assertThat(formats.sourceFor(reloaded, Locale.ENGLISH)).isEqualTo(ZoneSource.DETECTED);

        // Unchanged on the second report, which is every page load after the first.
        assertThat(users.recordDetectedZone(reloaded, "Asia/Tokyo")).isFalse();
    }

    @Test
    void aDetectionNeverOverwritesAChoice() {
        var me = newUser();
        me.chooseZone("Europe/Oslo");
        userRepository.save(me);

        users.recordDetectedZone(me, "Asia/Tokyo");

        var reloaded = userRepository.findById(me.getId()).orElseThrow();
        assertThat(reloaded.getZoneId()).isEqualTo("Europe/Oslo");
        assertThat(reloaded.getDetectedZoneId()).isEqualTo("Asia/Tokyo");
        // Both stored, and the choice still wins — which is the point of them being two columns.
        assertThat(formats.zoneFor(reloaded, Locale.ENGLISH)).isEqualTo(ZoneId.of("Europe/Oslo"));
    }

    @Test
    void garbageFromTheBrowserIsIgnoredRatherThanStored() {
        var me = newUser();

        assertThat(users.recordDetectedZone(me, "Mars/Olympus")).isFalse();
        assertThat(users.recordDetectedZone(me, "")).isFalse();
        assertThat(users.recordDetectedZone(me, null)).isFalse();

        assertThat(userRepository.findById(me.getId()).orElseThrow().getDetectedZoneId()).isNull();
    }

    @Test
    void everyEnumValuePassesItsCheckConstraint() {
        var me = newUser();
        for (var cycle : HourCycle.values()) {
            for (var style : DateStyle.values()) {
                users.updateTimeFormat(me, cycle, style);
                userRepository.flush();
                var reloaded = userRepository.findById(me.getId()).orElseThrow();
                assertThat(reloaded.getHourCycle()).isEqualTo(cycle);
                assertThat(reloaded.getDateStyle()).isEqualTo(style);
            }
        }
    }

    @Test
    void thePromptCanBeWavedAwayWithoutChoosingAZone() {
        var me = newUser();
        users.dismissZonePrompt(me);

        var reloaded = userRepository.findById(me.getId()).orElseThrow();
        assertThat(reloaded.isZonePromptDismissed()).isTrue();
        assertThat(reloaded.getZoneId()).isNull();
        // Still unconfirmed — dismissal silences the banner, it does not invent an answer.
        assertThat(formats.sourceFor(reloaded, Locale.ENGLISH)).isEqualTo(ZoneSource.DEFAULT);
    }
}
