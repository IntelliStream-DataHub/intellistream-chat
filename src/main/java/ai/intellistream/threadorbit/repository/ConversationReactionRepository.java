package ai.intellistream.threadorbit.repository;

import ai.intellistream.threadorbit.domain.ConversationMessage;
import ai.intellistream.threadorbit.domain.ConversationReaction;
import ai.intellistream.threadorbit.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface ConversationReactionRepository extends JpaRepository<ConversationReaction, Long> {

    Optional<ConversationReaction> findByMessageAndUserAndEmoji(ConversationMessage message, User user, String emoji);

    List<ConversationReaction> findByMessageOrderByCreatedAtAsc(ConversationMessage message);

    List<ConversationReaction> findByMessageInOrderByCreatedAtAsc(Collection<ConversationMessage> messages);

    void deleteByMessageAndUserAndEmoji(ConversationMessage message, User user, String emoji);
}
