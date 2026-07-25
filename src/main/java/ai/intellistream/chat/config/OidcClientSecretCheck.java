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

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * Refuses to start when a confidential OAuth2 client has no secret.
 *
 * <p>{@code application.yml} deliberately gives {@code KEYCLOAK_CLIENT_SECRET} an empty default, so
 * that a deployment which forgets it fails rather than silently falling back to a secret baked into
 * the repository. But an empty string is a perfectly valid property value: the context started, the
 * app looked healthy, {@code /actuator/health} returned 200 — and then every single login died at
 * the token exchange, redirecting to {@code /login?error} with nothing in the application log.
 * "Fails fast" was the documented intent; this is what actually makes it true.
 *
 * <p>The check is deliberately not a warning. A server that cannot authenticate anybody is not
 * usefully running, and the failure it produces otherwise is exceptionally hard to read from the
 * outside — it looks like a Keycloak problem, a cookie problem, or a redirect-URI problem, none of
 * which it is.
 */
public final class OidcClientSecretCheck {

    private OidcClientSecretCheck() {}

    /**
     * @throws IllegalStateException when {@code registration} authenticates with a secret it hasn't
     *         been given. Public clients and clients using other authentication methods pass.
     */
    public static void verify(ClientRegistration registration) {
        if (registration == null) {
            return;
        }
        var method = registration.getClientAuthenticationMethod();
        boolean needsSecret = ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(method)
                || ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(method)
                || ClientAuthenticationMethod.CLIENT_SECRET_JWT.equals(method);
        if (!needsSecret) {
            return;
        }
        var secret = registration.getClientSecret();
        if (secret != null && !secret.isBlank()) {
            return;
        }
        throw new IllegalStateException("""
                OAuth2 client '%s' authenticates with %s but its client secret is empty, so every \
                login would fail at the token exchange and redirect to /login?error.

                Set KEYCLOAK_CLIENT_SECRET before starting. For the bundled dev realm:
                  export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[]|select(.clientId=="intellistream-chat").secret' keycloak/realm.json)
                """.formatted(registration.getRegistrationId(), method.getValue()));
    }
}
