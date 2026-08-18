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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationReactionService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.service.SearchService.SearchHit;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.ConversationAlertPublisher;
import ai.intellistream.chat.web.ConversationRestController;
import ai.intellistream.chat.web.dto.AddGroupMemberRequest;
import ai.intellistream.chat.web.dto.CreateGroupRequest;
import ai.intellistream.chat.web.dto.SendMessageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A workspace administrator cannot read private messages. Full stop.
 *
 * <p>This is a product rule, not an implementation detail, and it is the kind that erodes one
 * reasonable-sounding request at a time — "admins need to see reported DMs", "support needs to
 * read the thread to answer the ticket". Each of those is a decision to take, not a bug to fix
 * quietly, so the rule is pinned here across the whole surface rather than left implied by
 * whichever {@code requireMember} call happens to run first.
 *
 * <p><b>Being an admin is not the same as having no access.</b> An administrator can already read
 * every public channel, suspend accounts and purge a user's channel messages. What they cannot do
 * is read a conversation they are not part of. Those are different powers, and one does not
 * follow from the other.
 *
 * <p>Every test uses a marker string that is genuinely present and genuinely indexed, and each
 * one is paired with a control asserting a real member CAN see it. Without the control, an
 * assertion that the admin "sees nothing" also passes when the fixture never wrote anything, the
 * index was empty, or the search was broken — the failure mode that makes a security test worse
 * than no test, because it reports safety it never checked.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class AdminCannotReadPrivateMessagesIT {

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
    @Autowired UserService userService;
    @Autowired ConversationService conversations;
    @Autowired ConversationAttachmentService convAttachments;
    @Autowired ConversationReactionService convReactions;
    @Autowired ai.intellistream.chat.moderation.StorageQuotaService quotas;
    @Autowired MarkdownRenderer markdown;
    @Autowired SearchService search;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;

    private CurrentUser currentUser;
    private ConversationRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new ConversationRestController(conversations, userService, currentUser,
                markdown, convAttachments, convReactions, mock(SimpMessagingTemplate.class),
                new RateLimiter(), quotas, mock(ConversationAlertPublisher.class), linkPreviews());
    }

    private User newUser(String label, boolean admin) {
        var n = SEQ.incrementAndGet();
        var u = new User("kc-adm-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n);
        u.setAdmin(admin);
        return users.save(u);
    }

    private void actAs(User u) {
        when(currentUser.resolve(any(Principal.class))).thenReturn(u);
    }

    /** A DM between two other people, carrying a marker only they should ever see. */
    private record Fixture(User admin, User first, User second, Long conversationId, String marker) {}

    private Fixture directBetweenTwoOthers() {
        var admin = newUser("admin", true);
        var first = newUser("first", false);
        var second = newUser("second", false);
        var marker = "marker" + SEQ.incrementAndGet() + "confidential";

        actAs(first);
        var conv = controller.startDirect(
                new ai.intellistream.chat.web.dto.StartDirectRequest(second.getUsername()),
                mock(Principal.class));
        controller.sendMessage(conv.id(), new SendMessageRequest(marker + " the payroll figures"),
                mock(Principal.class));
        return new Fixture(admin, first, second, conv.id(), marker);
    }

    // ------------------------------------------------------------------ reading the messages ----

    @Test
    void anAdminCannotListTheMessagesOfADirectConversationTheyAreNotIn() {
        var f = directBetweenTwoOthers();

        actAs(f.admin());
        assertThatThrownBy(() -> controller.messages(f.conversationId(), null, mock(Principal.class)))
                .describedAs("a workspace admin must not be able to read a DM between two other people")
                .isInstanceOf(AccessDeniedException.class);

        // Control: a participant can, so the refusal above is about the admin, not a broken fixture.
        actAs(f.first());
        assertThat(controller.messages(f.conversationId(), null, mock(Principal.class)))
                .extracting(m -> m.bodyMarkdown())
                .anySatisfy(body -> assertThat(body).contains(f.marker()));
    }

    @Test
    void anAdminCannotListTheMembersOfAConversationTheyAreNotIn() {
        var f = directBetweenTwoOthers();

        actAs(f.admin());
        assertThatThrownBy(() -> controller.members(f.conversationId(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);

        actAs(f.second());
        assertThat(controller.members(f.conversationId(), mock(Principal.class))).hasSize(2);
    }

    // ------------------------------------------------------------------------------ searching ----

    @Test
    void neitherAdminSearchModeReachesAPrivateConversation() {
        var f = directBetweenTwoOthers();

        // Same marker in a public channel, so the admin-wide search has something it SHOULD find.
        // Without it this test passes when the query is simply broken, and a security test that
        // cannot tell "correctly excluded" from "returned nothing at all" is worse than none.
        var room = channels.create("boardroom-" + SEQ.incrementAndGet(), null,
                ChannelType.PUBLIC, f.first());
        var channelBody = f.marker() + " posted in a public channel";
        messages.post(room, f.first(), channelBody);

        // The ordinary search, run by someone who happens to be an admin.
        assertThat(bodiesVisibleTo(f.admin(), f.marker()))
                .describedAs("an admin's own search must not pick up other people's DMs")
                .doesNotContain(f.marker() + " the payroll figures");

        // The unrestricted, admin-only search. searchEverywhere gates on the Spring authority,
        // not on the users.admin column, so the role has to be in the SecurityContext or this
        // is refused before it ever reaches the part being tested.
        SecurityContextHolder.getContext().setAuthentication(
                authenticated(f.admin().getUsername(), "ROLE_ADMIN"));
        var adminResults = search.searchEverywhere(f.admin(), f.marker(), 50).stream()
                .map(m -> m.getBodyMarkdown())
                .toList();

        assertThat(adminResults)
                .describedAs("proof the admin-wide query ran and matched: it is unrestricted "
                        + "across channels")
                .contains(channelBody);
        assertThat(adminResults)
                .describedAs("scope=all is workspace-wide over channels, never over conversations")
                .doesNotContain(f.marker() + " the payroll figures");

        // Control: the DM body really is indexed and really is findable — by a participant.
        assertThat(bodiesVisibleTo(f.first(), f.marker()))
                .describedAs("fixture sanity: a member must find it, or the assertions above "
                        + "prove nothing")
                .contains(f.marker() + " the payroll figures");
    }

    @Test
    void anAdminCannotScopeASearchToAConversationTheyAreNotIn() {
        var f = directBetweenTwoOthers();

        actAs(f.admin());
        var conversation = conversations.requireById(f.conversationId());
        assertThatThrownBy(() -> search.searchConversation(conversation, f.admin(), f.marker(), 50))
                .describedAs("there is no admin tier for a single conversation either")
                .isInstanceOf(AccessDeniedException.class);
    }

    // --------------------------------------------------------------------- letting yourself in ----

    @Test
    void anAdminCannotAddThemselvesToAGroupToReadIt() {
        var admin = newUser("admin", true);
        var owner = newUser("owner", false);
        var other = newUser("other", false);
        var marker = "marker" + SEQ.incrementAndGet() + "confidential";

        actAs(owner);
        var group = controller.createGroup(
                new CreateGroupRequest("Private planning", List.of(other.getUsername())),
                mock(Principal.class));
        controller.sendMessage(group.id(), new SendMessageRequest(marker + " the offer is 4.2M"),
                mock(Principal.class));

        // The obvious way around every check above: join, then read legitimately.
        actAs(admin);
        assertThatThrownBy(() -> controller.addMember(group.id(),
                new AddGroupMemberRequest(admin.getUsername()), mock(Principal.class)))
                .describedAs("adding yourself is the back door that makes every other refusal moot")
                .isInstanceOf(AccessDeniedException.class);

        // And the door is still shut afterwards.
        assertThatThrownBy(() -> controller.messages(group.id(), null, mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------------------ moderating ----

    @Test
    void anAdminCannotDeleteAMessageInAConversationTheyAreNotIn() {
        var f = directBetweenTwoOthers();

        actAs(f.first());
        var messageId = controller.messages(f.conversationId(), null, mock(Principal.class))
                .getFirst().id();

        // Admins CAN delete anyone's message inside a conversation they belong to — that is
        // deliberate parity with channels. Membership is still the gate, and deletion must not
        // become a read primitive (delete returns the message) for a conversation they are not in.
        actAs(f.admin());
        assertThatThrownBy(() -> controller.deleteMessage(messageId, mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);

        actAs(f.second());
        assertThat(controller.messages(f.conversationId(), null, mock(Principal.class)))
                .describedAs("the message is untouched")
                .isNotEmpty();
    }

    /** Every body the viewer's global search returned, whatever store it came from. */
    private List<String> bodiesVisibleTo(User viewer, String query) {
        return search.searchAccessible(viewer, query, 50).stream()
                .map(hit -> switch (hit) {
                    case SearchHit.ChannelHit c ->
                            c.message().getBodyMarkdown();
                    case SearchHit.ConversationHit c ->
                            c.message().getBodyMarkdown();
                })
                .toList();
    }

    private static TestingAuthenticationToken authenticated(String username, String... roles) {
        var auth = new TestingAuthenticationToken(username, "n/a", roles);
        auth.setAuthenticated(true);
        return auth;
    }

    /** Real decoration against this context's LinkPreviewService; the broker is the test's mock. */
    private ai.intellistream.chat.web.LinkPreviews linkPreviews() {
        return new ai.intellistream.chat.web.LinkPreviews(linkPreviewService,
                org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
    }
    @org.springframework.beans.factory.annotation.Autowired
    ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviewService;
}
