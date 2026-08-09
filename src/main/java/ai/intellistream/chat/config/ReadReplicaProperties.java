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

package ai.intellistream.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for an optional Postgres read replica, bound from
 * {@code ichat.datasource.replica.*}.
 *
 * <p>Off by default: with {@code enabled=false} (or absent) nothing in
 * {@link ReadReplicaDataSourceConfig} is registered and Boot's ordinary
 * {@code DataSourceAutoConfiguration} builds the single pool it always has. That is the same
 * "optional means genuinely absent" contract {@link VaultEnvironmentPostProcessor} follows —
 * a deployment that never sets these properties has no second pool, no proxy, and no new
 * failure mode.
 *
 * <p>{@code username} and {@code password} fall back to the primary's
 * ({@code spring.datasource.*}) when left blank, because a streaming replica usually carries the
 * same roles as the machine it replicates. Set them only when the replica has its own login —
 * a dedicated read-only role, or a pooler in front of it.
 *
 * <p>All three of {@code url}, {@code username} and {@code password} can equally come from Vault;
 * see {@link VaultEnvironmentPostProcessor} for the record shape.
 */
@ConfigurationProperties("ichat.datasource.replica")
public class ReadReplicaProperties {

    /** Master switch. False (the default) means no replica pool exists at all. */
    private boolean enabled = false;

    /** JDBC URL of the replica. Required when enabled — boot fails loudly if it is blank. */
    private String url = "";

    /** Replica login. Blank inherits {@code spring.datasource.username}. */
    private String username = "";

    /** Replica password. Blank inherits {@code spring.datasource.password}. */
    private String password = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    /** True once the replica has somewhere to connect to. */
    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }
}
