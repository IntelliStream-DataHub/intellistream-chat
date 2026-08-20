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
import ai.intellistream.chat.security.KeycloakRolesConverter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Configuration
public class SecurityConfig {

    /** Where a browser lands after signing in with nothing else to go back to. */
    static final String DEFAULT_LANDING_PAGE = "/channels";

    /*
     * Cookie {@code Secure} flag is auto-detected per request: both the JSESSIONID and
     * the CSRF cookie inherit {@code request.isSecure()}, which is set to {@code true}
     * whenever the inbound request carried {@code X-Forwarded-Proto: https}. This is wired by
     * {@code forward-headers-strategy: framework} in application.yml, which registers Spring's
     * {@code ForwardedHeaderFilter} (NOT Tomcat's RemoteIpValve — that's the {@code native}
     * strategy). On plain-HTTP local dev the cookies are written without Secure so they
     * round-trip; behind a TLS-terminating proxy they're marked Secure automatically.
     *
     * SECURITY NOTE: {@code ForwardedHeaderFilter} trusts {@code X-Forwarded-*} from ANY
     * upstream — it has no internal-proxy allowlist (unlike {@code native} + RemoteIpValve's
     * {@code internal-proxies}). This is safe only because the app binds to loopback
     * ({@code server.address=127.0.0.1} by default) behind the TLS proxy, so no untrusted client
     * can reach it directly to spoof those headers. If you ever widen the bind address, switch to
     * {@code forward-headers-strategy: native} and set {@code server.tomcat.remoteip.internal-proxies}
     * to your proxy's CIDR — otherwise a spoofed X-Forwarded-Host could poison {baseUrl} expansion
     * (redirect/logout URIs) and the per-request cookie Secure decision.
     *
     * Tomcat session cookie: see {@code Request#configureSessionCookie}, which ORs
     * {@code SessionCookieConfig.isSecure()} with {@code request.isSecure()}. We don't set
     * the config knob, so the OR collapses to per-request.
     *
     * CSRF cookie: see {@link CookieCsrfTokenRepository#saveToken} — when the {@code secure}
     * field is null (we don't set it), the cookie's Secure flag is set from
     * {@code request.isSecure()}.
     */

