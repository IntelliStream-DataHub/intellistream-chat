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

package com.example.chat.attachments;

import com.example.chat.security.UploadTooLargeException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Shared low-level byte handling for attachment uploads (channel + DM). Both
 * {@link com.example.chat.service.AttachmentService} and
 * {@link com.example.chat.service.ConversationAttachmentService} stream their
 * bytes through these helpers so there's a single implementation of size-capping,
 * MIME sniffing, and the on-disk write path.
 */
public final class AttachmentBytes {

    /**
     * Server-side default cap on a single uploaded attachment. Applies when the
     * caller doesn't pass an explicit limit (e.g. when the user has no
     * {@code chat_max_upload_bytes} Keycloak attribute set). Admins and users
     * with an explicit attribute get a different value via
     * {@link com.example.chat.security.CurrentUser#uploadCapBytes}.
     */
    public static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024; // 50 MiB

    /** Sentinel: pass this as {@code maxBytes} to disable the cap entirely (admins). */
    public static final long UNLIMITED = -1L;

    private AttachmentBytes() {}

    /**
     * Peek at the leading bytes and pick the more trustworthy MIME. If the sniffed type
     * disagrees with what the client declared (especially when the client claimed an image
     * type), we trust the sniff so we can't be tricked into rendering attacker-controlled
     * HTML inline by a future thumbnail endpoint.
     */
    public static String sniffContentType(BufferedInputStream in, String declared) throws IOException {
        in.mark(4096);
        var sniffed = URLConnection.guessContentTypeFromStream(in);
        in.reset();
        if (sniffed == null) return declared;
        return sniffed.equalsIgnoreCase(declared) ? declared : sniffed;
    }

    /**
     * Copy bytes from {@code in} into {@code target} chunk by chunk, aborting if the
     * total exceeds {@code maxBytes}. Pass {@link #UNLIMITED} (or any value &lt; 0)
     * to skip the cap entirely. The stream is closed when this returns.
     */
    public static long streamToFile(InputStream in, Path target, long maxBytes) throws IOException {
        var buffer = new byte[8 * 1024];
        long total = 0;
        try (in;
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (maxBytes >= 0 && total > maxBytes) {
                    throw new UploadTooLargeException(maxBytes);
                }
                out.write(buffer, 0, n);
            }
        }
        return total;
    }
}
