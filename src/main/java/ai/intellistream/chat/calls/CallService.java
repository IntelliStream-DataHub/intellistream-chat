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

package ai.intellistream.chat.calls;

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.dto.CallEvent;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import tools.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Everything that happens around a call except the media: who may start one, who gets told about
 * it, and what it leaves behind in the conversation.
 *
 * <p><b>The division of labour is the point of this class.</b> STOMP owns ringing and
 * authorisation — who may call whom, whose devices light up, when it stops — and the peers own the
 * media. The server relays SDP and ICE without reading them and never sees a packet of audio or
 * video, because everything goes through the TURN relay, which forwards encrypted UDP blind. That
 * boundary is what makes this replaceable: when group calls arrive and an SFU takes over the media,
 * this class is the half that stays.
 *
 * <p><b>Calls are DIRECT-only, on purpose.</b> Not because a group conversation could not ring —
 * it could — but because the media topology behind this is a single peer connection, and a third
 * participant has nowhere to go. Offering the button in a group would be promising something the
 * transport cannot do. {@code requireCallable} is the one place that rule lives.
 */
@Service
public class CallService {

    private static final Logger log = LoggerFactory.getLogger(CallService.class);

    /**
     * Cap on one relayed SDP or candidate, in characters of JSON. A large offer with many codecs
     * runs to a few kilobytes, so this is generous; what it refuses is the signalling channel being
     * used as a general-purpose message bus between two authenticated accounts, which is otherwise
     * exactly what an untyped pass-through payload is.
     */
    private static final int MAX_SIGNAL_CHARS = 16_384;

    private final CallRegistry registry;
    private final CallProperties properties;
    private final ConversationService conversations;
    private final UserService users;
    private final MarkdownRenderer markdown;
    private final SimpMessagingTemplate broker;

    public CallService(CallRegistry registry,
                       CallProperties properties,
                       ConversationService conversations,
                       UserService users,
                       MarkdownRenderer markdown,
                       SimpMessagingTemplate broker) {
        this.registry = registry;
        this.properties = properties;
        this.conversations = conversations;
        this.users = users;
        this.markdown = markdown;
        this.broker = broker;
    }

    /**
     * Place a call. The caller's own session id comes from STOMP rather than from the payload, so
     * the tab that owns the call is the tab that asked for it and cannot claim to be another.
     */
    public void invite(Long conversationId, User caller, String callerSession, CallMedia media) {
        if (!properties.isConfigured()) {
            throw new CallsUnavailableException();
        }
        var conversation = conversations.requireById(conversationId);
        var callee = requireCallable(conversation, caller);

        Call call;
        try {
            call = registry.invite(conversationId, caller.getUsername(), callee.getUsername(),
                    media, callerSession);
        } catch (CallBusyException e) {
            // Not a failure: the caller is told, and nothing was started. Naming who was busy lets
            // the client distinguish "they are on a call" from the stale-tab case where the busy
            // party is the caller themselves.
            send(caller.getUsername(), CallEvent.busy(e.getUsername(), displayName(
                    e.getUsername().equals(caller.getUsername()) ? caller : callee)));
            return;
        }

        send(callee.getUsername(), CallEvent.invite(call, displayName(caller)));
        send(caller.getUsername(), CallEvent.ringing(call, displayName(callee)));
    }

    /**
     * Answer. Publishing to both accounts' queues — not just to the two sessions on the call — is
     * what stops the callee's other tabs ringing: they see an {@code accepted} for a call they know
     * about and drop it, while the tab that answered recognises its own state and ignores the echo.
     */
    public void accept(String callId, User callee, String calleeSession) {
        registry.accept(callId, callee.getUsername(), calleeSession).ifPresent(call -> {
            send(call.caller(), CallEvent.accepted(call));
            send(call.callee(), CallEvent.accepted(call));
        });
    }

    /** Decline a ringing call, or hang up an active one — the same transition, different reason. */
    public void hangUp(String callId, User actor, CallEndReason reason) {
        registry.end(callId, actor.getUsername(), reason).ifPresent(this::finish);
    }

    /**
     * Relay one SDP or candidate to the other peer, unread.
     *
     * <p>The authorisation is that the sender is in the call — which the registry already knows, so
     * there is no separate membership query on the busiest path of a call setup. Trickle ICE puts
     * dozens of these through in the first seconds of a call; anything that touched the database per
     * frame would be felt.
     */
    public void signal(String callId, User from, String kind, JsonNode payload) {
        if (payload == null || payload.toString().length() > MAX_SIGNAL_CHARS) {
            throw new IllegalArgumentException("Signal payload missing or too large");
        }
        var call = registry.find(callId).orElse(null);
        if (call == null) return; // the call ended under a signal already in flight — drop it
        var peer = call.peerOf(from.getUsername());
        if (peer == null) {
            throw new AccessDeniedException("Not a participant in this call");
        }
        send(peer, CallEvent.signal(callId, from.getUsername(), kind, payload));
    }

    /** Ring timeouts. Driven by {@link CallScheduler}. */
    public void sweepTimeouts() {
        registry.expireRinging(properties.getRingTimeout()).forEach(this::finish);
    }

