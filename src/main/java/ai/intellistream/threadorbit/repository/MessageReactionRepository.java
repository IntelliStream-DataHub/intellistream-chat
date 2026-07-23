package ai.intellistream.threadorbit.repository;

import ai.intellistream.threadorbit.domain.Message;
import ai.intellistream.threadorbit.domain.MessageReaction;
import ai.intellistream.threadorbit.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {

    Optional<MessageReaction> findByMessageAndUserAndEmoji(Message message, User user, String emoji);

    List<MessageReaction> findByMessageOrderByCreatedAtAsc(Message message);

    List<MessageReaction> findByMessageInOrderByCreatedAtAsc(Collection<Message> messages);

    void deleteByMessageAndUserAndEmoji(Message message, User user, String emoji);
}
