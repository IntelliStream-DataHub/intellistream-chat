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

package ai.intellistream.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code GET /login} → the Keycloak round-trip.
 *
 * <p>The app has no login page of its own — Keycloak is the login page — so nothing here ever
 * linked to {@code /login}. Plenty outside it does: it is Spring Security's default login path, so
 * it is what a Keycloak client's <em>Home URL</em> is set to by anyone following a Spring tutorial,
 * and Keycloak builds its "Back to Application" link from that URL after an email verification, a
 * password reset, or any required action completed in a browser that no longer holds the original
 * login session. Until this existed, that link landed on Spring's auto-generated "Please sign in /
 * Login with OAuth 2.0" page, unstyled and one more click from the app.
 *
 * <p>A redirect rather than a page, and to the authorization endpoint rather than to
 * {@code /channels}, because it then does the right thing in both states: a signed-out browser
 * gets Keycloak's login form (or, with a live SSO session — the common case straight after
 * verifying an email — no form at all), and a signed-in one round-trips silently. Either way the
 * success handler lands on {@code /channels}. The path is {@code permitAll} in
 * {@link SecurityConfig} so the redirect itself never starts a second round-trip.
 */
@Configuration
public class LoginRedirectConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/login", SecurityConfig.LOGIN_URL);
    }
}
