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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.AppSettings;
import ai.intellistream.chat.domain.ChannelCreationPolicy;
import ai.intellistream.chat.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads / writes the singleton {@link AppSettings} row holding admin-editable branding
 * (topbar title and uploaded logo metadata). The Flyway V12 migration seeds the row, so
 * {@link #current()} always returns a non-null value once the schema is in place.
 */
@Service
public class AppSettingsService {

    static final int MAX_TITLE_LEN = 120;

    private final AppSettingsRepository repo;

    public AppSettingsService(AppSettingsRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public AppSettings current() {
        return repo.findById(AppSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "app_settings singleton row missing — Flyway V12 not applied?"));
    }

    @Transactional
    public AppSettings updateTitle(String title) {
        var trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Title cannot be empty");
        }
        if (trimmed.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException("Title too long (max " + MAX_TITLE_LEN + " chars)");
        }
        var s = current();
        s.setTitle(trimmed);
        return s;
    }

    @Transactional
    public AppSettings setLogo(String filename, String contentType) {
        var s = current();
        s.setLogo(filename, contentType);
        return s;
    }

    @Transactional
    public AppSettings clearLogo() {
        var s = current();
        s.clearLogo();
        return s;
    }

    @Transactional
    public AppSettings setExposeUserEmails(boolean expose) {
        var s = current();
        s.setExposeUserEmails(expose);
        return s;
    }

    /**
     * Who may create channels. Read on every create, so it goes through the same cached settings
     * lookup the branding does rather than a query per attempt.
     */
    @Transactional(readOnly = true)
    public ChannelCreationPolicy channelCreationPolicy() {
        return current().getChannelCreation();
    }

    /** Admin action. Null or unrecognised input falls back to the permissive default. */
    @Transactional
    public ChannelCreationPolicy setChannelCreationPolicy(ChannelCreationPolicy policy) {
        var settings = current();
        settings.setChannelCreation(policy);
        return repo.save(settings).getChannelCreation();
    }
}