    /**
     * API + WebSocket handshake for programmatic clients carrying a bearer JWT issued by Keycloak.
     * Browser sessions (no {@code Authorization} header) fall through to {@link #webFilterChain}
     * and authenticate via the OIDC session cookie — {@code CurrentUser.resolve} accepts both.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                              SuspensionEnforcementFilter suspensionFilter) throws Exception {
        http
                .securityMatcher(SecurityConfig::isBearerApiOrWs)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRolesConverter())))
                // After AuthorizationFilter, i.e. last: the authentication is resolved by then, and
                // nothing reaches a handler without passing it. Both chains get it — a suspended
                // account must be refused whether it arrives with a bearer token or a session
                // cookie, and the /ws handshake lands here too, so a client that reconnects after
                // being hung up on is turned away at the handshake rather than at CONNECT.
                .addFilterAfter(suspensionFilter, AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Browser pages: redirect to Keycloak login.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http,
                                              ClientRegistrationRepository clientRegistrationRepository,
                                              SuspensionEnforcementFilter suspensionFilter,
                                              @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
                                              String keycloakIssuerUri) throws Exception {
        // Fail here rather than at the first login attempt. A blank client secret is a valid
        // property value, so without this the app starts, reports healthy, and then nobody can
        // sign in — see OidcClientSecretCheck.
        OidcClientSecretCheck.verify(clientRegistrationRepository.findByRegistrationId("keycloak"));

        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        var registrationResolver = new RegistrationAuthorizationRequestResolver(clientRegistrationRepository);

        var requestCache = loginRequestCache();

        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // SameSite=Strict blocks cross-site submission even if the cookie leaks. We
        // intentionally don't call .secure(...) — without it the repository falls back
        // to request.isSecure() per request (see class-level comment).
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict"));

        // Content-Security-Policy: ban inline script (pages must load chat.js / theme-loader.js
        // via <script src=…>); allow inline style because Thymeleaf templates use a few inline
        // style attributes; restrict connect-src to the same origin so WS/AJAX stays in-bounds.
        var csp = "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; "
                + "font-src 'self' data:; "
                // Same-origin only. `'self'` covers the same-origin wss://…/ws STOMP endpoint;
                // the earlier `ws: wss:` scheme wildcards allowed outbound sockets to any host
                // (an exfil channel) and contradicted this comment.
                + "connect-src 'self'; "
                // YouTube and Vimeo embeds (rendered when a user posts a video URL).
                + "frame-src https://www.youtube.com https://www.youtube-nocookie.com https://player.vimeo.com; "
                + "frame-ancestors 'none'; "
                + "base-uri 'self'; "
                // form-action must include the Keycloak origin: Chromium enforces this
                // directive against the REDIRECT that follows a form submission, and the
                // logout POST 302s to Keycloak's end-session endpoint (RP-initiated
                // logout). With 'self' alone, Chrome aborts that redirect and sign-out
                // silently does nothing. Firefox doesn't check redirects, which is why
                // this went unnoticed.
                + "form-action 'self' " + originOf(keycloakIssuerUri);

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/css/**", "/js/**", "/img/**", "/webjars/**",
                                         "/actuator/health", "/branding/logo").permitAll()
                        // The session probe has to be reachable *after* the session dies, or it
                        // cannot report that it did — an authenticated route answers an expired
                        // session with a 302 to Keycloak, which a background fetch follows and
                        // resolves as a 200 full of login-page HTML. See SessionRestController.
                        // It discloses nothing: the caller's own sign-in state and their own name.
                        .requestMatchers(HttpMethod.GET, "/api/session").permitAll()
                        // Admin console + branding mutations require the ichat-admin realm role
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
                // Remember where the browser was going before it got bounced to Keycloak, so a
                // permalink survives the login round-trip. See loginRequestCache() for what is
                // deliberately NOT remembered.
                .requestCache(rc -> rc.requestCache(requestCache))
                .oauth2Login(login -> login
                        .authorizationEndpoint(ep -> ep.authorizationRequestResolver(registrationResolver))
                        .userInfoEndpoint(uie -> uie.userAuthoritiesMapper(keycloakAuthoritiesMapper()))
                        .successHandler(loginSuccessHandler(requestCache)))
                // Single sign-out: hit Keycloak's end-session endpoint after clearing our session,
                // then bounce back to the landing page. Without this, the OIDC SSO session stays
                // alive and the next /oauth2/authorization/keycloak round-trip would silently
                // re-authenticate the user.
                .logout(lo -> lo.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)))
                // See the note on the API chain. The filter lets the login and logout endpoints
                // through, so a suspended user can still sign out of a session they can't use.
                .addFilterAfter(suspensionFilter, AuthorizationFilter.class);
        return http.build();
    }

    // ------------------------------------------------------------------ post-login landing ----
    //
    // The web chain used to end every login at {@code defaultSuccessUrl("/channels", true)}, and
    // the {@code true} is what made it unconditional: Spring's saved request was thrown away, so
    // opening a permalink while signed out put you on the welcome page and left you to find the
    // message yourself. The app ships a "Copy link to message" action and a {@code ?m=<id>} route
    // that renders context around an older message, which makes this a shipped feature defeating
    // another one — share a link with a colleague who isn't signed in and they land nowhere near
    // it. Slack and Mattermost both return you to the deep link.
    //
    // An unvalidated post-login redirect is an open-redirect vector, so none of this reads a URL
    // out of a request parameter. The target can only ever be a request that already reached this
    // application and was refused for want of authentication — Spring records it, we hand it back.

    /**
     * The saved-request store, with a matcher deciding what is worth coming back to.
     *
     * <p>Two of the exclusions are the security-relevant ones. Browser calls to {@code /api/**}
     * and the {@code /ws} handshake carry no {@code Authorization} header, so they do not match
     * {@link #isBearerApiOrWs} and fall through to <em>this</em> chain. Without the exclusion, a
     * background poll that happens to hit an expired session would save {@code /api/presence} as
     * the thing to resume — and the user's next interactive login would land on a page of JSON,
     * chosen by whichever XHR lost the race. Where a person ends up after signing in must be a
     * consequence of something they did, not of the app's own housekeeping.
     *
     * <p>The rest is ergonomics. Only GET, because a saved POST comes back as a redirect and a
     * redirect is a GET — the form would not be re-submitted, it would be silently dropped. And
     * only a request whose {@code Accept} asks for HTML, which is what separates a page the user
     * typed or clicked from the favicon fetch alongside it. {@code /favicon.ico} is not in the
     * permitAll list, so without that test it is authenticated like anything else, and the
     * classic version of this bug is landing on an icon after login.
     */
    public static RequestCache loginRequestCache() {
        var cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(SecurityConfig::isResumableRequest);
        // Spring appends a bare `continue` parameter to the saved URL as a marker, so that
        // RequestCacheAwareFilter can recognise the replayed request later and hand the handler
        // back the original request's parameters and headers. That machinery exists for resuming
        // a POST; we only ever resume a GET, which the browser re-sends in full by itself, and
        // the success handler consumes the saved request outright. All the marker would do here
        // is leave "?m=1234&continue" in the address bar of every permalink anyone shares next.
        cache.setMatchingRequestParameterName(null);
        return cache;
    }

