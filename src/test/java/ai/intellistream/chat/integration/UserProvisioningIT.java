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

import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.service.UserService.ClaimView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The round-trip property the unit tests cannot reach: {@code upsert} must settle a row into
 * exactly the shape {@link UserService#findUnchanged} tests for.
 *
 * <p>If it does not, nothing breaks and nothing is logged — {@code CurrentUser} simply takes the
 * write path on every request of every session forever, which is the cost the fast path exists to
 * remove, reappearing silently. A mocked repository cannot catch that: the mock returns whatever
 * the test put in it, so the two halves agree by construction. Only a real round trip through JPA
 * and Postgres shows whether the row that comes back out matches the claims that went in.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class UserProvisioningIT {

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-user-provisioning");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserService users;

    private static ClaimView claims(String subject, String username, String email,
                                    String displayName, boolean admin) {
        return new ClaimView(subject, username, email, displayName, admin);
    }

    private void provision(ClaimView c) {
        users.upsert(c.subject(), c.username(), c.email(), c.displayName(), c.admin());
    }

    @Test
    void aFreshlyProvisionedAccountIsImmediatelyOnTheFastPath() {
        var c = claims("kc-fresh", "fresh", "fresh@example.com", "Fresh Person", false);

        assertThat(users.findUnchanged(c)).as("no row yet").isEmpty();
        provision(c);

        // The very next request must not write again. This is the property: one slow path, then
        // fast for the life of the account.
        assertThat(users.findUnchanged(c)).isPresent();
    }

    @Test
    void aClaimChangeTakesTheWritePathOnceAndThenSettles() {
        var before = claims("kc-renamed", "before", "before@example.com", "Before", false);
        provision(before);

        var after = claims("kc-renamed", "after", "after@example.com", "After", true);
        assertThat(users.findUnchanged(after)).as("claims moved, so the row must be rewritten").isEmpty();

        provision(after);
        assertThat(users.findUnchanged(after)).isPresent();
        assertThat(users.findUnchanged(before)).as("the old claims must not match any more").isEmpty();
    }

    @Test
    void anAccountWhoseHandleWasSuffixedForACollisionStillSettles() {
        provision(claims("kc-holder", "taken", "holder@example.com", "Holder", false));

        // A second principal wanting the same handle gets a suffix, so the stored username never
        // equals the claim and findUnchanged can never match it. That is correct but it would mean
        // this account pays the write path on every request forever — assert the suffix is at
        // least stable, so it is one steady query rather than a rename fight on each pass.
        var contender = claims("kc-contender", "taken", "contender@example.com", "Contender", false);
        provision(contender);
        var firstHandle = users.findUnchanged(
                claims("kc-contender", "taken", "contender@example.com", "Contender", false));
        assertThat(firstHandle).as("suffixed handle never equals the bare claim").isEmpty();

        provision(contender);
        provision(contender);
        // Stable: repeated provisioning must not keep appending suffixes.
        assertThat(users.findUnchanged(contender)).isEmpty();
    }

    @Test
    void aCaseOnlyDifferenceIsCorrectedOnceAndThenMatches() {
        provision(claims("kc-case", "Mixed", "case@example.com", "Case", false));

        var lower = claims("kc-case", "mixed", "case@example.com", "Case", false);
        assertThat(users.findUnchanged(lower)).as("stored handle is still 'Mixed'").isEmpty();

        // upsert lowercases the column; the collision check is skipped because the account already
        // holds the handle case-insensitively, which uk_users_username_lower guarantees is unique.
        provision(lower);
        assertThat(users.findUnchanged(lower)).isPresent();
    }
}
