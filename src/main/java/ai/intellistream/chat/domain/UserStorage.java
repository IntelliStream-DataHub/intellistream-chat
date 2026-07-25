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

package ai.intellistream.chat.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-user attachment usage and optional quota.
 *
 * <p>Maintained incrementally rather than summed on demand: the sum would join attachments to
 * messages to find the uploader, on the upload path, which is the one path already tuned for
 * throughput.
 *
 * <p><b>This is not a disk guarantee.</b> The filesystem quota is what actually stops the volume
 * filling; a ZFS dataset quota on the attachments directory means a runaway upload fails a write
 * instead of taking the host down. This exists so one account cannot consume everybody else's
 * share long before the filesystem notices, and so the admin screen has a number to show.
 */
@Entity
@Table(name = "user_storage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserStorage {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "bytes_used", nullable = false)
    private long bytesUsed;

    /** Null means "use the configured default". A value here overrides it for this user. */
    @Column(name = "quota_bytes")
    private Long quotaBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserStorage(Long userId) {
        this.userId = userId;
    }

    public void setQuotaBytes(Long quotaBytes) {
        this.quotaBytes = quotaBytes;
        this.updatedAt = Instant.now();
    }
}
