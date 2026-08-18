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

package ai.intellistream.chat.linkpreview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code ichat.link-previews.*}; the meanings are documented in {@code application.yml}. */
@Component
@ConfigurationProperties("ichat.link-previews")
public class LinkPreviewProperties {

    private boolean enabled = true;
    private String dir = "./data/link-previews";
    private long maxHtmlBytes = 512 * 1024;
    private long maxImageBytes = 3 * 1024 * 1024;
    private Duration timeout = Duration.ofSeconds(6);
    private Duration refreshAfter = Duration.ofDays(7);
    private Duration retryFailedAfter = Duration.ofHours(1);
    private Duration retention = Duration.ofDays(180);
    private int threads = 2;
    private int queueCapacity = 500;
    private String userAgent = "Mozilla/5.0 (compatible; IntelliStreamChat-LinkPreview/1.0; +https://intellistream.ai/chat)";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }

    public long getMaxHtmlBytes() { return maxHtmlBytes; }
    public void setMaxHtmlBytes(long maxHtmlBytes) { this.maxHtmlBytes = maxHtmlBytes; }

    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public Duration getRefreshAfter() { return refreshAfter; }
    public void setRefreshAfter(Duration refreshAfter) { this.refreshAfter = refreshAfter; }

    public Duration getRetryFailedAfter() { return retryFailedAfter; }
    public void setRetryFailedAfter(Duration retryFailedAfter) { this.retryFailedAfter = retryFailedAfter; }

    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }

    public int getThreads() { return threads; }
    public void setThreads(int threads) { this.threads = threads; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
