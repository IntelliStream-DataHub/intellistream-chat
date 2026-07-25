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
package ai.intellistream.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Resolves the URLs to emit for a declared JS/CSS asset bundle (used by the
 * {@code fragments/assets :: js(bundle)} / {@code css(bundle)} Thymeleaf fragments).
 *
 * <p>Bundles are declared <b>once</b> in {@code assets.gradle}, which also parses the manifests
 * and emits {@code asset-bundles.properties} — the single source of truth this class reads. The
 * Java side neither re-declares bundles nor parses manifests. See ASSETS.md.
 *
 * <ul>
 *   <li><b>Production</b> (default): the single minified bundle URL, content-versioned with
 *       {@code ?v=<hash>} so browsers can cache it aggressively yet never serve a stale copy
 *       after a deploy.</li>
 *   <li><b>Development</b> ({@code intellistream.assets.unbundled=true}): the original source files in
 *       order, so editing one and refreshing shows the change immediately (no rebuild).</li>
 * </ul>
 */
@Service
public class AssetService {

    private static final String REGISTRY = "asset-bundles.properties";

    private final boolean unbundled;
    private final Properties registry;

    public AssetService(@Value("${intellistream.assets.unbundled:false}") boolean unbundled) {
        this.unbundled = unbundled;
        this.registry = loadRegistry();
    }

    /** Ordered web paths of the script/stylesheet tags to emit for the given bundle. */
    public List<String> urls(String bundleName) {
        String bundleUrl = registry.getProperty(bundleName + ".bundle");
        if (bundleUrl == null) {
            throw new IllegalArgumentException("Unknown asset bundle: " + bundleName);
        }
        if (!unbundled) {
            return List.of(bundleUrl);
        }
        String sources = registry.getProperty(bundleName + ".sources", "");
        return sources.isBlank() ? List.of() : Arrays.asList(sources.split(","));
    }

    private static Properties loadRegistry() {
        Properties props = new Properties();
        ClassPathResource resource = new ClassPathResource(REGISTRY);
        try (InputStream in = resource.getInputStream()) {
            props.load(in);
        } catch (Exception e) {
            throw new IllegalStateException(
                    REGISTRY + " not found on the classpath — build the assets first "
                    + "(./gradlew buildAssets).", e);
        }
        return props;
    }
}
