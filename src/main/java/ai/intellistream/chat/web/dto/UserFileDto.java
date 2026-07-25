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

package ai.intellistream.chat.web.dto;

import java.time.Instant;

/**
 * One row of the file manager (GET /files).
 *
 * <p>{@code scope} + {@code id} together identify the file, because channel and DM attachments are
 * separate tables with separate id sequences — {@code /api/files/channel/7} and
 * {@code /api/files/conversation/7} are different files. The pair is echoed back on delete, and the
 * server re-derives ownership from it rather than trusting either half.
 *
 * <p>{@code deletable} and {@code blockedReason} carry the delete policy to the UI so a refusal is
 * visible <em>before</em> the click rather than as an error after it — see
 * {@code UserFileService.delete} for what the policy is and why.
 */
public record UserFileDto(
        String scope,
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        Instant createdAt,
        String downloadUrl,
        /** "#general", a group DM's title, or the other participant's name for a direct message. */
        String locationLabel,
        /** Deep link to the message that posted the file, anchored on it where the page supports it. */
        String locationUrl,
        /** "channel" | "group" | "direct" — drives the icon, not the authorization. */
        String locationKind,
        boolean deletable,
        String blockedReason) {
}
