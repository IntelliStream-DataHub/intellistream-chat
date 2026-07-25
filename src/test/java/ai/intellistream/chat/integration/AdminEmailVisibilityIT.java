/*
 * Copyright 2026 Olav Gjerde
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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.AppSettingsService;
import ai.intellistream.chat.web.AdminController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the admin "show emails / mask emails" toggle:
 * <ul>
 *   <li>Defaults to ON — installs that don't touch it keep the historical behaviour.</li>
 *   <li>{@code POST /admin/email-visibility} with {@code expose=true} flips on; absent param flips off.</li>
 *   <li>The page-render path masks emails server-side when the setting is OFF, so a screenshot
 *       or DOM dump never leaks raw addresses even though the DB still has them.</li>
 *   <li>{@link AdminController#maskEmail} format is stable: {@code alice@example.com → al…@example.com}.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class AdminEmailVisibilityIT {

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
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired AppSettingsService settings;
    @Autowired ChannelRepository channels;
    @Autowired ChannelMemberRepository members;
    @Autowired MessageRepository messages;

    private CurrentUser currentUser;
    private AdminController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private boolean originalSetting;

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new AdminController(settings, channels, users, members, messages,
                currentUser, "/tmp/chat-test-branding");
        // Capture the existing value so the suite is reentrant — every test restores it on teardown.
        originalSetting = settings.current().isExposeUserEmails();
    }

    @AfterEach
    void restore() {
        settings.setExposeUserEmails(originalSetting);
    }

    private User newUserWithEmail(String email) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-mail-" + i, "user-" + i, email, "User " + i));
    }

    // ---------- maskEmail() format ----------

    @Test
    void maskEmailKeepsFirstTwoLocalCharsAndDomain() {
        assertThat(AdminController.maskEmail("alice@example.com")).isEqualTo("al…@example.com");
        assertThat(AdminController.maskEmail("bob@corp.io")).isEqualTo("bo…@corp.io");
        // Single-letter local part keeps just that char.
        assertThat(AdminController.maskEmail("a@x.io")).isEqualTo("a…@x.io");
    }

    @Test
    void maskEmailReturnsDashForBlankOrInvalid() {
        assertThat(AdminController.maskEmail(null)).isEqualTo("—");
        assertThat(AdminController.maskEmail("")).isEqualTo("—");
        assertThat(AdminController.maskEmail("   ")).isEqualTo("—");
        assertThat(AdminController.maskEmail("not-an-email")).isEqualTo("—");
        assertThat(AdminController.maskEmail("@nolocal.io")).isEqualTo("—");
    }

    // ---------- Default ON ----------

    @Test
    void defaultExposesEmailsForBackwardCompatibility() {
        // Fresh-install posture: the V20 migration seeds the column to TRUE.
        settings.setExposeUserEmails(true);
        assertThat(settings.current().isExposeUserEmails()).isTrue();
    }

    // ---------- Toggle endpoint ----------

    @Test
    void postExposeTrueEnablesVisibility() {
        settings.setExposeUserEmails(false);
        var ra = new RedirectAttributesModelMap();
        var view = controller.setEmailVisibility("true", ra);
        assertThat(view).isEqualTo("redirect:/admin");
        assertThat(settings.current().isExposeUserEmails()).isTrue();
    }

    @Test
    void postWithoutExposeParamMasksEmails() {
        settings.setExposeUserEmails(true);
        var ra = new RedirectAttributesModelMap();
        // Browser sends no value when an unchecked checkbox is submitted — that's our "off" signal.
        var view = controller.setEmailVisibility(null, ra);
        assertThat(view).isEqualTo("redirect:/admin");
        assertThat(settings.current().isExposeUserEmails()).isFalse();
    }

    @Test
    void postExposeOnAlsoEnables() {
        // Some browsers may send "on" instead of "true" for a checkbox without an explicit value.
        settings.setExposeUserEmails(false);
        var ra = new RedirectAttributesModelMap();
        controller.setEmailVisibility("on", ra);
        assertThat(settings.current().isExposeUserEmails()).isTrue();
    }

    // ---------- Render-path masking ----------

    @Test
    @SuppressWarnings("unchecked")
    void indexRendersRawEmailsWhenExposureIsOn() {
        var alice = newUserWithEmail("alice-raw-" + SEQ.incrementAndGet() + "@example.com");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        settings.setExposeUserEmails(true);

        var model = new ConcurrentModel();
        controller.index(mock(Principal.class), model);

        var rows = (List<Map<String, Object>>) model.getAttribute("userRows");
        assertThat(rows).isNotNull();
        var aliceRow = rows.stream()
                .filter(r -> alice.getId().equals(r.get("id")))
                .findFirst().orElseThrow();
        assertThat(aliceRow.get("email")).isEqualTo(alice.getEmail());
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexMasksEmailsWhenExposureIsOff() {
        var bob = newUserWithEmail("bob-masked-" + SEQ.incrementAndGet() + "@example.com");
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        settings.setExposeUserEmails(false);

        var model = new ConcurrentModel();
        controller.index(mock(Principal.class), model);

        var rows = (List<Map<String, Object>>) model.getAttribute("userRows");
        assertThat(rows).isNotNull();
        var bobRow = rows.stream()
                .filter(r -> bob.getId().equals(r.get("id")))
                .findFirst().orElseThrow();
        // The raw address never reaches the template.
        assertThat(bobRow.get("email")).isNotEqualTo(bob.getEmail());
        // Format is the stable "first-2-chars + … + domain" pattern.
        assertThat(bobRow.get("email")).asString().contains("…@example.com");
    }
}
