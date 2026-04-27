package com.example.chat.repository;

import com.example.chat.domain.ConversationMessage;
import com.example.chat.domain.ConversationReaction;
import com.example.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationReactionRepository extends JpaRepository<ConversationReaction, UUID> {

    Optional<ConversationReaction> findByMessageAndUserAndEmoji(ConversationMessage message, User user, String emoji);

    List<ConversationReaction> findByMessageOrderByCreatedAtAsc(ConversationMessage message);

    List<ConversationReaction> findByMessageInOrderByCreatedAtAsc(Collection<ConversationMessage> messages);

    void deleteByMessageAndUserAndEmoji(ConversationMessage message, User user, String emoji);
}
