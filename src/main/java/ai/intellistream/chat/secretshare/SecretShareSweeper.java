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

package ai.intellistream.chat.secretshare;

import ai.intellistream.chat.repository.SecretShareRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Housekeeping for one-time secrets, in two steps with different clocks.
 *
 * <ol>
 *   <li><b>Forget expired ciphertext</b>, every few minutes. Not what keeps an expired secret shut —
 *       {@code SecretShareService} refuses one the moment {@code expires_at} passes, swept or not —
 *       but what keeps ciphertext from sitting in the table for as long as a creator never looks.</li>
 *   <li><b>Delete ended rows</b> once {@code ichat.secrets.retention} has passed since they ended, and
 *       with them who opened it, and for an opener without an account the address and browser
 *       summary. The receipt message in the creator's conversation is not touched, which is why
 *       it never contains those two. The retention is what the creator's list can show; it is also the promise
 *       the docs make about how long an outsider's address is kept.</li>
 * </ol>
 *
 * <p>Both are single bulk statements, so neither loads a row. They run on every node, which is
 * harmless: each is idempotent.
 */
@Component
public class SecretShareSweeper {

    private static final Logger log = LoggerFactory.getLogger(SecretShareSweeper.class);

    private final SecretShareRepository repo;
    private final SecretShareProperties props;
    private final TransactionTemplate tx;

    public SecretShareSweeper(SecretShareRepository repo, SecretShareProperties props,
                              PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.props = props;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Swept(int payloadsCleared, int rowsDeleted) {}

    @Scheduled(fixedDelayString = "${ichat.secrets.sweep-ms:300000}",
               initialDelayString = "${ichat.secrets.sweep-initial-delay-ms:60000}")
    public void sweepScheduled() {
        try {
            var swept = sweep(Instant.now());
            if (swept.payloadsCleared() > 0 || swept.rowsDeleted() > 0) {
                log.info("Secrets: cleared {} expired payloads, deleted {} ended secrets",
                        swept.payloadsCleared(), swept.rowsDeleted());
            }
        } catch (RuntimeException e) {
            log.warn("Secret sweep failed; will retry on the next run", e);
        }
    }

    /** One pass at a given instant; public so tests need not wait for the scheduler. */
    public Swept sweep(Instant now) {
        int cleared = orZero(tx.execute(status -> repo.clearExpiredPayloads(now)));
        int deleted = orZero(tx.execute(status -> repo.deleteEndedBefore(now.minus(props.getRetention()))));
        return new Swept(cleared, deleted);
    }

    private static int orZero(Integer n) {
        return n == null ? 0 : n;
    }
}
