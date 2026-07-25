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

import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Soft delete only works if <em>every</em> query that reads messages filters removed rows out.
 * A single unfiltered finder is enough for a purged message to reappear — in a thread, in a
 * reply count, in the mention badge — and the failure is invisible until someone notices a
 * message that should be gone.
 *
 * <p>Whether the filter is <em>correct</em> is a question for the database and belongs in an
 * integration test. What this catches is the far more likely regression: a new finder added to
 * either repository months from now by someone who has never heard of {@code deleted_at}. The
 * exemptions below are the complete list of queries that see removed rows on purpose; adding to
 * it should require explaining why in the same commit.
 */
class MessageModerationReadPathFilterTest {

    /**
     * Queries that must NOT filter on the removal flag, and why:
     * <ul>
     *   <li>{@code findIdsByChannel} — runs just before the channel and all its rows are
     *       hard-deleted, to purge the index. Filtering would leave permanently stale documents.</li>
     *   <li>{@code findByIdIncludingDeleted} — how moderation reaches a removed message to
     *       restore it.</li>
     *   <li>{@code findRepliesIncludingDeleted} — the hard-delete path, which must enumerate
     *       what {@code on delete cascade} is about to take so files and index docs go with it.</li>
     *   <li>{@code deleteByIdIn} — the retention purge itself; its candidate set is chosen by
     *       {@code findPurgeableIds}.</li>
     * </ul>
     */
    private static final Set<String> EXEMPT = Set.of(
            "findIdsByChannel",
            "findByIdIncludingDeleted",
            "findRepliesIncludingDeleted",
            "deleteByIdIn");

    @Test
    void everyMessageQueryIsAwareOfSoftDelete() {
        // Every @Query in this repository reads or writes messages, so all of them are in scope.
        assertThat(unfiltered(MessageRepository.class, sql -> true))
                .as("MessageRepository queries with no deleted_at predicate — either add the "
                    + "filter or, if the query genuinely needs removed rows, name it "
                    + "...IncludingDeleted and add it to EXEMPT with a reason")
                .isEmpty();
    }

    @Test
    void mentionCountsAndTheInboxAreAwareOfSoftDelete() {
        // Mention rows outlive a message's visibility: they are only removed on a hard delete.
        // An unfiltered query here produces a bell badge the user cannot clear, because the
        // channel it points at no longer shows the message. Only the queries that read through
        // to the messages table are in scope; the rest never see a removal flag to respect.
        assertThat(unfiltered(MessageMentionRepository.class, sql -> sql.contains("join messages")))
                .as("MessageMentionRepository queries joining messages with no deleted_at predicate")
                .isEmpty();
    }

    @Test
    void theExemptionListItselfStaysHonest() {
        // A typo'd or removed exemption would silently stop guarding anything, and the tests
        // above would still pass.
        var declared = new TreeSet<String>();
        for (var method : MessageRepository.class.getDeclaredMethods()) {
            declared.add(method.getName());
        }
        assertThat(declared).containsAll(EXEMPT);
    }

    /** Names of in-scope {@code @Query} methods that neither mention the removal column nor
     *  are exempt. */
    private static Set<String> unfiltered(Class<?> repository, Predicate<String> inScope) {
        var offenders = new TreeSet<String>();
        for (Method method : repository.getDeclaredMethods()) {
            var query = method.getAnnotation(Query.class);
            if (query == null || EXEMPT.contains(method.getName())) {
                continue;
            }
            var sql = query.value().toLowerCase(java.util.Locale.ROOT);
            if (!inScope.test(sql)) {
                continue;
            }
            // Either spelling: JPQL reads the field (deletedAt), native SQL the column.
            if (!sql.contains("deletedat") && !sql.contains("deleted_at")) {
                offenders.add(method.getName());
            }
        }
        return offenders;
    }
}
