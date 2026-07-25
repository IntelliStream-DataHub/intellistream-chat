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

package ai.intellistream.chat.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Sends the user to Keycloak's registration page instead of the login page when
 * the inbound request carries {@code ?action=register}. Keycloak exposes the
 * same flow at {@code /protocol/openid-connect/registrations}, identical to
 * {@code /auth} but landing on the sign-up form first.
 */
public class RegistrationAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String AUTH_PATH = "/protocol/openid-connect/auth";
    private static final String REGISTRATIONS_PATH = "/protocol/openid-connect/registrations";

    private final OAuth2AuthorizationRequestResolver delegate;

    public RegistrationAuthorizationRequestResolver(ClientRegistrationRepository repo) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return rewriteIfRegister(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return rewriteIfRegister(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest rewriteIfRegister(HttpServletRequest request, OAuth2AuthorizationRequest req) {
        if (req == null || !"register".equals(request.getParameter("action"))) {
            return req;
        }
        var rewritten = req.getAuthorizationUri().replace(AUTH_PATH, REGISTRATIONS_PATH);
        return OAuth2AuthorizationRequest.from(req).authorizationUri(rewritten).build();
    }
}
