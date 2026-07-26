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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Forwarding: send an existing message somewhere else, as a new message that quotes it.
 *
 * <h2>The forwarded copy is not a copy</h2>
 * It is a new message, authored by whoever forwarded it, whose body is a Markdown blockquote of the
 * original with an attribution line and a permalink back. Nothing anywhere claims the original
 * author wrote it in the destination, because they did not, and a system that lets one person put
 * words under another person's name in a room they may not even be in is a system nobody can trust
 * a screenshot of. The permalink is what makes the quote checkable: anyone who can read the source
 * can follow it and see the original in place.
 *
 * <h2>Authorisation on both ends</h2>
 * Read the source ({@link ChannelService#requireMember}) and write the destination
 * ({@link ChannelService#requireWriteAccess}, or conversation membership). Both, always — a forward
 * is a read and a post, and each half is checked as itself.
 *
 * <h2>Forwarding out of a private channel</h2>
 * Every private channel has an audience, and every destination has a different one, so a forward
 * out of a private channel is always a disclosure. Refusing outright would be wrong — this is a
 * chat application, the text can be selected and pasted, and a rule that only stops the convenient
 * path while leaving the inconvenient one is theatre. Allowing it silently is worse: the whole
 * problem with a forward button is that it turns a decision into a reflex.
 *
 * <p>So it is allowed and it is not casual. The request must carry
 * {@code acknowledgeDisclosure} when the source is private, and the UI only sets that after showing
 * what is about to happen and who will be able to read it. A flag a client could set on its own is
 * not a security control and is not meant to be one; it is the API's way of insisting that the
 * caller has said out loud what it is doing. The audit value is real too: "forwarded without
 * acknowledging" cannot appear in a log, because it cannot happen.
 *
 * <p>A PUBLIC source needs no acknowledgement. It is already readable by everyone in the workspace,
 * so moving it does not widen anything, and asking every time would train people to click through
 * the prompt that matters.
 *
 * <h2>What is deliberately not here</h2>
 * <b>Forwarding out of a DM or a group conversation.</b> A DM is the one room whose participants
 * have a real expectation that what they write stays between them, and there is no acknowledgement
 * wording that makes a one-click "send this elsewhere" button an acceptable thing to point at it.
 * Quoting and retyping still work, and both take enough deliberate effort to be a decision. This is
 * a product choice rather than a missing feature, and it is why {@link #forwardToConversation}
 * takes a channel message as its source too — a conversation is a destination here, never an origin.
 *
 * <p><b>Attachments.</b> A forwarded message quotes text. Re-posting somebody else's file into
 * another room would copy bytes, charge them to the forwarder's quota, and put a file somewhere its
 * uploader cannot delete it from. The quote's permalink leads to the original, files and all.
 */
@Service
public class MessageForwardService {

    /**
     * How much of the original the quote carries. A forward is a pointer with enough of the text to
     * be worth reading; past this the permalink is the better answer, and the destination message
     * still has to fit inside the 8000-character limit alongside the forwarder's own comment.
     */
    private static final int MAX_QUOTED_CHARS = 3000;

    private static final int MAX_COMMENT_CHARS = 2000;

    private static final DateTimeFormatter QUOTE_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final MessageRepository messages;
    private final MessageService messageService;
    private final ConversationService conversationService;
    private final ChannelService channelService;

    public MessageForwardService(MessageRepository messages,
                                 MessageService messageService,
                                 ConversationService conversationService,
                                 ChannelService channelService) {
        this.messages = messages;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.channelService = channelService;
    }

    /** Forward a channel message into another channel. */
    @Transactional
    public MessageService.Posted forwardToChannel(Long sourceMessageId, Channel target,
                                                  String comment, boolean acknowledgeDisclosure,
                                                  User actor) {
        var source = requireReadableSource(sourceMessageId, actor);
        if (source.getChannel().getId().equals(target.getId())) {
            // Quote-reply is the in-room gesture and it is a different, cheaper thing. A forward
            // into the room the message is already in produces a message quoting its own neighbour
            // with a permalink two lines up.
            throw new PublicBadRequestException(
                    "That message is already in this channel — quote it instead of forwarding it.");
        }
        requireDisclosureAcknowledged(source.getChannel(), acknowledgeDisclosure);
        channelService.requireWriteAccess(target, actor);
        return messageService.postWithMentions(target, actor, buildBody(source, comment));
    }

    /** Forward a channel message into a DM or group conversation. */
    @Transactional
    public ConversationMessage forwardToConversation(Long sourceMessageId, Conversation target,
                                                     String comment, boolean acknowledgeDisclosure,
                                                     User actor) {
        var source = requireReadableSource(sourceMessageId, actor);
        requireDisclosureAcknowledged(source.getChannel(), acknowledgeDisclosure);
        conversationService.requireMember(target, actor);
        return conversationService.post(target, actor, buildBody(source, comment));
    }

    /**
     * Does forwarding out of {@code source} need the caller to acknowledge a disclosure? Exposed so
     * the UI can decide whether to show the warning without guessing at the rule or waiting for a
     * refusal to tell it.
     */
    public static boolean requiresDisclosureAcknowledgement(Channel source) {
        return source.getType() != ChannelType.PUBLIC;
    }

    private Message requireReadableSource(Long sourceMessageId, User actor) {
        var source = messages.findByIdWithChannelAndAuthor(sourceMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + sourceMessageId));
        channelService.requireMember(source.getChannel(), actor);
        return source;
    }

    private static void requireDisclosureAcknowledged(Channel source, boolean acknowledged) {
        if (requiresDisclosureAcknowledgement(source) && !acknowledged) {
            throw new PublicBadRequestException(
                    "This message is from the private channel #" + source.getName()
                    + ". Forwarding it shows it to people who are not in that channel — confirm "
                    + "that you mean to.");
        }
    }

    /**
     * The forwarded body: the forwarder's own comment, then a blockquote of the original under an
     * attribution line that links back to it.
     *
     * <p>Every line of the original gets its own {@code > } prefix rather than the whole thing being
     * indented once. Markdown's lazy continuation would otherwise fold a quoted list, heading or
     * code fence into the surrounding quote and change what the original said, which is the one
     * thing a quote must not do.
     */
    static String buildBody(Message source, String comment) {
        var trimmedComment = comment == null ? "" : comment.trim();
        if (trimmedComment.length() > MAX_COMMENT_CHARS) {
            throw new IllegalArgumentException(
                    "Comment too long (max " + MAX_COMMENT_CHARS + " chars)");
        }
        var channel = source.getChannel();
        var permalink = "/channels/" + channel.getId()
                + "?m=" + source.getId() + "#m=" + source.getId();
        var sb = new StringBuilder();
        if (!trimmedComment.isEmpty()) {
            sb.append(trimmedComment).append("\n\n");
        }
        sb.append("> **@").append(source.getAuthor().getUsername()).append("** in [#")
                .append(channel.getName()).append("](").append(permalink).append(") · ")
                .append(QUOTE_DATE.format(source.getCreatedAt()))
                .append("\n>\n");
        var body = source.getBodyMarkdown() == null ? "" : source.getBodyMarkdown();
        var truncated = body.length() > MAX_QUOTED_CHARS;
        if (truncated) {
            body = body.substring(0, MAX_QUOTED_CHARS);
        }
        for (var line : body.split("\n", -1)) {
            sb.append("> ").append(line).append('\n');
        }
        if (truncated) {
            sb.append("> …\n");
        }
        return sb.toString().stripTrailing();
    }
}
