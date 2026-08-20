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

import ai.intellistream.chat.config.StaticAssetCacheConfig.StaticAssetCacheFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule is "does this URL carry its own version", and getting it backwards is expensive in one
 * direction and invisible in the other.
 *
 * <p>Marking an unversioned URL {@code immutable} is what the reverse-proxy config used to do to
 * the whole of {@code /js/}: browsers then served a stale {@code chat/index.js} against a new
 * server for up to thirty days, with nothing to indicate it. Marking a versioned one revalidate-me
 * costs a pointless conditional GET per bundle per page and nothing else, which is why the test for
 * that direction matters less — but both are pinned, because the interesting cases are the URLs
 * that look like one and are the other.
 */
class StaticAssetCacheConfigTest {

    private String cacheHeaderFor(String uri, String queryString) throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        request.setQueryString(queryString);
        var response = new MockHttpServletResponse();
        new StaticAssetCacheFilter().doFilter(request, response, new MockFilterChain());
        return response.getHeader("Cache-Control");
    }

    @Test
    void aContentVersionedBundleIsImmutable() throws Exception {
        // What AssetService actually emits.
        assertThat(cacheHeaderFor("/js/chat.bundle.min.js", "v=26d4196aa3"))
                .isEqualTo(StaticAssetCacheConfig.VERSIONED);
        assertThat(cacheHeaderFor("/css/app.bundle.min.css", "v=eb18510694"))
                .isEqualTo(StaticAssetCacheConfig.VERSIONED);
    }

    @Test
    void theEsModuleGraphIsRevalidated() throws Exception {
        // The 280 KB the old directory-wide rule was quietly freezing for a month. These are plain
        // <script type="module" src="..."> tags at a fixed path: the URL is identical before and
        // after a deploy, so "do not revalidate" means "do not pick up the deploy".
        assertThat(cacheHeaderFor("/js/chat/index.js", null))
                .isEqualTo(StaticAssetCacheConfig.UNVERSIONED);
        assertThat(cacheHeaderFor("/js/vendor/stomp.umd.min.js", null))
                .isEqualTo(StaticAssetCacheConfig.UNVERSIONED);
        assertThat(cacheHeaderFor("/img/favicon-alert.svg", null))
                .isEqualTo(StaticAssetCacheConfig.UNVERSIONED);
    }

    @Test
    void unbundledDevelopmentSourcesAreRevalidatedToo() throws Exception {
        // ichat.assets.unbundled=true serves the original files at unversioned paths, and the whole
        // point of that mode is that editing one and refreshing shows the change.
        assertThat(cacheHeaderFor("/js/time-format.js", null))
                .isEqualTo(StaticAssetCacheConfig.UNVERSIONED);
    }

    @Test
    void aVersionParameterMustActuallyCarryAValue() {
        assertThat(StaticAssetCacheFilter.hasVersionParam("v=abc123")).isTrue();
        assertThat(StaticAssetCacheFilter.hasVersionParam("foo=1&v=abc123")).isTrue();
        assertThat(StaticAssetCacheFilter.hasVersionParam("v=abc123&foo=1")).isTrue();

        assertThat(StaticAssetCacheFilter.hasVersionParam(null)).isFalse();
        assertThat(StaticAssetCacheFilter.hasVersionParam("")).isFalse();
        // A bare or empty `v` is not a version, and treating it as one would let anybody freeze an
        // asset in their own cache for a year by adding four characters to the URL.
        assertThat(StaticAssetCacheFilter.hasVersionParam("v")).isFalse();
        assertThat(StaticAssetCacheFilter.hasVersionParam("v=")).isFalse();
        // Nor is a parameter that merely ends in v.
        assertThat(StaticAssetCacheFilter.hasVersionParam("rev=abc")).isFalse();
        assertThat(StaticAssetCacheFilter.hasVersionParam("srv=1&x=2")).isFalse();
    }

    @Test
    void theFilterAlwaysPassesTheRequestOn() throws Exception {
        var request = new MockHttpServletRequest("GET", "/js/chat/index.js");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new StaticAssetCacheFilter().doFilter(request, response, chain);

        // A cache header is not worth swallowing an asset over.
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
