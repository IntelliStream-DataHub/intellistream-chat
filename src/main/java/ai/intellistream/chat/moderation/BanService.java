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

import ai.intellistream.chat.domain.AdminAudit;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Suspending and restoring accounts.
 *
 * <p>Suspension is three things happening together, and the order between them is the whole design:
 * <ol>
 *   <li><b>The in-memory registry goes first.</b> {@link SuspensionRegistry} is what the WebSocket
 *       and servlet paths actually consult, and it is updated <em>before</em> the row is written so
 *       there is no instant in which the ban has been decided but is not yet being enforced. If the
 *       transaction then rolls back, a synchronization puts the registry back — the registry is
 *       allowed to be briefly stricter than the database, never briefly laxer.</li>
 *   <li><b>The open sockets are closed.</b> Blocking the next frame is not the same as ending the
 *       session; see {@link SuspendedSessionEvictor}.</li>
 *   <li><b>The audit row is written inline</b>, not after commit, because {@code AuditService}
 *       writes in its own transaction precisely so that the record of an attempted action survives
 *       the action rolling back.</li>
 * </ol>
 *
 * <h2>Two guards, and why they are the defensible ones</h2>
 *
 * <p><b>An admin cannot suspend themselves.</b> A suspended admin is locked out of the admin
 * console by the same enforcement as everyone else, so this is not a recoverable mistake — it is a
 * one-click, irreversible-from-the-UI lockout of the person clicking.
 *
 * <p><b>An admin cannot suspend another admin.</b> The same lockout argument applies with more
 * force: two administrators can permanently brick each other's access, and if every admin is
 * suspended the only way back is hand-written SQL. It is also the wrong tool. Admin status here is
 * a cached mirror of a Keycloak realm role, and Keycloak is the source of truth for identity — the
 * answer to a rogue or compromised administrator is to strip the role and disable the account
 * there, which stops them getting a token at all rather than merely stopping them using this app.
 * Once demoted, they are an ordinary account and suspendable here. Note this reads the cached
 * {@code admin} column and is still safe: the column is refreshed at every login, and the only way
 * it can be stale is "promoted in Keycloak but not yet logged in", i.e. not yet an administrator of
 * this application. A stale value can only make the guard refuse a suspension, never permit one.
 *
 * <h2>The Keycloak half</h2>
 *
 * <p>Suspension is two locks, and this drives both: the local flag stops a principal <em>acting</em>
 * here immediately, and {@link KeycloakAdminClient} stops them <em>getting a new token</em> at all.
 * Neither is sufficient — an access token already issued stays cryptographically valid until it
 * expires no matter what Keycloak is told, and a Keycloak that is never told keeps handing out fresh
 * ones. The write-through is off by default (it needs a service account with {@code manage-users}),
 * returns a result rather than throwing, and never blocks the ban: an administrator pressing
 * "suspend" gets the local effect regardless, and the audit row says which halves took. The
 * consequence of leaving it off is worth stating plainly — the account remains able to log in to
 * every other client in the realm, and to this one, where it will simply be refused at the door.
 *
 * <p>That call is made inside the transaction so its outcome can go into the audit row that
 * describes the suspension. It holds a pooled connection for the duration of an HTTP call bounded by
 * the client's own timeout, which is a poor trade on a hot path and a fine one here: this runs when
 * a human presses a button, a handful of times a year.
 */
@Service
public class BanService {

    private static final Logger log = LoggerFactory.getLogger(BanService.class);

    /** {@code users.suspension_note} is varchar(500); truncate rather than fail the ban on it. */
    private static final int MAX_NOTE = 500;

    /**
     * {@code admin_audit.detail} is varchar(1000), and the pieces that go into it are not all ours —
     * a Keycloak failure detail carries whatever the IdP said. An over-long value would fail the
     * insert, and {@code AuditService} swallows that failure, so the row would simply not be there.
     */
    private static final int MAX_DETAIL = 1000;

    private final UserRepository users;
    private final AuditService audit;
    private final SuspensionRegistry suspensions;
    private final SuspendedSessionEvictor evictor;
    private final KeycloakAdminClient keycloak;

    public BanService(UserRepository users,
                      AuditService audit,
                      SuspensionRegistry suspensions,
                      SuspendedSessionEvictor evictor,
                      KeycloakAdminClient keycloak) {
        this.users = users;
        this.audit = audit;
        this.suspensions = suspensions;
        this.evictor = evictor;
        this.keycloak = keycloak;
    }