    /** @see #loginRequestCache() */
    static boolean isResumableRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        var path = pathWithinApplication(request);
        if (path.startsWith("/api/") || path.equals("/api")) return false;
        if (path.equals("/ws") || path.startsWith("/ws/")) return false;
        // A missing Accept is allowed through: some minimal clients omit it entirely, and this
        // test is here to tell pages apart from sub-resources, not to police header hygiene.
        var accept = request.getHeader("Accept");
        return accept == null || accept.contains("text/html");
    }

    /**
     * Sends the browser to whatever it asked for before the login round-trip, or to
     * {@link #DEFAULT_LANDING_PAGE} when there was nothing worth resuming.
     */
    public static AuthenticationSuccessHandler loginSuccessHandler(RequestCache requestCache) {
        return new SavedRequestSuccessHandler(requestCache);
    }

    /** @see #loginSuccessHandler(RequestCache) */
    public static final class SavedRequestSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

        private final RequestCache requestCache;

        public SavedRequestSuccessHandler(RequestCache requestCache) {
            this.requestCache = requestCache;
            setDefaultTargetUrl(DEFAULT_LANDING_PAGE);
            // Both of these are already the framework defaults. They are set anyway because they
            // are precisely the two switches that would turn this class into an open redirect —
            // one honours a target named in a query parameter, the other honours the Referer —
            // and a default nobody wrote down is not a decision, it is a thing that happens to
            // be true until someone changes it.
            setTargetUrlParameter(null);
            setUseReferer(false);
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication)
                throws IOException, ServletException {
            var saved = requestCache.getRequest(request, response);
            // Consumed either way: a saved request that survives its own login is a stale
            // destination waiting to hijack the next one.
            requestCache.removeRequest(request, response);
            var target = saved == null ? null : sameOriginTarget(saved.getRedirectUrl());
            if (target == null) {
                super.onAuthenticationSuccess(request, response, authentication);
                return;
            }
            clearAuthenticationAttributes(request);
            getRedirectStrategy().sendRedirect(request, response, target);
        }
    }

    /**
     * Reduce a saved request's absolute URL to its path and query, dropping the scheme, host and
     * port. Returns {@code null} when there is nothing safe to redirect to.
     *
     * <p>Visible for testing. A {@code SavedRequest} hands back an absolute URL that Spring built
     * from the server's own view of the request — including {@code Host}, which
     * {@code ForwardedHeaderFilter} will happily take from {@code X-Forwarded-Host} (see the note
     * at the top of this class: it has no trusted-proxy allowlist and is safe only because the app
     * binds to loopback). Keeping the host would put the post-login landing inside the blast
     * radius of that footgun for no benefit — the destination is on this origin by construction,
     * so the redirect may as well be structurally incapable of leaving it.
     *
     * <p>A path that is missing, relative, or starts with a second slash is refused rather than
     * repaired: {@code //evil.example/x} is a protocol-relative URL, and a browser reads it as
     * another origin even though it looks like a path. Note that it has to be caught on the way
     * in as well as on the way out — {@code URI} parses a leading {@code //} as an authority, so
     * {@code //evil.example/x} comes back with a perfectly innocent-looking path of {@code /x}.
     */
    public static String sameOriginTarget(String savedRedirectUrl) {
        if (savedRedirectUrl == null || savedRedirectUrl.startsWith("//")) return null;
        String path;
        String query;
        try {
            var uri = URI.create(savedRedirectUrl);
            path = uri.getRawPath();
            query = uri.getRawQuery();
        } catch (IllegalArgumentException notAUri) {
            return null;
        }
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return null;
        if (path.length() > 1 && (path.charAt(1) == '/' || path.charAt(1) == '\\')) return null;
        return query == null ? path : path + "?" + query;
    }

    /**
     * Keep Boot from also mapping {@link SuspensionEnforcementFilter} onto the plain servlet chain.
     *
     * <p>It is a {@code Filter} bean, so servlet auto-registration would pick it up and run a copy
     * outside Spring Security, where the {@code SecurityContext} is empty and it can only skip.
     * Being a {@code OncePerRequestFilter}, that skip marks the request as already filtered and the
     * copy inside the security chain — the one with an authentication to inspect — never runs. The
     * enforcement would then be silently absent while all the wiring still looked right.
     */
    @Bean
    public FilterRegistrationBean<SuspensionEnforcementFilter> suspensionFilterServletRegistration(
            SuspensionEnforcementFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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

    /** Scheme://host[:port] of a URI — the CSP source form of the Keycloak issuer. */
    private static String originOf(String uri) {
        var u = java.net.URI.create(uri);
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

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
     * iff the {@code ichat-admin} role is present. Without this, browser sessions would
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

    /** Request path with any deployment context path stripped, so the tests below are absolute. */
    private static String pathWithinApplication(HttpServletRequest request) {
        var path = request.getRequestURI();
        var ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        return path;
    }

    private static boolean isBearerApiOrWs(HttpServletRequest request) {
        var path = pathWithinApplication(request);
        // "/ws" exactly, not just "/ws/": the STOMP endpoint is registered at "/ws" with no
        // trailing slash, so a startsWith("/ws/") test never matched the handshake itself. A
        // bearer-authenticated client then fell through to the browser chain and was answered
        // with a 302 to the login page, which looks like a broken token rather than a routing
        // bug. Browsers never hit it because they authenticate with the session cookie.
        boolean isApi = path.startsWith("/api/") || path.equals("/api");
        boolean isWs = path.equals("/ws") || path.startsWith("/ws/");
        if (!(isApi || isWs)) return false;
        var auth = request.getHeader("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7);
    }
}
