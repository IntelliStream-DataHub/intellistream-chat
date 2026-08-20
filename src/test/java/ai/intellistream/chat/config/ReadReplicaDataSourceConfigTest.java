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

package ai.intellistream.chat.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring contract for the optional read replica. No database and no container: Hikari pools built
 * through {@code DataSourceBuilder} are lazy, so a context can be assembled and inspected without
 * anything connecting. {@code ReadReplicaRoutingIT} is the other half — it proves a read-only
 * transaction actually lands on the second pool.
 *
 * <p>The case worth guarding hardest is the first one. "Optional" has to mean the feature is
 * absent, not merely idle: a deployment that never asked for a replica should not gain a
 * connection proxy, a second pool, or a new way to fail.
 */
class ReadReplicaDataSourceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(ReadReplicaDataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://primary.invalid:5432/chat",
                    "spring.datasource.username=chat",
                    "spring.datasource.password=chat");

    @Test
    void withoutTheFlagTheApplicationHasExactlyOnePoolAndNoProxy() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context).doesNotHaveBean("readerDataSource");
            assertThat(context).doesNotHaveBean("writerDataSource");
            // Boot's own auto-configuration, untouched — not a proxy that happens to point at
            // the primary twice.
            assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
        });
    }

    @Test
    void enablingItPutsTheProxyInFrontAndKeepsBothPoolsAddressable() {
        runner.withPropertyValues(
                        "ichat.datasource.replica.enabled=true",
                        "ichat.datasource.replica.url=jdbc:postgresql://replica.invalid:5432/chat")
                .run(context -> {
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(LazyConnectionDataSourceProxy.class);
                    assertThat(context.getBean("writerDataSource", HikariDataSource.class).getJdbcUrl())
                            .isEqualTo("jdbc:postgresql://primary.invalid:5432/chat");
                    assertThat(context.getBean("readerDataSource", HikariDataSource.class).getJdbcUrl())
                            .isEqualTo("jdbc:postgresql://replica.invalid:5432/chat");
                });
    }

    @Test
    void blankReplicaCredentialsInheritThePrimarysRatherThanConnectingAnonymously() {
        runner.withPropertyValues(
                        "ichat.datasource.replica.enabled=true",
                        "ichat.datasource.replica.url=jdbc:postgresql://replica.invalid:5432/chat")
                .run(context -> {
                    var reader = context.getBean("readerDataSource", HikariDataSource.class);
                    assertThat(reader.getUsername()).isEqualTo("chat");
                    assertThat(reader.getPassword()).isEqualTo("chat");
                });
    }

    @Test
    void replicaCredentialsOverrideThePrimarysWhenTheReplicaHasItsOwnRole() {
        runner.withPropertyValues(
                        "ichat.datasource.replica.enabled=true",
                        "ichat.datasource.replica.url=jdbc:postgresql://replica.invalid:5432/chat",
                        "ichat.datasource.replica.username=chat_ro",
                        "ichat.datasource.replica.password=ro-secret")
                .run(context -> {
                    var reader = context.getBean("readerDataSource", HikariDataSource.class);
                    assertThat(reader.getUsername()).isEqualTo("chat_ro");
                    assertThat(reader.getPassword()).isEqualTo("ro-secret");
                });
    }

    @Test
    void theReplicaPoolIsReadOnlyAtTheDriverRatherThanPerTransaction() {
        runner.withPropertyValues(
                        "ichat.datasource.replica.enabled=true",
                        "ichat.datasource.replica.url=jdbc:postgresql://replica.invalid:5432/chat")
                .run(context -> {
                    // LazyConnectionDataSourceProxy suppresses the per-transaction setReadOnly call
                    // once a dedicated read-only DataSource is configured, expecting the flag to be
                    // a pool default. Without this the replica pool would hand out connections that
                    // believe they are writable.
                    assertThat(context.getBean("readerDataSource", HikariDataSource.class).isReadOnly()).isTrue();
                    assertThat(context.getBean("writerDataSource", HikariDataSource.class).isReadOnly()).isFalse();
                });
    }

    @Test
    void theTwoPoolsAreSizedSeparately() {
        runner.withPropertyValues(
                        "spring.datasource.hikari.maximum-pool-size=8",
                        "ichat.datasource.replica.enabled=true",
                        "ichat.datasource.replica.url=jdbc:postgresql://replica.invalid:5432/chat",
                        "ichat.datasource.replica.hikari.maximum-pool-size=40")
                .run(context -> {
                    // Read traffic and write traffic are different shapes; a shared number would
                    // make the replica pointless at exactly the load that motivated adding one.
                    assertThat(context.getBean("writerDataSource", HikariDataSource.class)
                            .getMaximumPoolSize()).isEqualTo(8);
                    assertThat(context.getBean("readerDataSource", HikariDataSource.class)
                            .getMaximumPoolSize()).isEqualTo(40);
                });
    }

    @Test
    void enabledWithNoUrlFailsTheBootRatherThanQuietlyServingReadsFromThePrimary() {
        runner.withPropertyValues("ichat.datasource.replica.enabled=true").run(context -> {
            // The silent fallback is the bad outcome: it looks like it worked and shows up months
            // later as a primary that never got any relief.
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("ichat.datasource.replica.enabled=true but "
                            + "ichat.datasource.replica.url is not set");
        });
    }
}
