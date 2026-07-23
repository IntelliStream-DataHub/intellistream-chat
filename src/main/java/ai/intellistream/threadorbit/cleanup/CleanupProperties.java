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

package ai.intellistream.threadorbit.cleanup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Config for the scheduled cleanup sweeps (orphan files + Lucene↔DB reconcile). Backstops for the
 * write-path crash windows (BUG-9/10/21). See the {@code CLEAN-*} items in tasks.md and the
 * datahub-cleanup comparison.
 *
 * <p><b>Defaults are deliberately safe:</b> {@code dry-run=true}, so out of the box the sweeps run
 * and LOG what they would delete/reindex but change nothing. An operator watches the logged
 * backlog, then sets {@code threadorbit.cleanup.dry-run=false} to arm the destructive deletes.
 *
 * <p><b>Single-instance only</b> (CLEAN-5): {@code @EnableScheduling} runs on every node, so with
 * multiple nodes these sweeps would race. Disable them ({@code enabled=false}) on all but one node,
 * or leave them for single-node deployments; a Postgres-advisory-lock guard is deferred with the
 * rest of horizontal scaling.
 */
@Component
@ConfigurationProperties("threadorbit.cleanup")
public class CleanupProperties {

    /** Master switch for all sweeps. */
    private boolean enabled = true;

    /** When true (default), log what WOULD be deleted/reindexed but make no changes. */
    private boolean dryRun = true;

    /** Files younger than this are never treated as orphans — spares a file uploaded but whose
     *  DB row hasn't committed yet. */
    private Duration grace = Duration.ofHours(24);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public Duration getGrace() { return grace; }
    public void setGrace(Duration grace) { this.grace = grace; }
}
