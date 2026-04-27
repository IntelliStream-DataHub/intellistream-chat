package com.example.chat.repository;

import com.example.chat.domain.Message;
import com.example.chat.domain.MessageReaction;
import com.example.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

    Optional<MessageReaction> findByMessageAndUserAndEmoji(Message message, User user, String emoji);

    List<MessageReaction> findByMessageOrderByCreatedAtAsc(Message message);

    List<MessageReaction> findByMessageInOrderByCreatedAtAsc(Collection<Message> messages);

    void deleteByMessageAndUserAndEmoji(Message message, User user, String emoji);
}
