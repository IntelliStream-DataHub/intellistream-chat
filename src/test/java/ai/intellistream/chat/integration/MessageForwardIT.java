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

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MessageForwardService;
import ai.intellistream.chat.service.MessageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Forwarding: authorisation on both ends, the private-source acknowledgement, and the shape of the
 * message that comes out the other side.
 *
 * <p>The authorisation cases are asymmetric on purpose and both directions are asserted: you need
 * to be able to <em>read</em> the source and to <em>write</em> the destination, and a test that only
 * checks one of those passes on a service that only checks one of those.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class MessageForwardIT {

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-forward");
        TestLuceneDirs.register(registry);
    }

    @PersistenceContext EntityManager em;
    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ConversationService conversations;
    @Autowired MessageForwardService forwards;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-fw-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    @Test
    void aForwardIsANewMessageByTheForwarderThatQuotesTheOriginal() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var from = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var to = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, bob);
        var original = messages.post(from, alice, "the build is green");
        em.flush();

        var posted = forwards.forwardToChannel(original.getId(), to, "look at this", false, bob);
        em.flush();

        var forwarded = posted.message();
        // Authorship is not faked. The new message is bob's, in bob's channel.
        assertThat(forwarded.getAuthor().getId()).isEqualTo(bob.getId());
        assertThat(forwarded.getChannel().getId()).isEqualTo(to.getId());
        // The original is untouched and still where it was.
        assertThat(messages.requireById(original.getId()).getChannel().getId())
                .isEqualTo(from.getId());

        var body = forwarded.getBodyMarkdown();
        assertThat(body).startsWith("look at this");
        assertThat(body).contains("**@" + alice.getUsername() + "**");
        assertThat(body).contains("#" + from.getName());
        // A permalink back, so the quote is checkable by anyone who can read the source.
        assertThat(body).contains("/channels/" + from.getId() + "?m=" + original.getId());
        assertThat(body).contains("> the build is green");
    }

    /** Without a comment the forward is just the quote — no stray blank line at the top. */
    @Test
    void aForwardWithoutACommentIsJustTheQuote() {
        var alice = newUser("alice");
        var from = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var to = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var original = messages.post(from, alice, "no notes");
        em.flush();

        var body = forwards.forwardToChannel(original.getId(), to, null, false, alice)
                .message().getBodyMarkdown();
        assertThat(body).startsWith("> **@");
    }

    /** Every line of a multi-line original is quoted, so its structure survives the move. */
    @Test
    void everyLineOfTheOriginalIsQuoted() {
        var alice = newUser("alice");
        var from = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var to = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var original = messages.post(from, alice, "# Heading\n- one\n- two");
        em.flush();

        var body = forwards.forwardToChannel(original.getId(), to, null, false, alice)
                .message().getBodyMarkdown();
        assertThat(body).contains("> # Heading");
        assertThat(body).contains("> - one");
        assertThat(body).contains("> - two");
    }

    // ------------------------------------------------------------------ authorisation, both ends

    @Test
    void youMustBeAbleToReadTheSource() {
        var owner = newUser("owner");
        var outsider = newUser("outsider");
        var secret = channels.create("s-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        var mine = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, outsider);
        var original = messages.post(secret, owner, "internal");
        em.flush();

        assertThatThrownBy(() -> forwards.forwardToChannel(original.getId(), mine, null, true, outsider))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void youMustBeAbleToWriteTheDestination() {
        var alice = newUser("alice");
        var owner = newUser("owner");
        var open = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        // A public channel alice can read but has not joined: read yes, write no.
        var notJoined = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, owner);
        var original = messages.post(open, alice, "hello");
        em.flush();

        assertThatThrownBy(() -> forwards.forwardToChannel(original.getId(), notJoined, null, false, alice))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** An archived destination takes no writes, and a forward is a write. */
    @Test
    void anArchivedDestinationRefusesAForward() {
        var alice = newUser("alice");
        var from = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var to = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var original = messages.post(from, alice, "hello");
        em.flush();
        channels.archive(to, alice);
        em.flush();
        em.clear();

        var frozen = channels.requireById(to.getId());
        assertThatThrownBy(() -> forwards.forwardToChannel(original.getId(), frozen, null, false, alice))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Reading an archived channel still works, so forwarding <em>out</em> of one does too. */
    @Test
    void anArchivedSourceCanStillBeForwardedOutOf() {
        var alice = newUser("alice");
        var from = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var to = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var original = messages.post(from, alice, "worth keeping");
        em.flush();
        channels.archive(from, alice);
        em.flush();
        em.clear();

        var posted = forwards.forwardToChannel(original.getId(), channels.requireById(to.getId()),
                null, false, alice);
        assertThat(posted.message().getBodyMarkdown()).contains("> worth keeping");
    }

    // ------------------------------------------------------------------ the disclosure rule

    @Test
    void forwardingOutOfAPrivateChannelNeedsAnAcknowledgement() {
        var alice = newUser("alice");
        var secret = channels.create("s-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        var open = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var original = messages.post(secret, alice, "the merger closes friday");
        em.flush();

        assertThatThrownBy(() -> forwards.forwardToChannel(original.getId(), open, null, false, alice))
                .isInstanceOf(PublicBadRequestException.class)
                .hasMessageContaining(secret.getName());

        // Said out loud, it goes through. Refusing outright would only stop the convenient path:
        // the text can be selected and pasted, and a rule that stops one and not the other is
        // theatre. What this buys is that it cannot happen by reflex.
        var posted = forwards.forwardToChannel(original.getId(), open, null, true, alice);
        em.flush();
        assertThat(posted.message().getBodyMarkdown()).contains("> the merger closes friday");
    }

    /** A public source is already workspace-readable, so moving it widens nothing and asks nothing. */
    @Test
    void forwardingOutOfAPublicChannelNeedsNoAcknowledgement() {
        var alice = newUser("alice");
        var from = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var to = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var original = messages.post(from, alice, "public knowledge");
        em.flush();

        assertThat(MessageForwardService.requiresDisclosureAcknowledgement(from)).isFalse();
        var posted = forwards.forwardToChannel(original.getId(), to, null, false, alice);
        assertThat(posted.message().getBodyMarkdown()).contains("> public knowledge");
    }

    /** The rule follows the source into a DM destination too — a DM is a different audience. */
    @Test
    void forwardingAPrivateChannelsMessageIntoADmAlsoNeedsTheAcknowledgement() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var secret = channels.create("s-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        var conv = conversations.directBetween(alice, bob);
        var original = messages.post(secret, alice, "not for bob");
        em.flush();

        assertThatThrownBy(() -> forwards.forwardToConversation(original.getId(), conv, null, false, alice))
                .isInstanceOf(PublicBadRequestException.class);

        var sent = forwards.forwardToConversation(original.getId(), conv, "fyi", true, alice);
        em.flush();
        assertThat(sent.getConversation().getId()).isEqualTo(conv.getId());
        assertThat(sent.getAuthor().getId()).isEqualTo(alice.getId());
        assertThat(sent.getBodyMarkdown()).startsWith("fyi");
        assertThat(sent.getBodyMarkdown()).contains("> not for bob");
    }

    @Test
    void youMustBeInTheConversationYouAreForwardingInto() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var open = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(open, carol);
        var theirDm = conversations.directBetween(alice, bob);
        var original = messages.post(open, alice, "hello");
        em.flush();

        assertThatThrownBy(() -> forwards.forwardToConversation(original.getId(), theirDm, null, false, carol))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Forwarding into the room the message is already in is quote-reply wearing a costume. */
    @Test
    void forwardingIntoTheSameChannelIsRefused() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var original = messages.post(room, alice, "hello");
        em.flush();

        assertThatThrownBy(() -> forwards.forwardToChannel(original.getId(), room, null, false, alice))
                .isInstanceOf(PublicBadRequestException.class);
    }
}
