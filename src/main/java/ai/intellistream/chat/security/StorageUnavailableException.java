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
 * Thrown when the server cannot store bytes at all: the attachments filesystem is full
 * (ENOSPC), the dataset quota is spent (EDQUOT), or free space has fallen below the
 * {@code ichat.attachments.min-free-bytes} floor we refuse to eat into.
 *
 * <p>Unlike {@link StorageQuotaExceededException} this is <b>the server's fault, not the
 * caller's</b>, which is why it maps to 507 Insufficient Storage rather than a 4xx. A full disk
 * is an operational incident: the response says so plainly, {@code Retry-After} tells clients not
 * to hammer, and the log line it comes with is written at ERROR so it shows up in whatever the
 * operator actually watches. What it must never be is the generic 500 that every other unhandled
 * {@code IOException} produces, because "500 on every upload" reads as a code bug and sends
 * whoever is on call looking in the wrong place.
 *
 * <p>Raising this instead of letting the {@code IOException} escape also guarantees the partial
 * file has already been removed — see {@code AttachmentBytes.streamToFile}. A disk that is full
 * because of half-written uploads nobody can delete is the failure mode worth avoiding.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message) {
        super(message);
    }

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
