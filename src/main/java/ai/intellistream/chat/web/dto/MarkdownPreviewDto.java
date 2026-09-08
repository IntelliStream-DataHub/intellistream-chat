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
 * An uploaded markdown file, rendered. The client never sees the file itself: the bytes are read
 * server-side and go through the same CommonMark parse and jsoup safelist as a message body, so
 * what arrives here is the sanitised HTML — which is what makes showing a user-uploaded document
 * in the page safe at all.
 *
 * @param filename  what to title the viewer with
 * @param html      sanitised HTML, rendered by {@code MarkdownRenderer.renderDocument}
 * @param truncated the file was longer than the preview cap and this is its beginning
 */
public record MarkdownPreviewDto(String filename, String html, boolean truncated) {}
