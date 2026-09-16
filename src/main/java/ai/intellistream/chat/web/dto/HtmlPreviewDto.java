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

/**
 * An uploaded HTML file, as the uploader wrote it.
 *
 * <p><b>{@code source} is untrusted markup and is not sanitised.</b> That is deliberate — an HTML
 * file previewed with its {@code <style>} blocks stripped is not a preview of that file — and it
 * is why the field is named for what it is rather than {@code html}, which is what every other
 * DTO in this package calls markup that has already been through the safelist.
 *
 * <p>It is safe to show only one way: assigned to the {@code srcdoc} of an iframe carrying a bare
 * {@code sandbox} attribute (no {@code allow-scripts}, no {@code allow-same-origin}), which puts
 * it in an opaque origin with scripting off. Two further things follow from staying inside
 * {@code srcdoc} rather than being served as a document: this application never responds
 * {@code text/html} with somebody's upload in it — so there is no URL that could be opened at the
 * top level and become stored XSS — and the frame inherits the page's CSP, so the file cannot
 * fetch anything off-origin and cannot report who read it. Serving these bytes any other way
 * undoes all of that.
 *
 * @param filename  what to title the viewer with
 * @param source    the file's own markup, capped at {@link
 *                  ai.intellistream.chat.attachments.PreviewableAttachments#MAX_HTML_CHARS}
 * @param truncated the file was longer than that and this is its beginning
 */
public record HtmlPreviewDto(String filename, String source, boolean truncated) {}
