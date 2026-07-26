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

import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MentionService;
import ai.intellistream.chat.service.NotificationPreferenceService;
import ai.intellistream.chat.web.ConversationAlertPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * The per-conversation notification level: what it stores, how it inherits, and what it actually
 * silences.
 *
 * <p>{@code NotificationLevelIT} is the channel sibling and the inherit semantics asserted here are
 * deliberately the same ones — a membership stores {@code DEFAULT}, not a snapshot, so changing the
 * account default moves everything un-overridden. What is new is the last section: a DM used to
 * always notify, so this is the first time a conversation can decline to.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationNotificationLevelIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ConversationService conversations;
    @Autowired NotificationPreferenceService preferences;
    @Autowired MentionService mentions;

    private SimpMessagingTemplate broker;
    private ConversationAlertPublisher alerts;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        broker = mock(SimpMessagingTemplate.class);
        alerts = new ConversationAlertPublisher(conversations, preferences, mentions, broker);
    }

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-nl-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n));
    }

    private void verifyAlerted(User recipient) {
        verify(broker).convertAndSendToUser(eq(recipient.getUsername()),
                eq("/queue/conversation-alerts"), any(Object.class));
    }

    private void verifyNotAlerted(User recipient) {
        verify(broker, never()).convertAndSendToUser(eq(recipient.getUsername()), anyString(),
                any(Object.class));
    }

    /** The alert payload delivered to one recipient. */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> alertTo(User recipient) {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSendToUser(eq(recipient.getUsername()),
                eq("/queue/conversation-alerts"), captor.capture());
        return (java.util.Map<String, Object>) captor.getValue();
    }

    // ---------- Storage and inheritance ----------

    @Test
    void aFreshMembershipInheritsRatherThanCopying() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        // The raw value is DEFAULT — the *instruction to inherit*, not a copy of what it resolves
        // to. That difference is the whole design: with a copy, changing the account default moves
        // nothing, because every row is carrying a frozen value indistinguishable from a choice.
        assertThat(preferences.levelFor(conv, bob)).isEqualTo(NotificationLevel.DEFAULT);
        assertThat(preferences.effectiveLevelFor(conv, bob)).isEqualTo(NotificationLevel.ALL);

        // And it is the *conversation* account default it inherits, not the channel one. Moving
        // that is what a row storing DEFAULT is for.
        preferences.setAccountDmDefault(bob, NotificationLevel.MENTIONS);
        var reloaded = users.findById(bob.getId()).orElseThrow();
        assertThat(preferences.levelFor(conv, reloaded)).isEqualTo(NotificationLevel.DEFAULT);
        assertThat(preferences.effectiveLevelFor(conv, reloaded)).isEqualTo(NotificationLevel.MENTIONS);
    }

    /**
     * The two defaults are genuinely separate. Changing how you follow channels must not quietly
     * change how you receive direct messages — that coupling is the bug the split removes.
     */
    @Test
    void theChannelDefaultAndTheConversationDefaultDoNotMoveEachOther() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        preferences.setAccountDefault(bob, NotificationLevel.NONE);
        var afterChannelChange = users.findById(bob.getId()).orElseThrow();
        assertThat(preferences.effectiveLevelFor(conv, afterChannelChange))
                .as("muting channels must not mute direct messages")
                .isEqualTo(NotificationLevel.ALL);

        preferences.setAccountDmDefault(bob, NotificationLevel.NONE);
        var afterDmChange = users.findById(bob.getId()).orElseThrow();
        assertThat(preferences.accountDefault(afterDmChange)).isEqualTo(NotificationLevel.NONE);
        assertThat(preferences.effectiveLevelFor(conv, afterDmChange)).isEqualTo(NotificationLevel.NONE);
    }

    @Test
    void anExplicitLevelStopsFollowingTheAccountDefault() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        preferences.setLevelFor(conv, bob, NotificationLevel.NONE);
        preferences.setAccountDefault(bob, NotificationLevel.ALL);
        var reloaded = users.findById(bob.getId()).orElseThrow();

        assertThat(preferences.levelFor(conv, reloaded)).isEqualTo(NotificationLevel.NONE);
        assertThat(preferences.effectiveLevelFor(conv, reloaded)).isEqualTo(NotificationLevel.NONE);
    }

    @Test
    void settingDefaultAgainGoesBackToInheriting() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        preferences.setLevelFor(conv, bob, NotificationLevel.NONE);
        preferences.setLevelFor(conv, bob, NotificationLevel.DEFAULT);
        preferences.setAccountDefault(bob, NotificationLevel.ALL);
        var reloaded = users.findById(bob.getId()).orElseThrow();

        assertThat(preferences.effectiveLevelFor(conv, reloaded)).isEqualTo(NotificationLevel.ALL);
    }

    @Test
    void theLevelIsPerPersonNotPerConversation() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Noise", alice, List.of(bob, carol));

        preferences.setLevelFor(conv, bob, NotificationLevel.NONE);

        assertThat(preferences.effectiveLevelFor(conv, bob)).isEqualTo(NotificationLevel.NONE);
        assertThat(preferences.effectiveLevelFor(conv, carol)).isEqualTo(NotificationLevel.ALL);
    }

    @Test
    void aNonMemberCanNeitherReadNorSetALevel() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.directBetween(alice, bob);

        assertThatThrownBy(() -> preferences.levelFor(conv, carol))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> preferences.setLevelFor(conv, carol, NotificationLevel.NONE))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- What it silences ----------

    @Test
    void mutingAGroupStopsTheAlertsAndNothingElse() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Standup", alice, List.of(bob, carol));
        preferences.setLevelFor(conv, bob, NotificationLevel.NONE);

        var msg = conversations.post(conv, alice, "morning all");
        alerts.alert(conv, msg);

        verifyNotAlerted(bob);
        verifyAlerted(carol);
        // Muting is "stop interrupting me", not "pretend nothing happened" — the count is a fact
        // about the conversation and still moves.
        assertThat(conversations.unreadCounts(bob, List.of(conv.getId())).get(conv.getId()))
                .isEqualTo(1L);
    }

    /**
     * MENTIONS means mentions here now. It could not while conversations inherited the *channel*
     * account default, which ships as MENTIONS: honouring it would have stopped delivering direct
     * messages to every existing account at once, so the code ignored it and only NONE silenced a
     * conversation. That cost the setting a large group DM actually wants. Conversations now have
     * their own account default (ALL), so choosing MENTIONS on one is a deliberate choice and gets
     * what it says.
     */
    @Test
    void mentionsOnlyDeliversOnlyWhatNamesYouInAGroup() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Chatter", alice, List.of(bob));
        preferences.setLevelFor(conv, bob, NotificationLevel.MENTIONS);

        alerts.alert(conv, conversations.post(conv, alice, "just thinking out loud"));
        verifyNotAlerted(bob);

        reset(broker);
        alerts.alert(conv, conversations.post(conv, alice, "@" + bob.getUsername() + " thoughts?"));
        verifyAlerted(bob);
    }

    /**
     * The regression the split exists to prevent: an account that has never touched either setting
     * still gets its direct messages. The channel default is MENTIONS and the conversation default
     * is ALL, and it is the second one a conversation follows.
     */
    @Test
    void anUntouchedAccountStillGetsItsDirectMessages() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        assertThat(preferences.accountDefault(bob)).isEqualTo(NotificationLevel.MENTIONS);
        assertThat(preferences.accountDmDefault(bob)).isEqualTo(NotificationLevel.ALL);

        var conv = conversations.createGroup("Chatter", alice, List.of(bob));
        alerts.alert(conv, conversations.post(conv, alice, "nobody is named in this one"));

        verifyAlerted(bob);
    }

    /** And the account-wide conversation default is itself honoured when set to mentions-only. */
    @Test
    void theConversationAccountDefaultCanBeMentionsOnly() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        preferences.setAccountDmDefault(bob, NotificationLevel.MENTIONS);
        var conv = conversations.createGroup("Chatter", alice, List.of(bob));

        alerts.alert(conv, conversations.post(conv, alice, "unaddressed chatter"));
        verifyNotAlerted(bob);

        reset(broker);
        alerts.alert(conv, conversations.post(conv, alice, "@" + bob.getUsername() + " ping"));
        verifyAlerted(bob);
    }

    @Test
    void aOneToOneNotifiesUnderTheShippedDefault() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        assertThat(preferences.effectiveLevelFor(conv, bob)).isEqualTo(NotificationLevel.ALL);

        alerts.alert(conv, conversations.post(conv, alice, "no names in this one"));

        verifyAlerted(bob);
    }

    @Test
    void aOneToOneCanStillBeMutedOutright() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        preferences.setLevelFor(conv, bob, NotificationLevel.NONE);

        alerts.alert(conv, conversations.post(conv, alice, "hello?"));

        verifyNotAlerted(bob);
    }

    @Test
    void aMutedMemberIsStillMutedByABroadcastHandle() {
        // NONE is a mute and a mute has no exceptions — the same rule the channel client applies.
        // "Nothing from this conversation" would mean very little if anyone could override it by
        // typing @channel.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Quiet", alice, List.of(bob));
        preferences.setLevelFor(conv, bob, NotificationLevel.NONE);

        alerts.alert(conv, conversations.post(conv, alice, "@here anyone about?"));

        verifyNotAlerted(bob);
    }

    // ---------- What "somebody said your name" means here ----------

    @Test
    void anOrdinaryMessageIsNotFlaggedAsAMention() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Room", alice, List.of(bob));

        alerts.alert(conv, conversations.post(conv, alice, "morning"));

        assertThat(alertTo(bob)).containsEntry("mention", false);
    }

    @Test
    void beingNamedIsFlaggedAsAMention() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Room", alice, List.of(bob));

        alerts.alert(conv, conversations.post(conv, alice, "@" + bob.getUsername() + " thoughts?"));

        assertThat(alertTo(bob)).containsEntry("mention", true);
    }

    @Test
    void aBroadcastHandleNamesEveryoneInTheConversation() {
        // The decision behind this: @channel / @here in a group conversation address everybody in
        // it, and no fan-out table is needed to answer "who is everybody here" — the alert loop is
        // already iterating exactly that set. `message_mentions` stays channel-only.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Release", alice, List.of(bob, carol));

        alerts.alert(conv, conversations.post(conv, alice, "@channel we ship at four"));

        assertThat(alertTo(bob)).containsEntry("mention", true);
        assertThat(alertTo(carol)).containsEntry("mention", true);
    }

    @Test
    void aHandleInsideCodeNamesNobody() {
        // extractHandles reads only non-code text, matching the renderer, which never decorates a
        // mention inside a code span (N21). A pill that isn't drawn must not notify either.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Room", alice, List.of(bob));

        alerts.alert(conv, conversations.post(conv, alice, "use `@channel` to reach everyone"));

        assertThat(alertTo(bob)).containsEntry("mention", false);
    }

    @Test
    void aReplyNamesThePeopleAlreadyInItsThread() {
        // Being in a thread is what makes a reply addressed to you, in the same sense a mention is
        // — the channel side made exactly this call, and it is what stops a threaded conversation
        // producing no signal at all for the people actually having it.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Threads", alice, List.of(bob, carol));

        var parent = conversations.post(conv, bob, "here is the plan");
        var reply = conversations.replyInThread(parent.getId(), alice, "one question");
        alerts.alert(conv, reply, conversations.threadParticipants(parent.getId(), alice));

        assertThat(alertTo(bob)).containsEntry("mention", true);   // wrote the parent
        assertThat(alertTo(carol)).containsEntry("mention", false); // has not written in it
    }

    // ---------- A conversation with one member ----------

    @Test
    void aSelfConversationHasALevelAndNobodyToAlert() {
        // One member, who is also every message's author, so the alert loop always skips them —
        // being notified of your own message is noise. The level is still readable and settable,
        // which matters because the sidebar row renders from it like any other.
        var solo = newUser("solo");
        var conv = conversations.directBetween(solo, solo);
        assertThat(conversations.members(conv)).hasSize(1);

        assertThat(preferences.levelFor(conv, solo)).isEqualTo(NotificationLevel.DEFAULT);
        preferences.setLevelFor(conv, solo, NotificationLevel.NONE);
        assertThat(preferences.effectiveLevelFor(conv, solo)).isEqualTo(NotificationLevel.NONE);

        alerts.alert(conv, conversations.post(conv, solo, "note to self"));
        verifyNotAlerted(solo);
    }
}
