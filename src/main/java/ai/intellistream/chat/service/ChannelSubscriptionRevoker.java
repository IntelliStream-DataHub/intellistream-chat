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
 * Tear down a user's live subscriptions to one channel's topics.
 *
 * <p>This exists because membership becoming <em>removable</em> breaks an assumption the realtime
 * layer rested on. STOMP SUBSCRIBE is authorised once, at the moment the frame arrives; the broker
 * never re-checks. So a user with an open socket who leaves — or is removed from — a private channel
 * keeps receiving every message broadcast to it until they happen to reconnect. Evicting
 * {@code ChannelAccessCache} stops them subscribing <em>again</em> and does nothing at all about the
 * subscription they already have. A page-reload test cannot see this, because reloading is exactly
 * the thing that fixes it.
 *
 * <p>An interface in the service layer with its implementation up in {@code config} because the fix
 * needs the broker's own plumbing — the user registry and the client-inbound channel — which the
 * service layer neither has nor should acquire. {@code ChannelService} resolves it through an
 * {@code ObjectProvider} and does nothing when it is absent, which is the case in the
 * service-and-repository-only integration test context.
 */
public interface ChannelSubscriptionRevoker {

    /**
     * Drop every subscription {@code userId} holds on {@code /topic/channels/{channelId}} and its
     * sub-destinations, across all of their open sessions on this node.
     *
     * <p>Called after the membership row is gone and the transaction has committed. Idempotent, and
     * a no-op for a user with nothing open.
     */
    void revoke(long channelId, long userId);

    /**
     * Drop <em>everybody's</em> subscriptions to {@code /topic/channels/{channelId}} and its
     * sub-destinations — for a channel that no longer exists.
     *
     * <p>Not the same job as {@link #revoke} with a loop over the membership, and the difference is
     * the whole reason this method exists: a PUBLIC channel's topic can be subscribed to by people
     * who never joined it, because SUBSCRIBE authorisation goes through {@code requireMember}, which
     * short-circuits to allowed for PUBLIC. Iterating members would leave exactly those sessions
     * holding a subscription to a destination that no longer has a row behind it.
     *
     * <p>Called after a channel is destroyed, and hygiene rather than a leak: nothing can ever be
     * published to that topic again, since every path to it starts with a lookup that now throws. What
     * it prevents is the broker's subscription registry accumulating dead destinations for the life of
     * each socket — and {@code DefaultSubscriptionRegistry}'s destination cache is the one piece of
     * broker state this application is already documented as having to size deliberately.
     *
     * <p>Called <em>after</em> the {@code channel-deleted} broadcast, never before: the frame that
     * tells open clients the channel is gone travels on the subscription this tears down.
     */
    void revokeAll(long channelId);
}