    /** A tab went away — hang up whatever call belonged to it. */
    public void endForSession(String sessionId) {
        registry.endForSession(sessionId, CallEndReason.DISCONNECTED).forEach(this::finish);
    }

    /**
     * Hang up everything on the way down.
     *
     * <p>The media would in fact survive this — the peers are connected to each other through the
     * relay, not through this process — but neither of them could hang up afterwards, because the
     * call they would name is one the restarted server has never heard of. Tearing down explicitly
     * turns an unkillable ghost call into a dropped one, which is a thing users understand.
     */
    @PreDestroy
    void endAllOnShutdown() {
        var live = registry.live();
        if (live.isEmpty()) return;
        log.info("Ending {} live call(s) for shutdown", live.size());
        for (var call : live) {
            registry.end(call.id(), null, CallEndReason.DISCONNECTED).ifPresent(ended -> {
                send(ended.caller(), CallEvent.ended(ended, CallEndReason.DISCONNECTED));
                send(ended.callee(), CallEvent.ended(ended, CallEndReason.DISCONNECTED));
            });
        }
    }

    /**
     * Tell both sides it is over, then leave a line in the conversation.
     *
     * <p>Order matters: the event goes first because it stops two people looking at a dead call,
     * and the archive line is bookkeeping that can afford to be a moment later. It is also the part
     * allowed to fail — a database that refuses the write must not stop the teardown, or a full disk
     * turns every call into one nobody can hang up.
     */
    private void finish(Call call) {
        send(call.caller(), CallEvent.ended(call, call.endReason()));
        send(call.callee(), CallEvent.ended(call, call.endReason()));
        try {
            archive(call);
        } catch (RuntimeException e) {
            log.warn("Could not write the archive line for call {}", call.id(), e);
        }
    }

    /**
     * Write what happened into the conversation.
     *
     * <p>Authored by the caller and italicised, rather than posted by a system account. A synthetic
     * author would need a real row in {@code users}, a name nobody chose, and an answer to what
     * happens when somebody clicks its avatar — a lot of surface for one line of text. The caller is
     * who caused the event, the italics say it was not typed, and the message is an ordinary
     * message: searchable, exportable, and still legible in five years without a renderer that
     * knows what a call is.
     *
     * <p>Every unanswered call writes the same line whatever the reason — see {@link CallEndReason}.
     */
    private void archive(Call call) {
        var conversation = conversations.requireById(call.conversationId());
        var author = users.requireByUsername(call.caller());
        var body = call.answeredAt() != null
                ? "_" + label(call.media()) + " · " + humanDuration(call.talkTime()) + "_"
                : "_Missed " + label(call.media()).toLowerCase(Locale.ROOT) + "_";

        var saved = conversations.post(conversation, author, body);
        // Broadcast it the same way an ordinary message is broadcast, or it would exist but not
        // appear until somebody reloaded the page they were just looking at.
        broker.convertAndSend("/topic/conversations/" + call.conversationId(),
                ConversationMessageDto.from(saved,
                        markdown.renderInConversation(saved.getBodyMarkdown())));
    }

    private static String label(CallMedia media) {
        return media == CallMedia.VIDEO ? "Video call" : "Call";
    }

    /**
     * "42 sec", "4 min", "1 h 05 min" — the resolution people actually want. Seconds stop being
     * interesting the moment there are minutes, and a call is never reported to the second because
     * nobody has ever needed that from a chat log.
     */
    static String humanDuration(Duration d) {
        long seconds = Math.max(0, d.toSeconds());
        if (seconds < 60) return seconds + " sec";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " h " + String.format("%02d", minutes % 60) + " min";
    }

    /**
     * The other person in a callable conversation.
     *
     * @throws AccessDeniedException if the caller is not a member — the same bar as posting into it
     * @throws CallsUnavailableException if this conversation cannot host a call: a group (no
     *         transport for a third participant) or a note-to-self (nobody to ring)
     */
    private User requireCallable(Conversation conversation, User caller) {
        conversations.requireMember(conversation, caller);
        if (conversation.getType() != ConversationType.DIRECT) {
            throw new CallsUnavailableException();
        }
        return conversations.members(conversation).stream()
                .map(m -> m.getUser())
                .filter(u -> !u.getId().equals(caller.getId()))
                .findFirst()
                // A DM with yourself is a real conversation and has exactly one member. There is
                // nobody to call, and the UI knows it — the button is not rendered — so reaching
                // here means a hand-made frame.
                .orElseThrow(CallsUnavailableException::new);
    }

    private void send(String username, CallEvent event) {
        broker.convertAndSendToUser(username, "/queue/calls", event);
    }

    private String displayName(User user) {
        var name = user.getDisplayName();
        return name == null || name.isBlank() ? user.getUsername() : name;
    }

    /** Calls are not available here — not configured, or not this kind of conversation. */
    public static class CallsUnavailableException extends RuntimeException {
        public CallsUnavailableException() {
            super("Calling is not available for this conversation");
        }
    }
}
