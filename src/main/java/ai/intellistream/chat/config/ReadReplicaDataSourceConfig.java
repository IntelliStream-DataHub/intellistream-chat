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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

/**
 * Optional second connection pool against a Postgres read replica, so read-only transactions
 * leave the primary alone.
 *
 * <p><b>Off unless asked for.</b> The whole class is gated on
 * {@code ichat.datasource.replica.enabled=true}. Without it none of these beans exist, Boot's
 * {@code DataSourceAutoConfiguration} builds the single pool it always has, and nothing about the
 * application changes — the same shape as the optional Vault backend. With it,
 * {@code DataSourceAutoConfiguration} backs off (it is {@code @ConditionalOnMissingBean(DataSource)})
 * and the three beans below replace it.
 *
 * <h2>What decides which pool a query uses</h2>
 *
 * <p>{@code @Transactional(readOnly = true)} — nothing else. There is no annotation to learn and no
 * thread-local to set. The chain is entirely Spring's own:
 *
 * <ol>
 *   <li>{@code HibernateJpaDialect.beginTransaction} sees {@code definition.isReadOnly()} and calls
 *       {@code DataSourceUtils.prepareConnectionForTransaction}, which is
 *       {@code connection.setReadOnly(true)}.</li>
 *   <li>That connection is a {@link LazyConnectionDataSourceProxy} handle holding no physical
 *       connection yet, so the call just records a flag.</li>
 *   <li>On the first actual statement the proxy fetches a real connection, and because the flag is
 *       set it fetches it from the DataSource handed to
 *       {@link LazyConnectionDataSourceProxy#setReadOnlyDataSource} — the replica.</li>
 * </ol>
 *
 * <p><b>The laziness is the mechanism, not a tuning choice.</b> Hibernate checks a connection out
 * when the transaction begins, which is *before* {@code AbstractPlatformTransactionManager} has
 * finished preparing it. Hand the EntityManagerFactory a pool directly and every transaction —
 * read-only or not — has already taken a primary connection by the time anyone could route it.
 * The proxy is what moves the checkout to first use, which is the first moment the read-only flag
 * exists.
 *
 * <p>Consequently a read that never joins a transaction — a raw {@code DataSource} connection or a
 * {@code JdbcTemplate} call — goes to the <b>primary</b>, because nothing ever set the flag. That
 * is the safe direction to be wrong in.
 *
 * <p><b>A Spring Data repository call is not that.</b> {@code SimpleJpaRepository} is annotated
 * {@code @Transactional(readOnly = true)} at class level, so {@code repo.findById(…)} invoked from
 * a non-transactional caller starts its own read-only transaction and lands on the replica —
 * including the handful of controllers that hold a repository directly. The rule is genuinely
 * "read-only transactions go to the replica"; it is only the raw-JDBC path that opts out by
 * never having a transaction at all.
 *
 * <h2>What must not run on the replica</h2>
 *
 * <p>A replica lags. Anything that reads in order to <em>delete</em> something, or that must see a
 * write this process just made, has to use a plain {@code @Transactional} instead — the
 * Lucene↔Postgres reconcile sweeps in {@code CleanupTasks} and {@code LuceneBootstrap} are the
 * cases in this codebase, and each says so at the annotation. Adding {@code readOnly = true} to
 * one of those turns replica lag into deleted search documents.
 *
 * <p>Flyway is pinned to the primary explicitly ({@code @FlywayDataSource}) rather than left to
 * pick up the {@code @Primary} bean, so a migration can never depend on which way the proxy
 * happened to route.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ichat.datasource.replica.enabled", havingValue = "true")
@EnableConfigurationProperties(ReadReplicaProperties.class)
public class ReadReplicaDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ReadReplicaDataSourceConfig.class);

    /**
     * Boot registers this itself from {@code DataSourceAutoConfiguration}, which has backed off
     * because this class contributes a {@link DataSource}. Declaring it here keeps
     * {@code spring.datasource.*} meaning exactly what it always did.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * The primary pool: every write, every non-transactional read, every migration. Binds
     * {@code spring.datasource.hikari.*} exactly as the auto-configuration would.
     */
    @Bean
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource writerDataSource(DataSourceProperties properties) {
        var writer = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        namePool(writer, "ichat-writer");
        return writer;
    }

    /**
     * The replica pool. Sized independently through {@code ichat.datasource.replica.hikari.*} —
     * read traffic and write traffic are different shapes, and the replica is usually the one that
     * wants the larger pool.
     *
     * <p>The pool is marked read-only rather than each transaction marking its connection:
     * {@link LazyConnectionDataSourceProxy} deliberately suppresses the per-transaction
     * {@code setReadOnly} call when a dedicated read-only DataSource is configured, expecting the
     * flag to be a pool default. That saves a round trip per transaction, and it means a write that
     * somehow reaches this pool fails on the JDBC driver rather than on the standby.
     */
    @Bean
    @ConfigurationProperties("ichat.datasource.replica.hikari")
    public HikariDataSource readerDataSource(DataSourceProperties primary, ReadReplicaProperties replica) {
        if (!replica.hasUrl()) {
            // Fail at boot, the way the Vault backend does. An operator who set enabled=true wants
            // reads on the replica; silently serving them from the primary would look like it
            // worked and show up months later as a primary that never got any relief.
            throw new IllegalStateException(
                    "ichat.datasource.replica.enabled=true but ichat.datasource.replica.url is not set");
        }
        var reader = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(primary.determineDriverClassName())
                .url(replica.getUrl())
                // Blank inherits the primary's credentials — a streaming replica normally carries
                // the same roles as the machine it replicates.
                .username(blankTo(replica.getUsername(), primary.determineUsername()))
                .password(blankTo(replica.getPassword(), primary.determinePassword()))
                .build();
        namePool(reader, "ichat-reader");
        // Set before the @ConfigurationProperties binding runs, so it is a default an operator can
        // still override with ichat.datasource.replica.hikari.read-only rather than a lock.
        reader.setReadOnly(true);
        return reader;
    }

    /**
     * The bean everything else injects: JPA, {@code JdbcTemplate}, the health endpoint. Lazy so the
     * read-only flag exists before a connection is taken (see the class javadoc), and carrying the
     * replica as its read-only variant.
     */
    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource writerDataSource, HikariDataSource readerDataSource) {
        var proxy = new LazyConnectionDataSourceProxy(writerDataSource);
        proxy.setReadOnlyDataSource(readerDataSource);
        return proxy;
    }

    /**
     * Registered as a bean rather than put on the configuration class, which cannot inject its own
     * beans without a cycle.
     */
    @Bean
    public ReplicaDiagnostics readReplicaDiagnostics(HikariDataSource readerDataSource) {
        return new ReplicaDiagnostics(readerDataSource);
    }

    /**
     * Says at startup what the routing actually resolved to. The failure this exists for is a
     * "replica" URL that points back at the primary — nothing misbehaves, the pool connects, and
     * the deployment simply runs two pools against one machine while believing it split the load.
     * {@code pg_is_in_recovery()} is the one question that distinguishes them.
     *
     * <p>Runs after startup and never fails it: a replica that is briefly unreachable is a reason
     * to log loudly, not to refuse to serve. The pool itself is lazy for the same reason.
     */
    public static class ReplicaDiagnostics {

        private final HikariDataSource reader;

        ReplicaDiagnostics(HikariDataSource reader) {
            this.reader = reader;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void reportReplicaRouting() {
            log.info("Read replica active — @Transactional(readOnly = true) routes to {} (pool max {})",
                    reader.getJdbcUrl(), reader.getMaximumPoolSize());
            try (var connection = reader.getConnection();
                 var statement = connection.prepareStatement("SELECT pg_is_in_recovery()");
                 var rs = statement.executeQuery()) {
                if (rs.next() && !rs.getBoolean(1)) {
                    log.warn("Replica at {} reports pg_is_in_recovery() = false — it is not a standby. "
                            + "Check ichat.datasource.replica.url; as configured, read and write pools "
                            + "are both hitting the same server.", reader.getJdbcUrl());
                }
            } catch (Exception e) {
                log.warn("Could not verify the read replica at {} — read-only transactions will fail "
                        + "until it is reachable", reader.getJdbcUrl(), e);
            }
        }
    }

    /** Leave an operator-supplied {@code pool-name} alone; replace Hikari's anonymous default. */
    private static void namePool(HikariDataSource ds, String name) {
        if (ds.getPoolName() == null || ds.getPoolName().startsWith("HikariPool-")) {
            ds.setPoolName(name);
        }
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
