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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.service.UserService.ClaimView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read-only fast path {@code CurrentUser} takes on every authenticated request.
 *
 * <p>Two things are being protected. The first is cost: resolving an unchanged principal must be
 * one {@code select} in a read-only transaction, not the two queries and the writable transaction
 * {@link UserService#upsert} costs — that is the whole point, and a regression would be invisible
 * because the behaviour stays correct. The second is that "unchanged" is judged on <em>every</em>
 * field the write path would set. A fast path that ignores one of them serves a stale row forever;
 * for {@code admin} that means a revoked role that never takes effect, which is why the
 * per-field cases below are spelled out one at a time rather than as a single happy path.
 */
class UserProvisioningFastPathTest {

    private static final String SUBJECT = "kc-subject-1";

    private UserRepository users;
    private UserService service;

    /** The claims Keycloak sends for a settled account, and the row that already matches them. */
    private static final ClaimView SETTLED =
            new ClaimView(SUBJECT, "alice", "alice@example.com", "Alice Example", false);

    private static User rowMatching(ClaimView claims) {
        return new User(claims.subject(), claims.username(), claims.email(), claims.displayName());
    }

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service = new UserService(users, mock(MessageRepository.class),
                mock(ConversationMessageRepository.class), mock(MessageIndexService.class));
    }

    @Test
    void anUnchangedAccountResolvesFromOneQueryAndNeverChecksTheHandle() {
        var existing = rowMatching(SETTLED);
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(existing));

        assertThat(service.findUnchanged(SETTLED)).containsSame(existing);

        // The second query is the one this exists to remove. findByUsernameIgnoreCase is the
        // collision check inside uniqueUsername, and an unchanged handle cannot collide with
        // anything but its own row.
        verify(users, never()).findByUsernameIgnoreCase(anyString());
    }

    @Test
    void anAccountWithNoRowYetFallsThroughToProvisioning() {
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.empty());

        assertThat(service.findUnchanged(SETTLED)).isEmpty();
    }

    @Test
    void aRevokedAdminRoleFallsThroughRatherThanBeingServedFromTheExistingRow() {
        var wasAdmin = rowMatching(SETTLED);
        wasAdmin.setAdmin(true);
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(wasAdmin));

        // SETTLED carries admin=false. Matching on anything less than every written field would
        // leave this account an administrator until something else happened to rewrite the row.
        assertThat(service.findUnchanged(SETTLED)).isEmpty();
    }

    @Test
    void aChangedDisplayNameFallsThrough() {
        var stale = rowMatching(SETTLED);
        stale.setDisplayName("Alice Old");
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(stale));

        assertThat(service.findUnchanged(SETTLED)).isEmpty();
    }

    @Test
    void aChangedEmailFallsThrough() {
        var stale = rowMatching(SETTLED);
        stale.setEmail("alice@old.example.com");
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(stale));

        assertThat(service.findUnchanged(SETTLED)).isEmpty();
    }

    @Test
    void aCaseOnlyHandleDifferenceIsARenameToPerformOnceNotAMatchToTolerate() {
        var mixedCase = rowMatching(SETTLED);
        mixedCase.setUsername("Alice");
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(mixedCase));

        // Falls through to upsert, which lowercases the column once; the request after that one
        // matches exactly and takes the fast path. Tolerating the difference here would leave the
        // stored handle disagreeing with the claim forever.
        assertThat(service.findUnchanged(SETTLED)).isEmpty();
    }

    @Test
    void theFallThroughSkipsTheCollisionCheckWhenTheHandleItselfIsUnchanged() {
        var existing = rowMatching(SETTLED);
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(existing));

        // Only the email moved, so this lands on the write path — but the handle is still the
        // account's own, and uk_users_username_lower (V2) makes at most one row match it. The
        // collision query could only come back with this same row.
        var updated = service.upsert(SUBJECT, "alice", "alice@new.example.com", "Alice Example", false);

        assertThat(updated.getEmail()).isEqualTo("alice@new.example.com");
        assertThat(updated.getUsername()).isEqualTo("alice");
        verify(users, never()).findByUsernameIgnoreCase(anyString());
    }

    @Test
    void anActualRenameStillPaysTheCollisionCheck() {
        var existing = rowMatching(SETTLED);
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(existing));
        when(users.findByUsernameIgnoreCase("bob")).thenReturn(Optional.empty());

        var renamed = service.upsert(SUBJECT, "bob", SETTLED.email(), SETTLED.displayName(), false);

        // The skip must not swallow the case it exists for: a handle this account does not
        // already hold can be held by somebody else, and that is what uniqueUsername resolves.
        assertThat(renamed.getUsername()).isEqualTo("bob");
        verify(users).findByUsernameIgnoreCase("bob");
    }

    @Test
    void aRenameOntoAHandleSomebodyElseHoldsIsSuffixed() {
        var existing = rowMatching(SETTLED);
        var otherPerson = new User("kc-subject-2", "bob", "bob@example.com", "Bob");
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(existing));
        when(users.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(otherPerson));

        var renamed = service.upsert(SUBJECT, "bob", SETTLED.email(), SETTLED.displayName(), false);

        assertThat(renamed.getUsername()).startsWith("bob-").isNotEqualTo("bob");
    }

    @Test
    void aNullEmailOnBothSidesStillCountsAsUnchanged() {
        var claims = new ClaimView(SUBJECT, "alice", null, "Alice Example", false);
        var existing = rowMatching(claims);
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(existing));

        // Keycloak realms without an email mapper send none. Comparing with == or equals() on a
        // null field would push every one of those accounts onto the write path on every request.
        assertThat(service.findUnchanged(claims)).containsSame(existing);
    }
}
