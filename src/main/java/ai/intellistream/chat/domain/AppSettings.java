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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * Singleton-row table for admin-editable application branding (topbar title + uploaded logo).
 * The id is fixed at {@code 1}; never insert a second row — a {@code CHECK (id = 1)} on the
 * column will reject it.
 */
@Entity
@Table(name = "app_settings")
@Getter
public class AppSettings {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id = SINGLETON_ID;

    @Column(name = "title", nullable = false, length = 120)
    private String title = "IntelliStream Chat";

    /** Filename (relative to {@code chat.branding.dir}) of the uploaded logo, or null when on default. */
    @Column(name = "logo_path", length = 255)
    private String logoPath;

    @Column(name = "logo_content_type", length = 64)
    private String logoContentType;

    @Column(name = "logo_updated_at")
    private Instant logoUpdatedAt;

    /**
     * When {@code true} the admin user table renders raw email addresses; when {@code false}
     * each row is masked as {@code al…@example.com} (or just the local-part initial when no
     * email exists). Default is {@code true} for backward compatibility with existing installs.
     */
    @Column(name = "expose_user_emails", nullable = false)
    private boolean exposeUserEmails = true;

    /**
     * Who may create channels. Defaults to {@link ChannelCreationPolicy#EVERYONE}, which is what
     * every deployment did before this column existed, so the migration changes no behaviour.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_creation", nullable = false, length = 16)
    private ChannelCreationPolicy channelCreation = ChannelCreationPolicy.EVERYONE;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean hasCustomLogo() { return logoPath != null; }

    public boolean isExposeUserEmails() { return exposeUserEmails; }

    /** Cache-busting suffix for the logo URL; {@code 0} when on the bundled default. */
    public long logoVersion() {
        return logoUpdatedAt == null ? 0L : logoUpdatedAt.toEpochMilli();
    }

    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public void setLogo(String path, String contentType) {
        this.logoPath = path;
        this.logoContentType = contentType;
        this.logoUpdatedAt = Instant.now();
        this.updatedAt = this.logoUpdatedAt;
    }

    public void clearLogo() {
        this.logoPath = null;
        this.logoContentType = null;
        this.logoUpdatedAt = Instant.now();
        this.updatedAt = this.logoUpdatedAt;
    }

    public void setExposeUserEmails(boolean expose) {
        this.exposeUserEmails = expose;
        this.updatedAt = Instant.now();
    }

    public void setChannelCreation(ChannelCreationPolicy policy) {
        this.channelCreation = policy == null ? ChannelCreationPolicy.EVERYONE : policy;
    }
}
