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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * How long a browser may keep a static asset, decided per URL rather than per directory.
 *
 * <p>Only some of what this application serves under {@code /js/} is content-versioned. The nine
 * declared bundles are — {@code AssetService} emits {@code /js/chat.bundle.min.js?v=26d4196aa3},
 * and a changed bundle gets a new hash and therefore a new URL. The {@code js/chat/} ES-module
 * graph is not, and neither is {@code js/vendor/}: those are ordinary
 * {@code <script type="module" src="/js/chat/index.js">} tags at a fixed path, some 420 KB of
 * JavaScript whose URL is identical before and after a deploy.
 *
 * <p>That distinction was invisible to the recommended reverse-proxy config, which matched on the
 * directory and told browsers {@code max-age=2592000, immutable} for everything under it.
 * {@code immutable} means "do not revalidate, ever", so a deploy that changed {@code chat/index.js}
 * kept serving the old copy against the new server for up to thirty days — with no error anywhere,
 * because from the browser's point of view it was doing exactly as it was told. The application is
 * the only party that knows which of its own URLs carry a hash, so it is the party that should say.
 *
 * <p>Two answers, and the query string is the whole test:
 *
 * <ul>
 *   <li><b>{@code ?v=<hash>} present</b> — a year, {@code immutable}. The URL <em>is</em> the
 *       version, so there is nothing to revalidate and never will be.</li>
 *   <li><b>No version</b> — a minute, then revalidate. Long enough that a burst of navigation
 *       inside one session does not re-fetch the module graph on every page, short enough that a
 *       deploy is picked up while somebody is still looking at it. The revalidation itself is
 *       cheap: Spring's resource handler answers a conditional GET with a bodiless 304.</li>
 * </ul>
 *
 * <p>Set before the chain runs rather than after, because a header cannot be added to a committed
 * response. Nothing downstream overwrites it: Boot leaves {@code spring.web.resources.cache}
 * unset, so {@code ResourceHttpRequestHandler} has no {@code Cache-Control} of its own to apply.
 */
@Configuration
public class StaticAssetCacheConfig {

    /** A year, in seconds. The conventional ceiling — anything longer is refused by some caches. */
    static final String VERSIONED = "public, max-age=31536000, immutable";

    /**
     * A minute, then ask. {@code must-revalidate} forbids a cache from serving this once stale,
     * which is the point: the whole failure being fixed here is a stale copy served confidently.
     */
    static final String UNVERSIONED = "public, max-age=60, must-revalidate";

    /**
     * The directories the asset pipeline and the templates serve from. {@code /branding/} is
     * deliberately absent — the custom logo is versioned but served by a controller that sets its
     * own headers, and a filter reaching into it would be a second opinion on the same question.
     */
    private static final String[] ASSET_PATHS = {"/js/*", "/css/*", "/img/*", "/fonts/*"};

    @Bean
    public FilterRegistrationBean<StaticAssetCacheFilter> staticAssetCacheFilter() {
        var registration = new FilterRegistrationBean<>(new StaticAssetCacheFilter());
        registration.addUrlPatterns(ASSET_PATHS);
        // Ahead of everything: these paths are permitAll and never touch a session, so there is no
        // reason to run security machinery before deciding a cache header.
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Visible for tests. */
    static final class StaticAssetCacheFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            response.setHeader(HttpHeaders.CACHE_CONTROL,
                    hasVersionParam(request.getQueryString()) ? VERSIONED : UNVERSIONED);
            chain.doFilter(request, response);
        }

        /**
         * Is there a {@code v} parameter with a value?
         *
         * <p>Read off the raw query string rather than through {@code getParameter}, which on a
         * POST parses the request body and consumes the stream. These are GETs today; a filter
         * that quietly breaks the first upload to land on one of these paths is not worth the
         * three lines it saves.
         */
        static boolean hasVersionParam(String queryString) {
            if (queryString == null || queryString.isEmpty()) return false;
            for (var pair : queryString.split("&")) {
                var eq = pair.indexOf('=');
                if (eq > 0 && "v".equals(pair.substring(0, eq)) && eq + 1 < pair.length()) {
                    return true;
                }
            }
            return false;
        }
    }
}
