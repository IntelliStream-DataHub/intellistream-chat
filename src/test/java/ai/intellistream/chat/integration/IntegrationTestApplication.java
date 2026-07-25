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

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;

/**
 * Boots a slim Spring context for integration tests: JPA + Flyway + services,
 * skipping security/oauth2/web layers so the tests don't need a live Keycloak.
 *
 * Each integration test class points {@code ichat.search.lucene-dir} at its own
 * unique sub-directory (see the {@code @DynamicPropertySource} blocks in each IT)
 * so concurrent contexts don't fight over the Lucene index lock.
 */
@SpringBootApplication(
        scanBasePackages = {
                "ai.intellistream.chat.service",
                "ai.intellistream.chat.repository",
                "ai.intellistream.chat.search",
                "ai.intellistream.chat.slash"
        },
        exclude = {
                SecurityAutoConfiguration.class,
                ServletWebSecurityAutoConfiguration.class,
                OAuth2ClientAutoConfiguration.class,
                OAuth2ClientWebSecurityAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class,
                OAuth2ResourceServerWebSecurityAutoConfiguration.class
        }
)
@EntityScan("ai.intellistream.chat.domain")
@EnableJpaRepositories("ai.intellistream.chat.repository")
public class IntegrationTestApplication {

    /**
     * The web-layer auto-configs are excluded above (no STOMP / WebSocket beans), but
     * {@link ai.intellistream.chat.slash.ReminderScheduler} and the presence event listener still
     * need {@code SimpMessagingTemplate} for their broadcast call. A no-op mock keeps the
     * context wireable; tests that care about the broadcast contract verify it on the mocks
     * they construct manually (e.g. {@code new ChatWebSocketController(..., mock(...))}).
     */
    @Bean
    public SimpMessagingTemplate simpMessagingTemplate() {
        return mock(SimpMessagingTemplate.class);
    }

    /**
     * {@code MessageService} records per-stage write-path timings. The metrics package is
     * deliberately outside this context's component scan (the scan stays narrow on purpose), so
     * supply the collaborator directly against a throwaway registry — the tests assert on
     * behaviour, not on the timers.
     */
    @Bean
    public ai.intellistream.chat.metrics.WritePathMetrics writePathMetrics() {
        return new ai.intellistream.chat.metrics.WritePathMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}
