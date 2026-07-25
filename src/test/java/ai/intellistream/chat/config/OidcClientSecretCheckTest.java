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

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the failure mode that took a working deployment down without a single log line: the app
 * booted with an empty {@code KEYCLOAK_CLIENT_SECRET}, answered {@code /actuator/health} with 200,
 * and then bounced every login to {@code /login?error} from the token exchange.
 */
class OidcClientSecretCheckTest {

    private static ClientRegistration.Builder keycloak() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId("intellistream-chat")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://kc/auth")
                .tokenUri("http://kc/token")
                .userInfoUri("http://kc/userinfo")
                .userNameAttributeName("preferred_username")
                .jwkSetUri("http://kc/certs");
    }

    @Test
    void rejectsAConfidentialClientWithNoSecret() {
        var registration = keycloak()
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("")
                .build();

        assertThatThrownBy(() -> OidcClientSecretCheck.verify(registration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client secret is empty")
                // The message has to name the fix, because the runtime symptom points everywhere
                // except at the actual cause.
                .hasMessageContaining("KEYCLOAK_CLIENT_SECRET");
    }

    @Test
    void rejectsABlankSecretNotJustAnEmptyOne() {
        // An env var set to whitespace is the same failure with a more confusing cause.
        var registration = keycloak()
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("   ")
                .build();

        assertThatThrownBy(() -> OidcClientSecretCheck.verify(registration))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void namesTheSymptomSoTheErrorIsSearchable() {
        var registration = keycloak()
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("")
                .build();

        assertThatThrownBy(() -> OidcClientSecretCheck.verify(registration))
                .hasMessageContaining("/login?error");
    }

    @Test
    void acceptsAConfidentialClientWithASecret() {
        var registration = keycloak()
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("a-real-secret")
                .build();

        assertThatCode(() -> OidcClientSecretCheck.verify(registration)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAPublicClient() {
        // A public client legitimately has no secret; the check must not block that deployment.
        var registration = keycloak()
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .build();

        assertThatCode(() -> OidcClientSecretCheck.verify(registration)).doesNotThrowAnyException();
    }

    @Test
    void toleratesAnAbsentRegistration() {
        assertThatCode(() -> OidcClientSecretCheck.verify(null)).doesNotThrowAnyException();
    }

    @Test
    void theBundledDevRealmActuallyCarriesASecret() throws Exception {
        // The quick-start tells operators to export this value out of realm.json. If an edit ever
        // empties it, every dev login breaks in the way described above — catch that here rather
        // than leaving it for a person to discover by failing to sign in.
        var realm = java.nio.file.Path.of("keycloak/realm.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(realm),
                "realm.json not present in this working directory");
        var json = java.nio.file.Files.readString(realm);

        // Brace-matched extraction rather than a regex: the client object contains nested objects
        // (attributes, protocol mappers), which a [^{}]* pattern cannot span.
        var marker = java.util.regex.Pattern.compile("\"clientId\"\\s*:\\s*\"intellistream-chat\"").matcher(json);
        assertThat(marker.find()).describedAs("intellistream-chat client in realm.json").isTrue();
        int start = json.lastIndexOf('{', marker.start());
        int depth = 0, end = start;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}' && --depth == 0) { end = i + 1; break; }
        }
        var clientJson = json.substring(start, end);

        var secretMatch = java.util.regex.Pattern.compile("\"secret\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(clientJson);
        assertThat(secretMatch.find()).describedAs("secret field on the intellistream-chat client").isTrue();

        var secret = secretMatch.group(1);
        assertThat(secret).describedAs("dev realm client secret").isNotBlank();
        assertThatCode(() -> OidcClientSecretCheck.verify(keycloak()
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret(secret)
                .build())).doesNotThrowAnyException();
    }
}
