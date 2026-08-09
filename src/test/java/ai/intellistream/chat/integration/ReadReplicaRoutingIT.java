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

import ai.intellistream.chat.config.ReadReplicaDataSourceConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@code @Transactional(readOnly = true)} actually reaches the second pool, and that
 * nothing else does.
 *
 * <p>The routing chain is entirely inside Spring and Hibernate — {@code HibernateJpaDialect}
 * marks the transaction's connection read-only, {@code LazyConnectionDataSourceProxy} has not yet
 * fetched a physical one, and when it does it picks the replica because of that mark. Every link
 * is framework internals we don't own, which is exactly why this is asserted end to end rather
 * than reasoned about: a Boot upgrade that changes when the connection is checked out would
 * silently send every read back to the primary, and nothing else in the suite would notice.
 *
 * <p><b>One container, two pools.</b> A second Postgres would need the schema, Flyway and a
 * replication link to be genuinely a replica, and would still not prove anything this doesn't.
 * What the test needs is only to tell the two pools apart, so each is opened with its own
 * {@code ApplicationName} and asks Postgres which one it is talking on. Where the connections
 * come from is the question; whether the data differs is not.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import({ReadReplicaDataSourceConfig.class, ReadReplicaRoutingIT.ProbeConfig.class})
class ReadReplicaRoutingIT {

    private static final String WRITER = "ichat-writer-probe";
    private static final String READER = "ichat-reader-probe";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> tagged(WRITER));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The "replica" is the same server reached over a differently-tagged pool — see the class
        // javadoc for why that is the whole of what this test needs.
        registry.add("ichat.datasource.replica.enabled", () -> "true");
        registry.add("ichat.datasource.replica.url", () -> tagged(READER));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-replica-routing");
        TestLuceneDirs.register(registry);
    }

    private static String tagged(String applicationName) {
        var url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "ApplicationName=" + applicationName;
    }

    @Autowired Probe probe;
    @Autowired DataSource dataSource;

    @Test
    void readOnlyTransactionsAreServedByTheReplicaPool() {
        assertThat(probe.inReadOnlyTransaction()).isEqualTo(READER);
    }

    @Test
    void writableTransactionsStayOnThePrimary() {
        assertThat(probe.inWritableTransaction()).isEqualTo(WRITER);
    }

    @Test
    void rawJdbcWithNoTransactionAtAllStaysOnThePrimary() throws Exception {
        // Nothing marked the connection read-only, so the proxy has no reason to route it. Note
        // this is the raw-DataSource path specifically: a Spring Data repository call outside a
        // transaction is NOT this case — SimpleJpaRepository declares readOnly = true, so it
        // starts a read-only transaction of its own and does reach the replica.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT current_setting('application_name')");
             var rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(WRITER);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        Probe readReplicaProbe() {
            return new Probe();
        }
    }

    /**
     * Asks Postgres which pool the current transaction is talking on. A separate
     * {@code @Transactional} bean rather than test methods, because the routing decision is made
     * when the transaction begins — so the test class itself must not be transactional, and each
     * probe call has to start its own.
     */
    static class Probe {

        @PersistenceContext
        private EntityManager entityManager;

        @Transactional(readOnly = true)
        public String inReadOnlyTransaction() {
            return applicationName();
        }

        @Transactional
        public String inWritableTransaction() {
            return applicationName();
        }

        private String applicationName() {
            return (String) entityManager
                    .createNativeQuery("SELECT current_setting('application_name')")
                    .getSingleResult();
        }
    }
}
