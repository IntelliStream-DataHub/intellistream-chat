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

package ai.intellistream.threadorbit.web;

import ai.intellistream.threadorbit.service.AppSettingsService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the admin-editable branding (title + logo URL) to every Thymeleaf template via
 * {@code ${appTitle}} and {@code ${appLogoUrl}} model attributes.
 *
 * <p>The logo URL points at {@code /branding/logo} (which redirects to the bundled default
 * when no custom upload exists), with a {@code ?v=...} cache-buster so the browser picks up
 * a fresh upload immediately.
 */
@ControllerAdvice(basePackages = "ai.intellistream.threadorbit.web")
public class BrandingModelAdvice {

    private final AppSettingsService settings;

    public BrandingModelAdvice(AppSettingsService settings) {
        this.settings = settings;
    }

    @ModelAttribute("appTitle")
    public String title() {
        return settings.current().getTitle();
    }

    @ModelAttribute("appLogoUrl")
    public String logoUrl() {
        var s = settings.current();
        return s.hasCustomLogo()
                ? "/branding/logo?v=" + s.logoVersion()
                : "/img/logo.svg";
    }

    @ModelAttribute("appHasCustomLogo")
    public boolean hasCustomLogo() {
        // Templates inline the bundled default mark (theme-aware SVG, fragments/logo.html)
        // and only fall back to an <img> when an admin has uploaded a custom logo.
        return settings.current().hasCustomLogo();
    }

    @ModelAttribute("appFaviconUrl")
    public String faviconUrl() {
        var s = settings.current();
        // The bundled default logo is animated and detailed — tabs get a static,
        // 16px-optimised variant instead. A custom uploaded logo serves both roles.
        return s.hasCustomLogo()
                ? "/branding/logo?v=" + s.logoVersion()
                : "/img/favicon.svg";
    }
}
