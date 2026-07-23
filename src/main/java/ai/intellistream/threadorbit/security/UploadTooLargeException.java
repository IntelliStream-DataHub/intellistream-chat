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

package ai.intellistream.threadorbit.security;

/**
 * Thrown when an upload exceeds the per-user cap from
 * {@link ai.intellistream.threadorbit.security.CurrentUser#uploadCapBytes}. Distinct from a generic
 * {@link IllegalArgumentException} so {@link ai.intellistream.threadorbit.web.ApiExceptionHandler}
 * can return the actual byte limit to the client without leaking unrelated internal
 * messages, and so the JS can render an informative "max XX MiB" error instead of the
 * default redacted "Request rejected.".
 */
public class UploadTooLargeException extends RuntimeException {

    private final long maxBytes;

    public UploadTooLargeException(long maxBytes) {
        super("File too large (max " + (maxBytes / (1024 * 1024)) + " MiB)");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
