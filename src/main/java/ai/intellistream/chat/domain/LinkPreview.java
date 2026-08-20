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

package ai.intellistream.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * What the server learned about a URL somebody posted: the Open Graph / HTML title and
 * description, the site's name, and the key of the image it copied to disk, if any. One row per
 * URL — a preview is a fact about the page, not about the message — so a message never carries a
 * preview column; the URL is derived from its body at read time and looked up here. See
 * {@code V14__link_previews.sql} for the shape and {@code LinkPreviewService} for the rules.
 */
@Entity
@Table(name = "link_previews",
        uniqueConstraints = @UniqueConstraint(name = "uk_link_previews_url_hash", columnNames = "url_hash"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkPreview {

    public enum Status {
        /** The page answered with a title; the card can be shown. */
        FETCHED,
        /** The page answered but had nothing worth a card. Negative-cached. */
        EMPTY,
        /** Refused, unreachable, or not HTML. Negative-cached. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex of {@link #url}; the lookup key, because a URL is too long to index sanely. */
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(length = 300)
    private String title;

    @Column(length = 600)
    private String description;

    @Column(name = "site_name", length = 120)
    private String siteName;

    /** Filename under the link-previews storage dir; the client is given this and nothing else. */
    @Column(name = "image_key", length = 64)
    private String imageKey;

    @Column(name = "image_content_type", length = 80)
    private String imageContentType;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** Bumped when a message posts the URL, never on read; retention reads this. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public LinkPreview(String urlHash, String url) {
        this.urlHash = urlHash;
        this.url = url;
        this.status = Status.FAILED;
        var now = Instant.now();
        this.fetchedAt = now;
        this.lastSeenAt = now;
    }

    public void fetched(String title, String description, String siteName,
                       String imageKey, String imageContentType) {
        this.status = Status.FETCHED;
        this.title = title;
        this.description = description;
        this.siteName = siteName;
        this.imageKey = imageKey;
        this.imageContentType = imageContentType;
        this.fetchedAt = Instant.now();
    }

    public void empty() {
        outcome(Status.EMPTY);
    }

    public void failed() {
        outcome(Status.FAILED);
    }

    private void outcome(Status status) {
        this.status = status;
        this.title = null;
        this.description = null;
        this.siteName = null;
        this.imageKey = null;
        this.imageContentType = null;
        this.fetchedAt = Instant.now();
    }

    public void seen() {
        this.lastSeenAt = Instant.now();
    }

    public boolean isShowable() {
        return status == Status.FETCHED;
    }

    public boolean hasImage() {
        return imageKey != null;
    }
}
