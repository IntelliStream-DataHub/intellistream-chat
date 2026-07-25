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
import ai.intellistream.chat.repository.AdminAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records administrative actions.
 *
 * <p>Every moderation path writes here. The trail is the difference between "an admin removed
 * 4,000 messages" being an accountable decision and being an unexplained gap in the archive, and
 * it matters more, not less, once there is a second administrator.
 *
 * <p><b>Writes in their own transaction</b> ({@link Propagation#REQUIRES_NEW}). A record of an
 * attempted action is worth keeping even when the action itself rolls back, and the alternative,
 * joining the caller's transaction, means the only trace of a failed ban disappears with it. The
 * cost is that a logged action is not proof the action succeeded, so callers record the outcome
 * in {@code detail} rather than relying on the row's existence.
 *
 * <p>Logging here never throws. An audit failure must not be able to abort the moderation action
 * an administrator is relying on; it is logged loudly instead.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AdminAuditRepository repository;

    public AuditService(AdminAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * @param actor      the administrator responsible, or null for a scheduled/system action
     * @param action     one of the {@code AdminAudit} action constants
     * @param targetUser the account acted upon, if the action is about an account
     * @param targetRef  a free-form reference (message id, channel slug) when it is not
     * @param detail     what actually happened, including counts and outcome
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User actor, String action, User targetUser, String targetRef, String detail) {
        try {
            repository.save(new AdminAudit(actor, action, targetUser, targetRef, detail));
        } catch (RuntimeException e) {
            // Deliberately swallowed: see the class javadoc. Loud, because a silently missing
            // audit trail is worse than a noisy one.
            log.error("AUDIT WRITE FAILED action={} actor={} target={} detail={}",
                    action,
                    actor != null ? actor.getUsername() : "system",
                    targetUser != null ? targetUser.getUsername() : targetRef,
                    detail, e);
        }
    }

    /** Convenience for account-scoped actions. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOnUser(User actor, String action, User targetUser, String detail) {
        record(actor, action, targetUser, null, detail);
    }

    @Transactional(readOnly = true)
    public Page<AdminAudit> recent(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminAudit> forUser(User user, Pageable pageable) {
        return repository.findByTargetUserOrderByCreatedAtDesc(user, pageable);
    }
}