    /**
     * Suspend {@code target}. Idempotent: re-suspending an already-suspended account changes
     * nothing and writes no second audit row, so a double-click on a slow admin page cannot rewrite
     * the original timestamp and reason — those are the record.
     *
     * @param admin  the administrator performing the action (never null)
     * @param target the account to suspend; re-read inside the transaction, so a detached or stale
     *               instance from the caller is fine
     * @param note   why, for other administrators; truncated to 500 chars, never shown to the user
     * @return the managed, now-suspended user
     */
    @Transactional
    public User suspend(User admin, User target, String note) {
        var managed = load(admin, target);
        if (Objects.equals(admin.getId(), managed.getId())) {
            throw new PublicBadRequestException("You cannot suspend your own account.");
        }
        if (managed.isAdmin()) {
            throw new PublicBadRequestException(
                    "Administrators cannot be suspended here — remove their admin role in Keycloak first.");
        }
        if (managed.isSuspended()) {
            return managed;
        }

        // Registry before the write; see the class javadoc on ordering. The undo is registered
        // first so it is in place no matter where the rest of this method fails.
        undoOnRollback(() -> suspensions.unsuspend(managed));
        suspensions.suspend(managed);

        managed.suspend(admin, truncate(note));
        users.save(managed);

        int closed = evictor.closeAllFor(managed.getId());
        // Best-effort, and last: everything above has already taken effect if this hangs or fails.
        var idp = keycloak.disableAndLogout(managed.getSubject());

        log.info("Account suspended: {} by {} ({} live session(s) closed; {})",
                managed.getUsername(), admin.getUsername(), closed, idp.detail());
        audit.recordOnUser(admin, AdminAudit.SUSPEND, managed,
                detail("suspended; closed " + closed + " live session(s); " + idp.detail()
                        + (managed.getSuspensionNote() == null ? "" : "; note: " + managed.getSuspensionNote())));
        return managed;
    }

    /**
     * Lift a suspension. Idempotent in the same way: an account that is not suspended is left
     * alone and nothing is recorded.
     */
    @Transactional
    public User unsuspend(User admin, User target) {
        var managed = load(admin, target);
        if (!managed.isSuspended()) {
            return managed;
        }
        managed.unsuspend();
        users.save(managed);

        // Re-enable at the IdP, but do not log the account out again — a restored user has no
        // sessions worth ending, and the disable is the only thing a suspension turned off.
        var idp = keycloak.setEnabled(managed.getSubject(), true);

        log.info("Account restored: {} by {} ({})", managed.getUsername(), admin.getUsername(), idp.detail());
        audit.recordOnUser(admin, AdminAudit.UNSUSPEND, managed, detail("suspension lifted; " + idp.detail()));

        // The mirror of suspend(): the ban stays in force in memory until the row that lifts it has
        // actually committed. A rollback here leaves the registry saying "suspended", which is what
        // the database still says too.
        afterCommit(() -> suspensions.unsuspend(managed));
        return managed;
    }

    /**
     * Re-read the target inside this transaction. Callers hand us whatever they resolved from the
     * request; the guards below and {@code User.suspend} both need the row Hibernate is managing,
     * and the guards in particular must not be evaluated against a stale copy of it.
     */
    private User load(User admin, User target) {
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(target, "target");
        if (target.getId() == null) {
            throw new ResourceNotFoundException("Unsaved user cannot be suspended");
        }
        return users.findById(target.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User " + target.getId() + " not found"));
    }

    private static String detail(String detail) {
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }

    private static String truncate(String note) {
        if (note == null) return null;
        var trimmed = note.strip();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= MAX_NOTE ? trimmed : trimmed.substring(0, MAX_NOTE);
    }

    /**
     * Run {@code work} once the surrounding transaction commits, or immediately when there is no
     * transaction — the same shape {@code UserService} uses for post-commit reindexing. The
     * no-transaction branch is not just for tests: it keeps this correct if the method is ever
     * called from a context that manages its own boundary.
     */
    private static void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { work.run(); }
        });
    }

    /** Run {@code undo} only if the surrounding transaction ends without committing. */
    private static void undoOnRollback(Runnable undo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) undo.run();
            }
        });
    }
}
