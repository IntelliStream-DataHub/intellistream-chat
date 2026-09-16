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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** {@code ichat.secrets.*}; the meanings are documented in {@code application.yml}. */
@Component
@ConfigurationProperties("ichat.secrets")
public class SecretShareProperties {

    public static final int MAX_DISPLAY_SECONDS = 300;

    private int maxPlaintextBytes = 16 * 1024;
    private int maxWaitingPerUser = 50;
    private int displaySeconds = 300;
    private List<Duration> lifetimes = List.of(Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(7));
    private Duration defaultLifetime = Duration.ofDays(1);
    private Duration retention = Duration.ofDays(30);

    public int getMaxPlaintextBytes() { return maxPlaintextBytes; }
    public void setMaxPlaintextBytes(int maxPlaintextBytes) { this.maxPlaintextBytes = maxPlaintextBytes; }

    public int getMaxWaitingPerUser() { return maxWaitingPerUser; }
    public void setMaxWaitingPerUser(int maxWaitingPerUser) { this.maxWaitingPerUser = maxWaitingPerUser; }

    public int getDisplaySeconds() { return displaySeconds; }
    /**
     * How long a revealed secret stays on screen. Five minutes is the ceiling, not a default an
     * operator may raise: a secret left open on a screen is the thing this feature exists to avoid,
     * so a larger value fails at startup rather than being quietly honoured.
     */
    public void setDisplaySeconds(int displaySeconds) {
        if (displaySeconds < 1 || displaySeconds > MAX_DISPLAY_SECONDS) {
            throw new IllegalArgumentException("ichat.secrets.display-seconds must be between 1 and "
                    + MAX_DISPLAY_SECONDS + ", was " + displaySeconds);
        }
        this.displaySeconds = displaySeconds;
    }

    public List<Duration> getLifetimes() { return lifetimes; }
    public void setLifetimes(List<Duration> lifetimes) { this.lifetimes = List.copyOf(lifetimes); }

    public Duration getDefaultLifetime() { return defaultLifetime; }
    public void setDefaultLifetime(Duration defaultLifetime) { this.defaultLifetime = defaultLifetime; }

    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
}
