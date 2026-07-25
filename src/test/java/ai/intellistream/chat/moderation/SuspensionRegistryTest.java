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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The registry is what both enforcement points actually ask, so what matters here is that the
 * cheap answer ({@code anySuspended}) never disagrees with the real one, and that both lookup keys
 * stay in step.
 */
class SuspensionRegistryTest {

    private final SuspensionRegistry registry = new SuspensionRegistry(mock(DataSource.class));

    private static User user(long id, String subject) {
        var user = new User(subject, "u" + id, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void anEmptyRegistrySaysNoToEverything() {
        assertThat(registry.anySuspended()).isFalse();
        assertThat(registry.isSuspended(1L)).isFalse();
        assertThat(registry.isSuspendedSubject("kc-1")).isFalse();
        assertThat(registry.isSuspended(null)).isFalse();
        assertThat(registry.isSuspendedSubject(null)).isFalse();
    }

    @Test
    void aSuspendedUserIsFoundByIdAndBySubject() {
        registry.suspend(user(7L, "kc-7"));

        assertThat(registry.anySuspended()).isTrue();
        assertThat(registry.isSuspended(7L)).isTrue();
        assertThat(registry.isSuspendedSubject("kc-7")).isTrue();
        assertThat(registry.isSuspended(8L)).isFalse();
        assertThat(registry.isSuspendedSubject("kc-8")).isFalse();
    }

    @Test
    void theFastPathStaysTrueWhileAnyoneIsStillSuspended() {
        registry.suspend(user(1L, "kc-1"));
        registry.suspend(user(2L, "kc-2"));

        registry.unsuspend(user(1L, "kc-1"));

        // The volatile short-circuit must not go false while user 2 is still banned — that is the
        // one direction of staleness that lets a suspended account keep sending frames.
        assertThat(registry.anySuspended()).isTrue();
        assertThat(registry.isSuspended(2L)).isTrue();
        assertThat(registry.isSuspended(1L)).isFalse();

        registry.unsuspend(user(2L, "kc-2"));

        assertThat(registry.anySuspended()).isFalse();
    }

    @Test
    void anUnsavedUserIsIgnoredRatherThanIndexedUnderNull() {
        registry.suspend(new User("kc-new", "new", null, null));

        assertThat(registry.anySuspended()).isFalse();
    }
}
