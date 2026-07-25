/*
 * Copyright 2026 Olav Gjerde
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

package ai.intellistream.threadorbit.service;

import ai.intellistream.threadorbit.domain.Channel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * Hot-path cache for the two lookups every posted message otherwise repeats: "what channel is
 * this?" and "may this user write to it?". Both were a database round trip per message, together
 * roughly 40% of the handler's time under load.
 *
 * <p><b>Why this is safe today, and exactly what that depends on.</b>
 * <ul>
 *   <li>Membership is <b>add-only</b>: {@code join} and {@code invite} add rows, and nothing in the
 *       application removes one short of deleting the whole channel, which evicts. So only
 *       <em>positive</em> write-access answers are cached — a "yes" cannot silently become a "no",
 *       while a user who has just joined is never held back by a cached "no", because negatives are
 *       never stored.</li>
 *   <li>{@link Channel} is immutable after creation — it exposes no setters, so a cached copy
 *       cannot go stale against a rename or a PUBLIC↔PRIVATE flip. This is now enforced by the
 *       type rather than by convention: {@code ChannelImmutabilityTest} fails if a setter
 *       reappears. (It briefly was not true — the fields carried Lombok {@code @Setter} with no
 *       caller, which a source grep for {@code void set} cannot reveal.)</li>
 * </ul>
 *
 * <p><b>Why immutability is load-bearing.</b> The cached {@code Channel} is what
 * {@code StompAuthorizationConfig} hands to {@code ChannelService.requireMember} when authorizing a
 * STOMP SUBSCRIBE, and {@code requireMember} short-circuits to "allowed" for PUBLIC channels. If a
 * channel could be flipped to PRIVATE, a stale cached copy would let a non-member subscribe to it
 * for up to the TTL. So a channel change must go through a {@code ChannelService} method that also
 * calls {@link #evictChannel}; likewise a future membership-removal path must call
 * {@link #evictMember}. The TTL bounds the damage; it is not the guarantee.
 *
 * <p>Entries are capped; on overflow the map is cleared wholesale rather than evicted one by one.
 * Rebuilding costs one query per active channel, which is trivially cheaper than maintaining LRU
 * order on a path this hot.
 */
@Component
public class ChannelAccessCache {

    /** Cached channels, keyed by id. Values are detached entities — read-only, no lazy access. */
    private final ConcurrentHashMap<Long, Entry<Channel>> channels = new ConcurrentHashMap<>();
    /** Cached positive write-access decisions, keyed by (channel, user). */
    private final ConcurrentHashMap<MemberKey, Long> writeAccessUntil = new ConcurrentHashMap<>();

    private final long ttlNanos;
    private final int maxEntries;

    public ChannelAccessCache(@Value("${threadorbit.cache.channel-ttl-seconds:60}") long ttlSeconds,
                              @Value("${threadorbit.cache.max-entries:100000}") int maxEntries) {
        this.ttlNanos = Math.max(1, ttlSeconds) * 1_000_000_000L;
        this.maxEntries = Math.max(1024, maxEntries);
    }

    /** Look up a channel, loading and caching it on a miss. */
    public Channel channel(long channelId, LongFunction<Channel> loader) {
        var now = System.nanoTime();
        var hit = channels.get(channelId);
        if (hit != null && hit.expiresAt - now > 0) {
            return hit.value;
        }
        var loaded = loader.apply(channelId);
        if (channels.size() >= maxEntries) {
            channels.clear();
        }
        channels.put(channelId, new Entry<>(loaded, now + ttlNanos));
        return loaded;
    }

    /** True when this (channel, user) pair has a cached, unexpired "may write" decision. */
    public boolean hasWriteAccess(long channelId, long userId) {
        var until = writeAccessUntil.get(new MemberKey(channelId, userId));
        return until != null && until - System.nanoTime() > 0;
    }

    /** Record that a user may write to a channel. Only ever called with a verified positive. */
    public void rememberWriteAccess(long channelId, long userId) {
        if (writeAccessUntil.size() >= maxEntries) {
            writeAccessUntil.clear();
        }
        writeAccessUntil.put(new MemberKey(channelId, userId), System.nanoTime() + ttlNanos);
    }

    /** Drop everything cached for a channel — call when the channel itself goes away. */
    public void evictChannel(long channelId) {
        channels.remove(channelId);
        writeAccessUntil.keySet().removeIf(key -> key.channelId() == channelId);
    }

    /** Drop one member's cached access — call this if a membership-removal path is ever added. */
    public void evictMember(long channelId, long userId) {
        writeAccessUntil.remove(new MemberKey(channelId, userId));
    }

    /** Visible for tests / operational reset. */
    public void clear() {
        channels.clear();
        writeAccessUntil.clear();
    }

    private record Entry<T>(T value, long expiresAt) {}

    private record MemberKey(long channelId, long userId) {}
}
