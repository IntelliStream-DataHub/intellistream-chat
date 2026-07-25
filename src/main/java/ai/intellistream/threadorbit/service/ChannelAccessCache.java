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
 * <p><b>Why this is safe here.</b> Two properties of the domain make it sound rather than a
 * gamble:
 * <ul>
 *   <li>{@link Channel} is immutable once created — the entity exposes no setters, so there is no
 *       rename or PUBLIC↔PRIVATE flip that a cached copy could go stale against. A channel's only
 *       lifecycle events are create and destroy, and destroy invalidates explicitly.</li>
 *   <li>Membership is <b>add-only</b>: {@code join} and {@code invite} add rows, and nothing in the
 *       application removes one short of deleting the whole channel. So only <em>positive</em>
 *       write-access answers are cached — a "yes" cannot silently become a "no", while a user who
 *       has just joined is never held back by a cached "no", because negatives are never stored.
 *   </li>
 * </ul>
 *
 * <p>The TTL is therefore not needed for correctness today; it is deliberate insurance against a
 * future membership-removal path whose author forgets to invalidate here. If you add one, call
 * {@link #evictMember} from it and treat the TTL as the backstop it is.
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
