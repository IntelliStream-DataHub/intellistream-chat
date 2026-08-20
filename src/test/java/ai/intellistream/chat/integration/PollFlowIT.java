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

import ai.intellistream.chat.domain.PollOption;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.slash.PollCommand;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.PollRepository;
import ai.intellistream.chat.repository.PollVoteRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.PollService;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.slash.SlashCommandService;
import ai.intellistream.chat.web.ChatWebSocketController;
import ai.intellistream.chat.web.PollRestController;
import ai.intellistream.chat.web.dto.CastVoteRequest;
import ai.intellistream.chat.web.dto.MessageEvent;
import ai.intellistream.chat.web.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage for the proper poll feature: a {@code /poll} command persists Poll +
 * Options rows (no emoji-markdown trick), vote casts go through {@link PollRestController},
 * and the channel topic broadcasts a {@code poll-vote} envelope so other viewers can update
 * their widget without a full message refresh.
 *
 * <p>Reactions on the host message stay independent — they're emoji reactions, not votes.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class PollFlowIT {

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
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ReactionService reactions;
    @Autowired MarkdownRenderer markdown;
    @Autowired PollService pollService;
    @Autowired PollRepository pollRepo;
    @Autowired PollVoteRepository voteRepo;
    @Autowired MessageMentionRepository mentionRepo;
    @Autowired SlashCommandService slashCommands;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ChatWebSocketController wsController;
    private PollRestController pollController;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        wsController = new ChatWebSocketController(channels, messages, markdown, currentUser,
                broker, new RateLimiter(), mentionRepo, slashCommands, pollService,
                new ai.intellistream.chat.metrics.WritePathMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), linkPreviews());
        pollController = new PollRestController(pollService, pollRepo, channels, currentUser,
                broker, new RateLimiter());
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-poll-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    /**
     * Sends a {@code /poll …} message and captures the resulting broadcast envelope. The
     * envelope's {@code message.poll.id} is the only handle we need to load the persisted
     * row without tripping lazy-init proxies on {@code poll.message.channel}. Returns the
     * captured envelope for any extra assertions the caller wants to make.
     */
    private MessageEvent slashPoll(Long channelId, String body) {
        wsController.send(channelId, new SendMessageRequest(body), mock(Principal.class));
        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        verify(broker, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(eq("/topic/channels/" + channelId), captor.capture());
        return captor.getAllValues().stream()
                .filter(e -> "created".equals(e.type()) && e.message() != null && e.message().poll() != null)
                .reduce((a, b) -> b)        // last one (most recent) wins
                .orElseThrow();
    }

    // ---------- Creation ----------

    @Test
    void slashPollPersistsPollAndAttachesDtoToBroadcast() {
        var alice = newUser("alice");
        var room = channels.create("Poll-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        wsController.send(room.getId(),
                new SendMessageRequest("/poll Pizza or burger? | Pizza | Burger | Salad"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        var event = captor.getValue();
        assertThat(event.type()).isEqualTo("created");
        var dto = event.message();
        assertThat(dto.bodyMarkdown()).contains("📊").contains("Poll").contains("Pizza or burger?");
        // The widget data is on the DTO — the markdown body is just a header.
        assertThat(dto.poll()).isNotNull();
        assertThat(dto.poll().question()).isEqualTo("Pizza or burger?");
        assertThat(dto.poll().options()).hasSize(3);
        assertThat(dto.poll().options())
                .extracting("label")
                .containsExactly("Pizza", "Burger", "Salad");
        assertThat(dto.poll().totalVoters()).isZero();
        assertThat(dto.poll().myVoteOptionId()).isNull();
        // The Poll row is in the DB.
        var hostMessageId = dto.id();
        assertThat(pollRepo.findByMessageIdWithOptions(hostMessageId)).isPresent();
    }

    @Test
    void emojisInPollMarkdownDoNotEncodeOptions() {
        // Sanity: the new body shape never re-emits "1️⃣ option" — that was the old hack.
        var alice = newUser("alice");
        var room = channels.create("Poll-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        wsController.send(room.getId(),
                new SendMessageRequest("/poll Lunch? | Pizza | Salad"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        var body = captor.getValue().message().bodyMarkdown();
        assertThat(body).doesNotContain("1️⃣").doesNotContain("React with");
    }

    // ---------- Voting ----------

    @Test
    void castVoteIncrementsCountAndMarksMyVote() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Vote-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob); // voting requires membership (SEC-4)
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var event = slashPoll(room.getId(), "/poll Lunch? | Pizza | Burger");
        var pollId = event.message().poll().id();
        var pizzaId = event.message().poll().options().get(0).id();

        // Bob votes Pizza.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var dto = pollController.castVote(pollId, new CastVoteRequest(pizzaId), mock(Principal.class));

        assertThat(dto.myVoteOptionId()).isEqualTo(pizzaId);
        assertThat(dto.totalVoters()).isEqualTo(1);
        assertThat(dto.options()).filteredOn(o -> o.id().equals(pizzaId))
                .singleElement()
                .satisfies(o -> assertThat(o.voteCount()).isEqualTo(1));

        // Broadcast went out on the host channel topic with type=poll-vote.
        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        verify(broker, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        var pollVoteEvents = captor.getAllValues().stream()
                .filter(e -> "poll-vote".equals(e.type())).toList();
        assertThat(pollVoteEvents).isNotEmpty();
        assertThat(pollVoteEvents.get(pollVoteEvents.size() - 1).poll().totalVoters()).isEqualTo(1);
    }

    @Test
    void changingVoteMovesItRatherThanDouble() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Move-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob); // voting requires membership (SEC-4)
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var event = slashPoll(room.getId(), "/poll Lunch? | Pizza | Burger");
        var pollId = event.message().poll().id();
        var pizzaId = event.message().poll().options().get(0).id();
        var burgerId = event.message().poll().options().get(1).id();

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        pollController.castVote(pollId, new CastVoteRequest(pizzaId), mock(Principal.class));
        var moved = pollController.castVote(pollId, new CastVoteRequest(burgerId), mock(Principal.class));

        assertThat(moved.totalVoters()).isEqualTo(1); // not double-counted
        assertThat(moved.myVoteOptionId()).isEqualTo(burgerId);
        assertThat(moved.options()).filteredOn(o -> o.id().equals(pizzaId))
                .singleElement().satisfies(o -> assertThat(o.voteCount()).isZero());
        assertThat(moved.options()).filteredOn(o -> o.id().equals(burgerId))
                .singleElement().satisfies(o -> assertThat(o.voteCount()).isEqualTo(1));
    }

    @Test
    void castingSameOptionTwiceIsIdempotent() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Idem-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob); // voting requires membership (SEC-4)
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var event = slashPoll(room.getId(), "/poll Lunch? | Pizza | Burger");
        var pollId = event.message().poll().id();
        var pizzaId = event.message().poll().options().get(0).id();

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        pollController.castVote(pollId, new CastVoteRequest(pizzaId), mock(Principal.class));
        var second = pollController.castVote(pollId, new CastVoteRequest(pizzaId), mock(Principal.class));

        assertThat(second.totalVoters()).isEqualTo(1);
        assertThat(second.myVoteOptionId()).isEqualTo(pizzaId);
    }

    @Test
    void removeVoteClearsMyVoteAndDecrementsTotal() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Rm-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob); // voting requires membership (SEC-4)
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var event = slashPoll(room.getId(), "/poll Lunch? | Pizza | Burger");
        var pollId = event.message().poll().id();
        var pizzaId = event.message().poll().options().get(0).id();

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        pollController.castVote(pollId, new CastVoteRequest(pizzaId), mock(Principal.class));
        var afterRemove = pollController.removeVote(pollId, mock(Principal.class));

        assertThat(afterRemove.myVoteOptionId()).isNull();
        assertThat(afterRemove.totalVoters()).isZero();
        // Vote row is gone from the DB.
        var poll = pollRepo.findByIdWithOptions(pollId).orElseThrow();
        assertThat(voteRepo.findByPollAndVoter(poll, bob)).isEmpty();
    }

    @Test
    void multipleVotersTallyCorrectlyPerOption() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var dan = newUser("dan");
        var room = channels.create("Tally-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob); channels.join(room, carol); channels.join(room, dan); // voting requires membership (SEC-4)
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var event = slashPoll(room.getId(), "/poll Lunch? | Pizza | Burger | Salad");
        var pollId = event.message().poll().id();
        var pizzaId = event.message().poll().options().get(0).id();
        var burgerId = event.message().poll().options().get(1).id();

        for (var voter : new User[]{bob, carol}) {
            when(currentUser.resolve(any(Principal.class))).thenReturn(voter);
            pollController.castVote(pollId, new CastVoteRequest(pizzaId), mock(Principal.class));
        }
        when(currentUser.resolve(any(Principal.class))).thenReturn(dan);
        var dto = pollController.castVote(pollId, new CastVoteRequest(burgerId), mock(Principal.class));

        assertThat(dto.totalVoters()).isEqualTo(3);
        assertThat(dto.options()).filteredOn(o -> o.id().equals(pizzaId))
                .singleElement().satisfies(o -> assertThat(o.voteCount()).isEqualTo(2));
        assertThat(dto.options()).filteredOn(o -> o.id().equals(burgerId))
                .singleElement().satisfies(o -> assertThat(o.voteCount()).isEqualTo(1));
    }

    // ---------- Reactions stay separate ----------

    @Test
    void emojiReactionsOnPollMessageDoNotAffectVoteTally() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Sep-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var event = slashPoll(room.getId(), "/poll Lunch? | Pizza | Burger");
        var pollId = event.message().poll().id();
        var hostMessageId = event.id();

        // Bob slaps a 🎉 reaction on the host message.
        reactions.addReaction(hostMessageId, bob, "🎉");

        // Tally is still empty — reactions are not votes.
        assertThat(voteRepo.tallyByPollIds(java.util.List.of(pollId))).isEmpty();
        var poll = pollRepo.findByIdWithOptions(pollId).orElseThrow();
        assertThat(voteRepo.findByPollAndVoter(poll, bob)).isEmpty();
    }

    // ---------- Validation ----------

    @Test
    void voteForOptionFromAnotherPollIsRejected() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("X-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob); // voting requires membership (SEC-4)
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var eventA = slashPoll(room.getId(), "/poll A? | a1 | a2");
        // Reset to capture the next "created" cleanly without picking up A's poll-vote events.
        org.mockito.Mockito.clearInvocations(broker);
        var eventB = slashPoll(room.getId(), "/poll B? | b1 | b2");
        var pollAId = eventA.message().poll().id();
        var stranger = eventB.message().poll().options().get(0).id();

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        assertThatThrownBy(() -> pollController.castVote(pollAId,
                new CastVoteRequest(stranger), mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Option not in this poll");
    }

    // ---------- Editing ----------

    @Test
    void editingAPollRewritesItsQuestionAndOptions() {
        var alice = newUser("alice");
        var room = channels.create("Poll-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var message = pollCommandMessage(room, alice, "/poll Lunch? | Pizza | Sushi");

        pollService.update(message, "Lunch on Friday?", List.of("Pizza", "Sushi", "Salad"));

        var poll = pollRepo.findByMessageIdWithOptions(message.getId()).orElseThrow();
        assertThat(poll.getQuestion()).isEqualTo("Lunch on Friday?");
        assertThat(poll.getOptions()).extracting(PollOption::getLabel)
                .containsExactly("Pizza", "Sushi", "Salad");
        // Replacing the options reuses positions 0..n, which collides with the rows still in the
        // table unless the deletes are flushed first — this is the assertion that catches it.
        assertThat(poll.getOptions()).extracting(PollOption::getPosition).containsExactly(0, 1, 2);
    }

    @Test
    void aPollWithVotesCanStillHaveItsQuestionCorrected() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Poll-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var message = pollCommandMessage(room, alice, "/poll Lunch? | Pizza | Sushi");
        var poll = pollRepo.findByMessageIdWithOptions(message.getId()).orElseThrow();
        pollService.castVote(poll.getId(), poll.getOptions().get(0).getId(), bob);

        // A typo fix changes nothing about what bob chose, so it is always allowed.
        pollService.update(message, "Lunch on Friday?", List.of("Pizza", "Sushi"));

        var after = pollRepo.findByMessageIdWithOptions(message.getId()).orElseThrow();
        assertThat(after.getQuestion()).isEqualTo("Lunch on Friday?");
        assertThat(voteRepo.countByPoll(after)).describedAs("bob's vote survives").isEqualTo(1);
    }

    @Test
    void thePollsOptionsFreezeOnceSomebodyHasVoted() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Poll-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var message = pollCommandMessage(room, alice, "/poll Venue? | Lisbon | Tallinn");
        var poll = pollRepo.findByMessageIdWithOptions(message.getId()).orElseThrow();
        pollService.castVote(poll.getId(), poll.getOptions().get(0).getId(), bob);

        // A vote is a statement about a specific set of choices. Re-pointing it at a different
        // set would put words in bob's mouth, and discarding his vote to allow the edit would let
        // the poll's author erase other people's answers by changing a word.
        assertThatThrownBy(() ->
                pollService.update(message, "Venue?", List.of("Lisbon", "Porto")))
                .isInstanceOf(PublicBadRequestException.class)
                .hasMessageContaining("already voted");

        var after = pollRepo.findByMessageIdWithOptions(message.getId()).orElseThrow();
        assertThat(after.getOptions()).extracting(PollOption::getLabel)
                .describedAs("nothing was half-applied")
                .containsExactly("Lisbon", "Tallinn");
        assertThat(voteRepo.countByPoll(after)).isEqualTo(1);
    }

    @Test
    void anEditedCommandParsesBackIntoTheSameQuestionAndOptions() {
        // The client rebuilds this string from the poll to fill the edit box; if the round trip
        // is not exact, editing a poll silently rewrites labels the author never touched.
        var parsed = PollCommand.parseEditedCommand("/poll Lunch? | Pizza | Sushi | Salad");
        assertThat(parsed).isNotNull();
        assertThat(parsed.question()).isEqualTo("Lunch?");
        assertThat(parsed.options()).containsExactly("Pizza", "Sushi", "Salad");

        // A label containing a literal pipe survives, escaped.
        var piped = PollCommand.parseEditedCommand("/poll Pick | A \\| B | C");
        assertThat(piped.options()).containsExactly("A | B", "C");

        // Ordinary messages are not poll edits, including near-misses.
        assertThat(PollCommand.parseEditedCommand("just a message")).isNull();
        assertThat(PollCommand.parseEditedCommand("/pollute the well")).isNull();
    }

    private Message pollCommandMessage(Channel room, User author, String command) {
        when(currentUser.resolve(any(Principal.class))).thenReturn(author);
        wsController.send(room.getId(), new SendMessageRequest(command), mock(Principal.class));
        return messages.recent(room, author, 10).stream()
                .filter(m -> m.getBodyMarkdown().contains("Poll:"))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    /** Real decoration against this context's LinkPreviewService; the broker is the test's mock. */
    private ai.intellistream.chat.web.LinkPreviews linkPreviews() {
        return new ai.intellistream.chat.web.LinkPreviews(linkPreviewService,
                org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
    }
    @org.springframework.beans.factory.annotation.Autowired
    ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviewService;
}
