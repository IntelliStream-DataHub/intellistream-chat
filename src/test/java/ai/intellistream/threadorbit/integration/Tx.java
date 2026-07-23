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

package ai.intellistream.threadorbit.integration;

import org.springframework.test.context.transaction.TestTransaction;

/**
 * Tiny helpers around Spring's {@link TestTransaction} for IT classes that need
 * {@code afterCommit} hooks to actually fire. The IT classes are {@code @Transactional}
 * at class level (each test method rolls back at the end for isolation), so service
 * methods that register {@code TransactionSynchronization.afterCommit} hooks never run
 * those hooks under test — the outer test tx rolls back before the commit phase.
 *
 * <p>{@link #commit()} is the standard pattern: flag the current tx for commit, end it
 * (which fires {@code afterCommit}), and start a new one so the rest of the test stays
 * inside an automatic-rollback container.
 *
 * <p>This pattern is needed for tests exercising:
 * <ul>
 *   <li>Lucene index updates (via {@link ai.intellistream.threadorbit.service.MessageService})
 *       — the indexer defers writes to {@code afterCommit} so a rolled-back tx never
 *       leaves stale entries in the index.</li>
 *   <li>Filesystem cleanup on message delete — same reason; rolled-back deletes shouldn't
 *       strand files on disk.</li>
 * </ul>
 */
final class Tx {

    private Tx() {}

    /**
     * Commit the current test transaction so {@code afterCommit} hooks fire, then start
     * a fresh transaction so the rest of the test still benefits from the automatic
     * rollback semantics that the {@code @Transactional} class-level annotation provides.
     */
    static void commit() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }
}
