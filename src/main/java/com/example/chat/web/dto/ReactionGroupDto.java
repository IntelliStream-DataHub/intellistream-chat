package com.example.chat.web.dto;

import java.util.List;

/**
 * One emoji's reactions on a message: total count, the usernames who reacted (so the UI
 * can show a tooltip), and a flag for whether the current viewer reacted.
 */
public record ReactionGroupDto(
        String emoji,
        long count,
        boolean mine,
        List<String> usernames
) {}
