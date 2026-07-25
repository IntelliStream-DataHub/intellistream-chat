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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.domain.User;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is suspended, in memory, so that asking costs a hash lookup instead of a query.
 *
 * <p><b>Why this exists at all.</b> Two enforcement points cannot afford the database. The STOMP
 * inbound interceptor runs on the message send path, which is deliberately query-free (see
 * AGENT.md) — a {@code select} per frame would undo the work that made it fast. The servlet filter
 * runs before the request reaches anything that resolves a domain {@code User}, so it has a token
 * and nothing else; resolving one there would double the user lookup every page load already pays.
 *
 * <p><b>Why it is safe to answer from memory.</b> The set is seeded from {@code users} at startup
 * and mutated by {@link BanService} in the same call that writes the row, in an order that always
 * errs towards the ban being in force: it goes {@code true} <em>before</em> the update and back to
 * {@code false} only <em>after</em> the unsuspend commits. Nothing else in the application writes
 * {@code suspended_at}. And it is not the last word — {@code CurrentUser.resolve} re-checks the row
 * it just loaded, so a request that reaches a controller is judged on database state regardless of
 * what this says.
 *
 * <p><b>Single-process state</b>, like {@code RateLimiter} and {@code PresenceTracker}. A second
 * instance would not learn about a ban issued on the first until it restarted; that is one more
 * item for the same shared-state migration those two already need, and until then the
 * {@code CurrentUser} re-check is what keeps a second node correct on the HTTP path (its STOMP
 * sessions would keep running, which is the reason this app is single-instance today).
 *
 * <p>Keyed twice on purpose. The WebSocket path holds a domain {@code User} and knows the id; the
 * servlet filter holds an OIDC/JWT principal and knows the subject. Storing both spares each side a
 * lookup to translate into the other's key.
 */
@Component
public class SuspensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SuspensionRegistry.class);

    private final Set<Long> suspendedIds = ConcurrentHashMap.newKeySet();
    private final Set<String> suspendedSubjects = ConcurrentHashMap.newKeySet();

    /**
     * Fast path for the frame interceptor: a single volatile read tells it that nobody at all is
     * suspended, which is the state of a healthy deployment essentially all of the time. Maintained
     * only inside the synchronized mutators, so it can never claim "nobody" while the set is
     * non-empty — a plain {@code isEmpty()} on a concurrent map can, under interleaved writers, and
     * that failure direction is the one that lets a banned user keep talking.
     */
    private volatile boolean anySuspended = false;

    private final JdbcTemplate jdbc;

    public SuspensionRegistry(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Load the suspended set from the database once the context is up.
     *
     * <p>On {@code ApplicationReadyEvent} rather than {@code @PostConstruct}: this reads a column
     * that Flyway adds, and Flyway is ordered before the {@code EntityManagerFactory}, not before
     * every bean. Initialising here also means the request path is open for the few microseconds
     * before the query returns — harmless, because {@code CurrentUser} independently rejects a
     * suspended row, and because no WebSocket session can predate startup.
     *
     * <p>Two columns of a small, indexed set of rows ({@code ix_users_suspended} is partial for
     * exactly this), read with JDBC rather than JPA because nothing here wants a managed entity.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedFromDatabase() {
        try {
            var rows = jdbc.queryForList("select id, subject from users where suspended_at is not null");
            synchronized (this) {
                for (var row : rows) {
                    var id = ((Number) row.get("id")).longValue();
                    suspendedIds.add(id);
                    var subject = (String) row.get("subject");
                    if (subject != null) suspendedSubjects.add(subject);
                }
                anySuspended = !suspendedIds.isEmpty();
            }
            if (!rows.isEmpty()) {
                log.info("Suspension enforcement active for {} account(s)", rows.size());
            }
        } catch (RuntimeException e) {
            // Loud, and not fatal. Starting with an empty set means suspended users get the
            // generic 403 from CurrentUser instead of the explanatory one from the filter; it
            // does not mean they get in.
            log.error("Could not load the suspended account set — HTTP enforcement will fall back "
                    + "to the per-request database check until the next restart", e);
        }
    }

    /**
     * Mutators are synchronized, readers are not. A ban is a human action a handful of times a
     * year, so serialising them costs nothing and makes {@link #anySuspended} exact; the reads that
     * matter (every STOMP frame, every request) stay lock-free.
     */
    public synchronized void suspend(User user) {
        if (user == null || user.getId() == null) return;
        suspendedIds.add(user.getId());
        if (user.getSubject() != null) suspendedSubjects.add(user.getSubject());
        anySuspended = true;
    }

    public synchronized void unsuspend(User user) {
        if (user == null || user.getId() == null) return;
        suspendedIds.remove(user.getId());
        if (user.getSubject() != null) suspendedSubjects.remove(user.getSubject());
        anySuspended = !suspendedIds.isEmpty();
    }

    /** True when at least one account is suspended — the guard that keeps the hot path free. */
    public boolean anySuspended() {
        return anySuspended;
    }

    /** For the WebSocket path, which holds the domain user cached on the STOMP session. */
    public boolean isSuspended(Long userId) {
        return anySuspended && userId != null && suspendedIds.contains(userId);
    }

    /** For the servlet path, which holds only the token's {@code sub} claim. */
    public boolean isSuspendedSubject(String subject) {
        return anySuspended && subject != null && suspendedSubjects.contains(subject);
    }

    /** Visible for tests / operational reset. */
    public synchronized void clear() {
        suspendedIds.clear();
        suspendedSubjects.clear();
        anySuspended = false;
    }

    @PreDestroy
    void logOnShutdown() {
        if (anySuspended) {
            // The set is rebuilt from the database on the way back up; say so, because "the bans
            // survive a restart" is the question an operator asks at exactly this moment.
            log.info("Shutting down with {} suspended account(s); the set is reloaded from the "
                    + "database at startup", suspendedIds.size());
        }
    }
}
