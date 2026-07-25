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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.domain.AdminAudit;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the suspension decisions: the two guards, idempotency, and the fact that a
 * suspension is not finished until the audit row is written and the live sockets are gone.
 *
 * <p>No transaction is active here, so {@code BanService}'s post-commit hooks run inline — which is
 * exactly what makes the registry effects observable without a Spring context.
 */
class BanServiceTest {

    private UserRepository users;
    private AuditService audit;
    private SuspensionRegistry registry;
    private SuspendedSessionEvictor evictor;
    private KeycloakAdminClient keycloak;
    private BanService service;

    private User admin;
    private User target;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        audit = mock(AuditService.class);
        registry = new SuspensionRegistry(mock(DataSource.class));
        evictor = mock(SuspendedSessionEvictor.class);
        keycloak = mock(KeycloakAdminClient.class);
        // The write-through is off in most deployments; that is the default this suite runs under.
        when(keycloak.disableAndLogout(any())).thenReturn(KeycloakAdminClient.Result.NOT_CONFIGURED);
        when(keycloak.setEnabled(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(KeycloakAdminClient.Result.NOT_CONFIGURED);
        service = new BanService(users, audit, registry, evictor, keycloak);

        admin = user(1L, "kc-admin", "root");
        admin.setAdmin(true);
        target = user(2L, "kc-bob", "bob");
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
    }

    private static User user(long id, String subject, String username) {
        var user = new User(subject, username, username + "@example.com", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void suspendMarksTheAccountAndRecordsWhoDidIt() {
        when(evictor.closeAllFor(2L)).thenReturn(3);

        var result = service.suspend(admin, target, "  spamming  ");

        assertThat(result.isSuspended()).isTrue();
        assertThat(result.getSuspendedBy()).isSameAs(admin);
        assertThat(result.getSuspensionNote()).isEqualTo("spamming");
        verify(users).save(target);
        verify(audit).recordOnUser(eq(admin), eq(AdminAudit.SUSPEND), eq(target),
                org.mockito.ArgumentMatchers.contains("closed 3 live session(s)"));
    }

    @Test
    void suspendClosesTheOpenSocketsAndBlocksFutureFrames() {
        service.suspend(admin, target, null);

        verify(evictor).closeAllFor(2L);
        // Both keys, because the two enforcement points look the account up differently.
        assertThat(registry.isSuspended(2L)).isTrue();
        assertThat(registry.isSuspendedSubject("kc-bob")).isTrue();
    }

    @Test
    void suspendIsIdempotent() {
        service.suspend(admin, target, "first");
        var firstStamp = target.getSuspendedAt();

        service.suspend(admin, target, "second");

        // The original timestamp and reason are the record; a second click must not rewrite them.
        assertThat(target.getSuspendedAt()).isEqualTo(firstStamp);
        assertThat(target.getSuspensionNote()).isEqualTo("first");
        verify(audit, never()).recordOnUser(any(), eq(AdminAudit.SUSPEND), any(),
                org.mockito.ArgumentMatchers.contains("second"));
    }

    @Test
    void anAdminCannotSuspendThemselves() {
        assertThatThrownBy(() -> service.suspend(admin, admin, "oops"))
                .isInstanceOf(PublicBadRequestException.class)
                .hasMessageContaining("your own account");

        assertThat(admin.isSuspended()).isFalse();
        verifyNoInteractions(audit, evictor, keycloak);
    }

    @Test
    void anAdminCannotSuspendAnotherAdmin() {
        var otherAdmin = user(3L, "kc-other", "other");
        otherAdmin.setAdmin(true);
        when(users.findById(3L)).thenReturn(Optional.of(otherAdmin));

        assertThatThrownBy(() -> service.suspend(admin, otherAdmin, "power struggle"))
                .isInstanceOf(PublicBadRequestException.class)
                .hasMessageContaining("Keycloak");

        assertThat(otherAdmin.isSuspended()).isFalse();
        assertThat(registry.anySuspended()).isFalse();
        verifyNoInteractions(audit, evictor, keycloak);
    }

    @Test
    void theNoteIsTruncatedToTheColumnWidth() {
        service.suspend(admin, target, "x".repeat(900));

        // varchar(500): truncating beats failing the ban on a database constraint.
        assertThat(target.getSuspensionNote()).hasSize(500);
    }

    @Test
    void unsuspendClearsTheStateAndRecordsIt() {
        service.suspend(admin, target, "spam");

        var result = service.unsuspend(admin, target);

        assertThat(result.isSuspended()).isFalse();
        assertThat(result.getSuspendedBy()).isNull();
        assertThat(result.getSuspensionNote()).isNull();
        assertThat(registry.isSuspended(2L)).isFalse();
        assertThat(registry.isSuspendedSubject("kc-bob")).isFalse();
        verify(audit).recordOnUser(eq(admin), eq(AdminAudit.UNSUSPEND), eq(target), any());
    }

    @Test
    void theBanIsPushedToKeycloakAndTheOutcomeIsRecorded() {
        when(keycloak.disableAndLogout("kc-bob"))
                .thenReturn(KeycloakAdminClient.Result.applied("Keycloak account disabled and sessions terminated"));

        service.suspend(admin, target, null);

        // The subject is the Keycloak user id; the sanitized username is not.
        verify(keycloak).disableAndLogout("kc-bob");
        verify(audit).recordOnUser(any(), eq(AdminAudit.SUSPEND), any(),
                org.mockito.ArgumentMatchers.contains("Keycloak account disabled"));
    }

    @Test
    void aFailedKeycloakWriteThroughStillLeavesTheAccountSuspendedHere() {
        when(keycloak.disableAndLogout("kc-bob"))
                .thenReturn(KeycloakAdminClient.Result.failed("Keycloak returned 403"));

        service.suspend(admin, target, null);

        // The local half is the half that always works; the audit row says the other one didn't.
        assertThat(target.isSuspended()).isTrue();
        assertThat(registry.isSuspended(2L)).isTrue();
        verify(audit).recordOnUser(any(), eq(AdminAudit.SUSPEND), any(),
                org.mockito.ArgumentMatchers.contains("403"));
    }

    @Test
    void unsuspendReEnablesTheKeycloakAccount() {
        service.suspend(admin, target, "spam");

        service.unsuspend(admin, target);

        verify(keycloak).setEnabled("kc-bob", true);
    }

    @Test
    void unsuspendingAnAccountThatIsNotSuspendedRecordsNothing() {
        service.unsuspend(admin, target);

        verifyNoInteractions(audit, keycloak);
        verify(users, never()).save(any());
    }
}
