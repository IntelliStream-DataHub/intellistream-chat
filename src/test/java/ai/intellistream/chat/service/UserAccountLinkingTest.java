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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A subject the app has never seen, arriving with the email of an account it has, is the
 * realm-migration case: the old realm brokered into a new dedicated one hands everyone a new
 * subject. {@link UserService#upsert} re-keys the existing account rather than minting a second
 * one — but only on the identity provider's word that the email is verified, only when exactly
 * one account carries it, and only while the switch is on. Each of those bars is a case below,
 * because each one that slipped would be a silent account takeover or a silent duplicate.
 */
class UserAccountLinkingTest {

    private static final String OLD_SUBJECT = "old-realm-subject";
    private static final String NEW_SUBJECT = "new-realm-subject";
    private static final String EMAIL = "alice@example.com";

    private UserRepository users;
    private User alice;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        alice = new User(OLD_SUBJECT, "alice", EMAIL, "Alice Example");
        when(users.findBySubject(NEW_SUBJECT)).thenReturn(Optional.empty());
        // uniqueUsername's collision probe: nobody else holds the handle.
        when(users.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    private UserService service(boolean linking) {
        return new UserService(users, mock(MessageRepository.class),
                mock(ConversationMessageRepository.class), mock(MessageIndexService.class), linking);
    }

    @Test
    void aVerifiedEmailMatchingOneAccountReKeysThatAccountInsteadOfInsertingASecond() {
        when(users.findAllByEmailIgnoreCase(EMAIL)).thenReturn(List.of(alice));

        var settled = service(true).upsert(NEW_SUBJECT, "alice", EMAIL, "Alice Example", false, true);

        assertThat(settled).isSameAs(alice);
        assertThat(alice.getSubject()).isEqualTo(NEW_SUBJECT);
        verify(users, never()).insertIgnore(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void emailMatchingIsCaseInsensitiveAndTheRowIsRefreshedLikeAnyLogin() {
        when(users.findAllByEmailIgnoreCase("Alice@Example.COM")).thenReturn(List.of(alice));

        service(true).upsert(NEW_SUBJECT, "alice", "Alice@Example.COM", "Alice E.", true, true);

        assertThat(alice.getSubject()).isEqualTo(NEW_SUBJECT);
        assertThat(alice.getDisplayName()).isEqualTo("Alice E.");
        assertThat(alice.isAdmin()).isTrue();
    }

    @Test
    void anUnverifiedEmailNeverLinks() {
        when(users.findAllByEmailIgnoreCase(EMAIL)).thenReturn(List.of(alice));
        when(users.findBySubject(NEW_SUBJECT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new User(NEW_SUBJECT, "alice-2", EMAIL, "Alice Example")));

        service(true).upsert(NEW_SUBJECT, "alice", EMAIL, "Alice Example", false, false);

        assertThat(alice.getSubject()).isEqualTo(OLD_SUBJECT);
        verify(users, never()).findAllByEmailIgnoreCase(anyString());
        verify(users).insertIgnore(eq(NEW_SUBJECT), anyString(), eq(EMAIL), eq("Alice Example"), eq(false));
    }

    @Test
    void twoAccountsAlreadySharingTheEmailAreLeftForAnOperatorNotGuessedBetween() {
        var twin = new User("some-other-subject", "alice-2", EMAIL, "Alice Example");
        when(users.findAllByEmailIgnoreCase(EMAIL)).thenReturn(List.of(alice, twin));
        when(users.findBySubject(NEW_SUBJECT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new User(NEW_SUBJECT, "alice-3", EMAIL, "Alice Example")));

        service(true).upsert(NEW_SUBJECT, "alice", EMAIL, "Alice Example", false, true);

        assertThat(alice.getSubject()).isEqualTo(OLD_SUBJECT);
        assertThat(twin.getSubject()).isEqualTo("some-other-subject");
        verify(users).insertIgnore(eq(NEW_SUBJECT), anyString(), eq(EMAIL), eq("Alice Example"), eq(false));
    }

    @Test
    void theSwitchOffMeansAVerifiedMatchStillInserts() {
        when(users.findAllByEmailIgnoreCase(EMAIL)).thenReturn(List.of(alice));
        when(users.findBySubject(NEW_SUBJECT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new User(NEW_SUBJECT, "alice-2", EMAIL, "Alice Example")));

        service(false).upsert(NEW_SUBJECT, "alice", EMAIL, "Alice Example", false, true);

        assertThat(alice.getSubject()).isEqualTo(OLD_SUBJECT);
        verify(users, never()).findAllByEmailIgnoreCase(anyString());
        verify(users).insertIgnore(eq(NEW_SUBJECT), anyString(), eq(EMAIL), eq("Alice Example"), eq(false));
    }

    @Test
    void aKnownSubjectNeverConsultsEmailAtAll() {
        when(users.findBySubject(NEW_SUBJECT)).thenReturn(Optional.of(alice));

        service(true).upsert(NEW_SUBJECT, "alice", EMAIL, "Alice Example", false, true);

        verify(users, never()).findAllByEmailIgnoreCase(anyString());
    }
}
