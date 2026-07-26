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

/**
 * Tear down a departing member's live subscriptions to one conversation's topics.
 *
 * <p>{@link ChannelSubscriptionRevoker}'s reasoning applies here and applies harder. STOMP SUBSCRIBE
 * is authorised once, when the frame arrives, and the broker never re-checks — so somebody who
 * leaves a group DM with an open socket keeps receiving every message sent to it until they happen
 * to reconnect. A channel at least has a PUBLIC tier where that would have been allowed anyway; a
 * conversation has none. Every message in one is private to its participants, and an ex-participant
 * still hearing them is the whole reason this half exists.
 *
 * <p>It is also the half a page-reload test cannot see, because reloading is exactly the thing that
 * fixes it.
 *
 * <p>Separate interface from the channel one rather than a shared "revoke this topic": the two are
 * asked for by different services with different vocabularies, and a single method taking a
 * destination string would put topic-naming — a transport concern — in the service layer. The walk
 * they share lives in {@code StompSubscriptionSweeper}, on the config side where the broker's
 * plumbing already is.
 */
public interface ConversationSubscriptionRevoker {

    /**
     * Drop every subscription {@code userId} holds on {@code /topic/conversations/{conversationId}}
     * and its sub-destinations ({@code /typing}), across all of their open sessions on this node.
     *
     * <p>Called after the membership row is gone and the transaction has committed. Idempotent, and
     * a no-op for a user with nothing open.
     */
    void revoke(long conversationId, long userId);
}
