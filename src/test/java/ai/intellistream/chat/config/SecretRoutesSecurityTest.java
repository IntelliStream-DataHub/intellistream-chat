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

import ai.intellistream.chat.moderation.SuspensionEnforcementFilter;
import ai.intellistream.chat.moderation.SuspensionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real {@link SecurityConfig} filter chains, driven through MockMvc, for the routes one-time
 * secrets added. The handlers are stubs that answer 200: what is under test is only whether a request
 * reaches its handler — signed out, without a CSRF token — and nothing else.
 *
 * <p>The two exemptions (signed-out access, no CSRF token) are the widest thing this feature did to
 * the security configuration, so the tests go both ways: the two key-holder calls get through, and
 * every neighbouring route — creating, listing, revoking, the sign-in hop, the admin switch — is still
 * refused exactly as before.
 */
@SpringJUnitWebConfig(SecretRoutesSecurityTest.Harness.class)
@TestPropertySource(properties = "spring.security.oauth2.client.provider.keycloak.issuer-uri=http://keycloak.test/realms/ichat")
class SecretRoutesSecurityTest {

    private static final String ID = "AbCdEfGhIjKlMnOpQrStUv";

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, StubRoutes.class})
    static class Harness {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("keycloak")
                    .clientId("ichat-client")
                    .clientSecret("test-secret")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri("http://keycloak.test/realms/ichat/protocol/openid-connect/auth")
                    .tokenUri("http://keycloak.test/realms/ichat/protocol/openid-connect/token")
                    .jwkSetUri("http://keycloak.test/realms/ichat/protocol/openid-connect/certs")
                    .userInfoUri("http://keycloak.test/realms/ichat/protocol/openid-connect/userinfo")
                    .userNameAttributeName("preferred_username")
                    .build());
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        SuspensionEnforcementFilter suspensionEnforcementFilter() {
            return new SuspensionEnforcementFilter(new SuspensionRegistry(mock(DataSource.class)));
        }
    }

    /** Stand-ins for the real controllers: reaching one is the whole assertion. */
    @RestController
    static class StubRoutes {
        @GetMapping("/s/{id}") String page(jakarta.servlet.http.HttpServletResponse response) {
            ai.intellistream.chat.web.SecretSharePageController.linkPageHeaders(response);
            return "page";
        }
        @GetMapping("/s/{id}/sign-in") String signIn() { return "sign-in"; }
        @PostMapping("/api/secrets/{id}/status") String status() { return "status"; }
        @PostMapping("/api/secrets/{id}/open") String open() { return "open"; }
        @PostMapping("/api/secrets") String create() { return "create"; }
        @GetMapping("/api/secrets") String list() { return "list"; }
        @DeleteMapping("/api/secrets/{id}") String revoke() { return "revoke"; }
        @PostMapping("/admin/public-secrets") String adminSwitch() { return "admin"; }
        @GetMapping("/secrets") String createPage() { return "secrets"; }
    }

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void mvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------- what is open ----

    @Test
    void theLinkPageIsReachableSignedOut() throws Exception {
        mvc.perform(get("/s/" + ID).accept("text/html")).andExpect(status().isOk())
                // The site-wide headers still apply to it.
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self';")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                // The page's own headers survive Spring Security's defaults rather than being doubled.
                .andExpect(header().stringValues("Cache-Control", "no-store"))
                .andExpect(header().stringValues("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
    }

    @Test
    void theTwoKeyHolderCallsAreReachableSignedOutAndWithoutACsrfToken() throws Exception {
        mvc.perform(post("/api/secrets/" + ID + "/status").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/secrets/" + ID + "/open").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // A signed-in session without a token too: someone arriving from an email has no CSRF cookie.
        mvc.perform(post("/api/secrets/" + ID + "/open").with(user("bob")).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- what is not ----

    @Test
    void theSignInHopRequiresAuthenticationSoTheLoginRoundTripComesBackToIt() throws Exception {
        mvc.perform(get("/s/" + ID + "/sign-in").accept("text/html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(SecurityConfig.LOGIN_URL));
        mvc.perform(get("/s/" + ID + "/sign-in").with(user("bob")).accept("text/html")).andExpect(status().isOk());
    }

    @Test
    void deeperPathsUnderTheLinkPageAreNotOpened() throws Exception {
        mvc.perform(get("/s/" + ID + "/anything").accept("text/html")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/s/" + ID)).andExpect(status().isForbidden());
    }

    @Test
    void creatingListingAndRevokingStillNeedASessionAndACsrfToken() throws Exception {
        mvc.perform(get("/api/secrets")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/secrets").accept("text/html")).andExpect(status().is3xxRedirection());

        // Signed out, with or without a token.
        mvc.perform(post("/api/secrets").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().is3xxRedirection());
        // Signed in without a token: CSRF refuses.
        mvc.perform(post("/api/secrets").with(user("alice")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/secrets/" + ID).with(user("alice"))).andExpect(status().isForbidden());
        // Signed in with a token: through.
        mvc.perform(post("/api/secrets").with(user("alice")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/secrets/" + ID).with(user("alice")).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/secrets").with(user("alice"))).andExpect(status().isOk());
    }

    @Test
    void theExemptionDoesNotLeakToOtherMethodsOnTheSamePaths() throws Exception {
        mvc.perform(get("/api/secrets/" + ID + "/open")).andExpect(status().is3xxRedirection());
        mvc.perform(delete("/api/secrets/" + ID + "/open")).andExpect(status().isForbidden());
    }

    @Test
    void theAdminSwitchNeedsTheAdminRoleAndACsrfToken() throws Exception {
        mvc.perform(post("/admin/public-secrets").with(user("alice")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/public-secrets").with(user("root").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/public-secrets").with(user("root").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
    }
}
