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

package ai.intellistream.chat.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One administrative action, recorded so that moderation is accountable.
 *
 * <p>Append-only by convention: nothing in the application updates or deletes a row. That is the
 * point. An audit trail an administrator can quietly edit answers no question worth asking, and
 * the question this exists to answer is "who removed these messages, and when, and why".
 *
 * <p>{@code actor} is nullable so a system action, the scheduled purge, is distinguishable from a
 * human one rather than being attributed to whoever last held an admin session.
 */
@Entity
@Table(name = "admin_audit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAudit {

    /** Action names. Constants rather than an enum: rows outlive code, and an enum that loses a
     *  constant turns old rows into a deserialisation failure instead of an unfamiliar string. */
    public static final String SUSPEND = "user.suspend";
    public static final String UNSUSPEND = "user.unsuspend";
    public static final String PURGE_MESSAGES = "user.messages.purge";
    public static final String RESTORE_MESSAGES = "user.messages.restore";
    public static final String DELETE_MESSAGE = "message.delete";
    public static final String QUOTA_SET = "user.quota.set";
    public static final String RETENTION_PURGE = "system.retention.purge";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(nullable = false, length = 64)
    private String action;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user")
    private User targetUser;

    @Column(name = "target_ref", length = 255)
    private String targetRef;

    @Column(length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AdminAudit(User actor, String action, User targetUser, String targetRef, String detail) {
        this.actor = actor;
        this.action = action;
        this.targetUser = targetUser;
        this.targetRef = targetRef;
        this.detail = detail;
    }
}
