package ai.intellistream.radiance.repository;

import ai.intellistream.radiance.domain.ConversationMessage;
import ai.intellistream.radiance.domain.ConversationReaction;
import ai.intellistream.radiance.domain.User;
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
