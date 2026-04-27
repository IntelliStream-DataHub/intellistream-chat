package ai.intellistream.radiance.integration;

import ai.intellistream.radiance.domain.ChannelType;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.MessageReactionRepository;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.MessageService;
import ai.intellistream.radiance.service.ReactionService;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class ReactionFlowIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
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
        registry.add("radiance.search.lucene-dir", () -> "build/test-lucene/ReactionFlowIT");
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ReactionService reactions;
    @Autowired MessageReactionRepository reactionRepo;
    @PersistenceContext EntityManager em;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    @Test
    void addingReactionShowsUpInGrouping() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var msg = messages.post(
                channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice),
                alice, "hi");
        em.flush();

        reactions.addReaction(msg.getId(), bob, "👍");
        em.flush();

        var groups = reactions.groupingsFor(msg, bob);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).emoji()).isEqualTo("👍");
        assertThat(groups.get(0).count()).isEqualTo(1);
        assertThat(groups.get(0).mine()).isTrue();
        assertThat(groups.get(0).usernames()).containsExactly(bob.getUsername());
    }

    @Test
    void duplicateReactionFromSameUserIsIdempotent() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var msg = messages.post(
                channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice),
                alice, "hi");
        em.flush();

        reactions.addReaction(msg.getId(), bob, "👍");
        reactions.addReaction(msg.getId(), bob, "👍");
        em.flush();

        var groups = reactions.groupingsFor(msg, bob);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).count()).isEqualTo(1);
    }

    @Test
    void multipleUsersAndEmojisGroupCorrectly() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.join(room, carol);
        // bob authors so the other two are free to react and the `mine` assertions stay clean
        // from alice's POV (authors can't react to their own messages).
        var msg = messages.post(room, bob, "hi");
        em.flush();

        reactions.addReaction(msg.getId(), alice, "👍");
        reactions.addReaction(msg.getId(), carol, "👍");
        reactions.addReaction(msg.getId(), carol, "❤️");
        em.flush();

        var groups = reactions.groupingsFor(msg, alice);
        assertThat(groups).hasSize(2);
        var thumbs = groups.stream().filter(g -> g.emoji().equals("👍")).findFirst().orElseThrow();
        assertThat(thumbs.count()).isEqualTo(2);
        assertThat(thumbs.usernames()).containsExactlyInAnyOrder(alice.getUsername(), carol.getUsername());
        assertThat(thumbs.mine()).isTrue(); // viewer is alice, who reacted with 👍
        var heart = groups.stream().filter(g -> g.emoji().equals("❤️")).findFirst().orElseThrow();
        assertThat(heart.count()).isEqualTo(1);
        assertThat(heart.mine()).isFalse();
    }

    @Test
    void removeReactionDeletesIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var msg = messages.post(
                channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice),
                alice, "hi");
        em.flush();

        reactions.addReaction(msg.getId(), bob, "👍");
        em.flush();
        assertThat(reactions.groupingsFor(msg, bob)).hasSize(1);

        reactions.removeReaction(msg.getId(), bob, "👍");
        em.flush();
        assertThat(reactions.groupingsFor(msg, bob)).isEmpty();
    }

    @Test
    void removingReactionThatDoesntExistIsNoop() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var msg = messages.post(
                channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice),
                alice, "hi");
        em.flush();

        // Should not throw.
        reactions.removeReaction(msg.getId(), bob, "👍");
    }

    @Test
    void privateChannelNonMemberCannotReact() {
        var owner = newUser("owner");
        var snoop = newUser("snoop");
        var secret = channels.create("s-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        var msg = messages.post(secret, owner, "internal");
        em.flush();

        assertThatThrownBy(() -> reactions.addReaction(msg.getId(), snoop, "👍"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void emojiValidation() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var msg = messages.post(
                channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice),
                alice, "hi");
        em.flush();

        assertThatThrownBy(() -> reactions.addReaction(msg.getId(), bob, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reactions.addReaction(msg.getId(), bob, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reactions.addReaction(msg.getId(), bob, "a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reactions.addReaction(msg.getId(), bob, "badchars"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupingsForMessagesBatch() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var m1 = messages.post(room, alice, "one");
        var m2 = messages.post(room, alice, "two");
        em.flush();

        reactions.addReaction(m1.getId(), bob, "👍");
        reactions.addReaction(m2.getId(), bob, "❤️");
        em.flush();

        var batch = reactions.groupingsFor(List.of(m1, m2), bob);
        assertThat(batch).hasSize(2);
        assertThat(batch.get(m1.getId()).get(0).emoji()).isEqualTo("👍");
        assertThat(batch.get(m2.getId()).get(0).emoji()).isEqualTo("❤️");
    }

    @Test
    void deletingMessageRemovesReactions() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var msg = messages.post(
                channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice),
                alice, "hi");
        reactions.addReaction(msg.getId(), bob, "👍");
        em.flush();
        var reactionId = reactionRepo.findByMessageOrderByCreatedAtAsc(msg).get(0).getId();

        messages.delete(msg.getId(), alice);
        em.flush();

        assertThat(reactionRepo.findById(reactionId)).isEmpty();
    }

    @Test
    void authorCannotReactToOwnMessage() {
        var alice = newUser("alice");
        var msg = messages.post(
                channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice),
                alice, "hi");
        em.flush();

        assertThatThrownBy(() -> reactions.addReaction(msg.getId(), alice, "👍"))
                .isInstanceOf(AccessDeniedException.class);
        // Nothing was persisted by the rejected call.
        assertThat(reactions.groupingsFor(msg, alice)).isEmpty();
    }
}
