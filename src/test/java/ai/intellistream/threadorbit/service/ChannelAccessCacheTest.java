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
import ai.intellistream.threadorbit.domain.ChannelType;
import ai.intellistream.threadorbit.domain.User;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This cache sits in front of an authorisation decision, so the tests that matter are the ones
 * about what it must <em>not</em> remember.
 */
class ChannelAccessCacheTest {

    private static Channel channel(long id) {
        var creator = new User("sub-" + id, "creator", "c@example.com", "Creator");
        var channel = new Channel("c" + id, "c" + id, "", ChannelType.PUBLIC, creator);
        // Id is normally assigned by the database; set it directly for a unit test.
        try {
            var field = Channel.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(channel, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return channel;
    }

    @Test
    void loadsOnceThenServesFromCache() {
        var cache = new ChannelAccessCache(60, 1024);
        var loads = new AtomicInteger();

        var first = cache.channel(7, id -> { loads.incrementAndGet(); return channel(id); });
        var second = cache.channel(7, id -> { loads.incrementAndGet(); return channel(id); });

        assertThat(loads).hasValue(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    void doesNotRememberAccessItWasNeverTold() {
        // Negatives are never cached, so a user who has only just joined is never wrongly refused
        // by a stale "no".
        var cache = new ChannelAccessCache(60, 1024);

        assertThat(cache.hasWriteAccess(1, 2)).isFalse();
    }

    @Test
    void remembersAVerifiedPositive() {
        var cache = new ChannelAccessCache(60, 1024);

        cache.rememberWriteAccess(1, 2);

        assertThat(cache.hasWriteAccess(1, 2)).isTrue();
        assertThat(cache.hasWriteAccess(1, 3)).isFalse(); // scoped to the user
        assertThat(cache.hasWriteAccess(9, 2)).isFalse(); // and to the channel
    }

    @Test
    void expiresAPositiveOnceTheTtlPasses() throws InterruptedException {
        // The TTL is insurance against a future membership-removal path forgetting to invalidate.
        var cache = new ChannelAccessCache(1, 1024);
        cache.rememberWriteAccess(1, 2);
        assertThat(cache.hasWriteAccess(1, 2)).isTrue();

        Thread.sleep(1100);

        assertThat(cache.hasWriteAccess(1, 2)).isFalse();
    }

    @Test
    void evictingAChannelDropsItsCachedAccessDecisions() {
        // Destroying a channel is the one event that can invalidate a cached positive.
        var cache = new ChannelAccessCache(60, 1024);
        cache.channel(5, ChannelAccessCacheTest::channel);
        cache.rememberWriteAccess(5, 1);
        cache.rememberWriteAccess(5, 2);
        cache.rememberWriteAccess(6, 1); // a different channel, must survive

        cache.evictChannel(5);

        assertThat(cache.hasWriteAccess(5, 1)).isFalse();
        assertThat(cache.hasWriteAccess(5, 2)).isFalse();
        assertThat(cache.hasWriteAccess(6, 1)).isTrue();

        var loads = new AtomicInteger();
        cache.channel(5, id -> { loads.incrementAndGet(); return channel(id); });
        assertThat(loads).hasValue(1); // channel entry went too, so it reloads
    }

    @Test
    void evictingAMemberDropsOnlyThatMember() {
        var cache = new ChannelAccessCache(60, 1024);
        cache.rememberWriteAccess(5, 1);
        cache.rememberWriteAccess(5, 2);

        cache.evictMember(5, 1);

        assertThat(cache.hasWriteAccess(5, 1)).isFalse();
        assertThat(cache.hasWriteAccess(5, 2)).isTrue();
    }

    @Test
    void staysBoundedWhenTheKeyspaceIsUnbounded() {
        // Overflow clears wholesale rather than evicting one by one; the point is that memory
        // stays bounded, not that any particular entry survives.
        var cache = new ChannelAccessCache(60, 1024);

        for (long user = 0; user < 5000; user++) {
            cache.rememberWriteAccess(1, user);
        }

        assertThat(cache.hasWriteAccess(1, 4999)).isTrue(); // most recent survived the clear
    }

    @Test
    void clearDropsEverything() {
        var cache = new ChannelAccessCache(60, 1024);
        cache.rememberWriteAccess(1, 1);
        cache.channel(1, ChannelAccessCacheTest::channel);

        cache.clear();

        assertThat(cache.hasWriteAccess(1, 1)).isFalse();
    }
}
