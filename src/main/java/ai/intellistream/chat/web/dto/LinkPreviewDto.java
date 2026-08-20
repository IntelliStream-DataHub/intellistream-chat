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

import ai.intellistream.chat.domain.LinkPreview;

/**
 * The card under a message that contains a link: what the page said about itself, and where the
 * server's copy of its picture is served from. {@code imageUrl} is a path on this origin
 * ({@code /api/link-previews/images/<key>}), never the page's own image URL — see the CSP and
 * privacy notes on {@code LinkPreviewService}. Null fields are simply not rendered; only
 * {@code url} and {@code title} are always present.
 */
public record LinkPreviewDto(
        String url,
        String title,
        String description,
        String siteName,
        String imageUrl
) {
    public static final String IMAGE_PATH = "/api/link-previews/images/";

    /** Only for a {@link LinkPreview#isShowable() showable} row; the caller filters. */
    public static LinkPreviewDto from(LinkPreview p) {
        return new LinkPreviewDto(p.getUrl(), p.getTitle(), p.getDescription(), p.getSiteName(),
                p.hasImage() ? IMAGE_PATH + p.getImageKey() : null);
    }
}
