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

package ai.intellistream.radiance.config;

import ai.intellistream.radiance.security.KeycloakRolesConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Configuration
public class SecurityConfig {

    /**
     * Whether to mark session and CSRF cookies {@code Secure}. Defaults to {@code false}
     * so local-dev over plain HTTP keeps working; set
     * {@code chat.security.cookie-secure=true} (or {@code CHAT_SECURITY_COOKIE_SECURE=true})
     * in any HTTPS-fronted deployment so the cookies can't be lifted over the wire.
     */
    private final boolean cookieSecure;

    public SecurityConfig(@Value("${radiance.security.cookie-secure:false}") boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    /**
     * API + WebSocket handshake for programmatic clients carrying a bearer JWT issued by Keycloak.
     * Browser sessions (no {@code Authorization} header) fall through to {@link #webFilterChain}
     * and authenticate via the OIDC session cookie — {@code CurrentUser.resolve} accepts both.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(SecurityConfig::isBearerApiOrWs)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRolesConverter())));
        return http.build();
    }

    /**
     * Browser pages: redirect to Keycloak login.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http,
                                              ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        var registrationResolver = new RegistrationAuthorizationRequestResolver(clientRegistrationRepository);

        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // SameSite=Strict blocks cross-site submission even if the cookie leaks. Secure
        // is gated on chat.security.cookie-secure so local-dev over plain HTTP still
        // receives the cookie; production over HTTPS must set it true.
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict").secure(cookieSecure));

        // Content-Security-Policy: ban inline script (pages must load chat.js / theme-loader.js
        // via <script src=…>); allow inline style because Thymeleaf templates use a few inline
        // style attributes; restrict connect-src to the same origin so WS/AJAX stays in-bounds.
        var csp = "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; "
                + "font-src 'self' data:; "
                + "connect-src 'self' ws: wss:; "
                // YouTube and Vimeo embeds (rendered when a user posts a video URL).
                + "frame-src https://www.youtube.com https://www.youtube-nocookie.com https://player.vimeo.com; "
                + "frame-ancestors 'none'; "
                + "base-uri 'self'; "
                + "form-action 'self'";

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/css/**", "/js/**", "/img/**", "/webjars/**",
                                         "/actuator/health", "/branding/logo").permitAll()
                        // Admin console + branding mutations require the chat-admin realm role
                        // (mapped to ROLE_ADMIN by KeycloakRolesConverter).
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(csrfHandler))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(csp))
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .oauth2Login(login -> login
                        .authorizationEndpoint(ep -> ep.authorizationRequestResolver(registrationResolver))
                        .userInfoEndpoint(uie -> uie.userAuthoritiesMapper(keycloakAuthoritiesMapper()))
                        .defaultSuccessUrl("/channels", true))
                // Single sign-out: hit Keycloak's end-session endpoint after clearing our session,
                // then bounce back to the landing page. Without this, the OIDC SSO session stays
                // alive and the next /oauth2/authorization/keycloak round-trip would silently
                // re-authenticate the user.
                .logout(lo -> lo.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));
        return http.build();
    }

    /**
     * Apply {@code SameSite=Strict} to the JSESSIONID cookie. Spring Security's CSRF cookie
     * is configured separately in {@link #webFilterChain}; this supplier covers the session
     * cookie that the servlet container itself issues.
     */
    @Bean
    public CookieSameSiteSupplier sessionCookieSameSite() {
        return CookieSameSiteSupplier.ofStrict().whenHasName("JSESSIONID");
    }

    // The JSESSIONID cookie's Secure flag is bound from application.yml's
    // server.servlet.session.cookie.secure → ${radiance.security.cookie-secure:false}.
    // Setting it programmatically is container-specific in Spring Boot 4; the property
    // route works regardless of whether Tomcat or another embedded server is on the path.

    private static LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository repo) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(repo);
        // Spring Security expands {baseUrl} to the live request scheme/host/port, so this
        // works whether you reach the app on localhost or the host's IP without further config.
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }

    /**
     * Mirrors {@link KeycloakRolesConverter} for the browser/oauth2Login path: extracts
     * {@code realm_access.roles} from the OIDC id-token claims and adds {@code ROLE_ADMIN}
     * iff the {@code chat-admin} role is present. Without this, browser sessions would
     * never carry {@code ROLE_ADMIN} (only the JWT/resource-server chain ran the converter).
     */
    private static GrantedAuthoritiesMapper keycloakAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>(authorities);
            for (var auth : authorities) {
                if (auth instanceof OidcUserAuthority oidc) {
                    Map<String, Object> realm = oidc.getIdToken().getClaimAsMap("realm_access");
                    if (realm != null && realm.get("roles") instanceof Collection<?> roles
                            && roles.contains(KeycloakRolesConverter.ADMIN_REALM_ROLE)) {
                        mapped.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    }
                }
            }
            return mapped;
        };
    }

    private static boolean isBearerApiOrWs(jakarta.servlet.http.HttpServletRequest request) {
        var path = request.getRequestURI();
        var ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        if (!(path.startsWith("/api/") || path.startsWith("/ws/"))) return false;
        var auth = request.getHeader("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7);
    }
}
