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

package ai.intellistream.chat.security;

/**
 * Thrown when an upload would push an account past its total storage allowance —
 * {@code user_storage.quota_bytes}, or the {@code ichat.attachments.user-quota-bytes} default.
 *
 * <p>Sibling of {@link UploadTooLargeException} and deliberately distinct from it: "this one file
 * is bigger than you may send" and "your account has no room left" need different words in the UI
 * and different fixes by the user (shrink the file vs. delete something). Both surface as 413 so a
 * client that only knows the older error still fails gracefully; the {@code code} in the body is
 * what tells them apart.
 *
 * <p>{@code quotaBytes} and {@code usedBytes} are the values read when the upload started, so the
 * message can say "1.9 of 2.0 GiB used" rather than a bare refusal. They are a snapshot, not a
 * live reading: a concurrent upload from the same account may have moved {@code usedBytes} since.
 */
public class StorageQuotaExceededException extends RuntimeException {

    private final long quotaBytes;
    private final long usedBytes;

    public StorageQuotaExceededException(long quotaBytes, long usedBytes) {
        super("Storage quota exceeded (" + mib(usedBytes) + " of " + mib(quotaBytes) + " MiB used)");
        this.quotaBytes = quotaBytes;
        this.usedBytes = usedBytes;
    }

    public long getQuotaBytes() {
        return quotaBytes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    /** Never negative — an account can end up slightly over quota, and "-3 MiB left" helps nobody. */
    public long getRemainingBytes() {
        return Math.max(0L, quotaBytes - usedBytes);
    }

    private static long mib(long bytes) {
        return bytes / (1024 * 1024);
    }
}
