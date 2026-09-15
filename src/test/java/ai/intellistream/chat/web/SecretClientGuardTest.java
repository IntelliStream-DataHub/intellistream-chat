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

package ai.intellistream.chat.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard for the client half of one-time secrets. The project runs no JavaScript in tests
 * (see AGENTS.md), so like {@link MessageBodyRenderGuardTest} this reads the sources as text. Each
 * assertion is a rule whose breaking would be silent: nothing throws when a second crypto
 * implementation appears, when the sign-in hop starts carrying the key, or when a page that handles
 * plaintext secrets starts loading scripts that act on a session.
 */
class SecretClientGuardTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Code without comment lines, so prose explaining a rule does not match the rule's own scan. */
    private static String codeOnly(String src) {
        return src.lines()
                .filter(line -> !line.stripLeading().startsWith("//"))
                .filter(line -> !line.stripLeading().startsWith("*"))
                .filter(line -> !line.stripLeading().startsWith("/*"))
                .collect(Collectors.joining("\n"));
    }

    private static List<Path> ownScripts() throws IOException {
        try (Stream<Path> files = Files.walk(JS)) {
            return files.filter(p -> p.toString().endsWith(".js"))
                    .filter(p -> !p.toString().contains("/vendor/"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .filter(p -> !p.getFileName().toString().endsWith(".min.js"))
                    .toList();
        }
    }

    @Test
    void secretCryptoIsTheOnlyFileThatTouchesWebCryptoOrDrawsRandomBytes() throws IOException {
        // crypto.randomUUID (client message ids) is not key material and is not what this is about.
        var forbidden = Pattern.compile("crypto\\.subtle|getRandomValues");
        var offenders = ownScripts().stream()
                .filter(p -> !p.getFileName().toString().equals("secret-crypto.js"))
                .filter(p -> forbidden.matcher(codeOnly(read(p))).find())
                .map(Path::toString)
                .toList();
        assertThat(offenders).as("files using Web Crypto outside secret-crypto.js").isEmpty();

        var crypto = codeOnly(read(JS.resolve("secret-crypto.js")));
        assertThat(crypto).contains("getRandomValues").contains("AES-GCM").contains("HKDF")
                .doesNotContain("Math.random");
        // Two distinct derivations: the value the server sees must not be the decryption key.
        assertThat(crypto).contains("'ichat-secret-v1 enc'").contains("'ichat-secret-v1 verify'");
    }

    @Test
    void theSecretPagesBuildTheirDomWithoutInnerHtml() {
        for (var name : List.of("secrets.js", "secret-view.js", "secret-crypto.js")) {
            var code = codeOnly(read(JS.resolve(name)));
            assertThat(code).as(name).doesNotContain("innerHTML").doesNotContain("outerHTML")
                    .doesNotContain("insertAdjacentHTML").doesNotContain("setHTML").doesNotContain("document.write");
        }
    }

    @Test
    void theSignInHopNeverCarriesTheKeyAndTheStashIsRemovedWhenRead() {
        var view = codeOnly(read(JS.resolve("secret-view.js")));

        var assign = Pattern.compile("location\\.(assign|replace|href\\s*=)\\s*\\(?([^;\\n]*)").matcher(view);
        var navigations = 0;
        while (assign.find()) {
            navigations++;
            assertThat(assign.group(2)).as("navigation target").contains("/sign-in")
                    .doesNotContain("hash").doesNotContain("keyText").doesNotContain("#");
        }
        assertThat(navigations).as("the page navigates somewhere to sign in").isPositive();

        assertThat(view).contains("sessionStorage.removeItem(STASH_KEY)");
        // Read and remove happen together, before anything else uses the value.
        var read = view.indexOf("sessionStorage.getItem(STASH_KEY)");
        var remove = view.indexOf("sessionStorage.removeItem(STASH_KEY)");
        assertThat(read).isPositive();
        assertThat(remove).isGreaterThan(read);
        assertThat(view.substring(read, remove).lines().count()).isLessThanOrEqualTo(2);
    }

    @Test
    void theRevealedSecretIsWipedOnADeadlineWhenHiddenAndOnPagehide() {
        var view = codeOnly(read(JS.resolve("secret-view.js")));
        assertThat(view).contains("addEventListener('pagehide'")
                .contains("addEventListener('visibilitychange'")
                .contains("deadline - Date.now()")
                // The key leaves the address bar once the secret is revealed.
                .contains("history.replaceState(null, '', location.pathname)");
        // Every secret call opts out of caches.
        assertThat(view).contains("cache: 'no-store'");
        assertThat(codeOnly(read(JS.resolve("secrets.js")))).contains("cache: 'no-store'");
    }

    @Test
    void theSecretViewBundleLoadsNothingThatActsOnASession() {
        var manifest = read(JS.resolve("secret-view.manifest.js"));
        var required = manifest.lines()
                .filter(l -> l.startsWith("//= require"))
                .map(l -> l.substring("//= require".length()).trim())
                .toList();
        assertThat(required).containsExactly("time-format.js", "secret-crypto.js", "secret-view.js");
        assertThat(read(JS.resolve("secrets.manifest.js"))).contains("//= require secret-crypto.js");
    }

    @Test
    void secretTextFieldsStayOutOfFormRestoreAndCloudSpellcheck() {
        var create = read(TEMPLATES.resolve("secrets.html"));
        var textarea = Pattern.compile("<textarea[^>]*id=\"secret-text\"[^>]*>", Pattern.DOTALL).matcher(create);
        assertThat(textarea.find()).isTrue();
        assertThat(textarea.group()).contains("autocomplete=\"off\"").contains("spellcheck=\"false\"")
                .contains("data-gramm=\"false\"");

        var view = read(TEMPLATES.resolve("secret-view.html"));
        var key = Pattern.compile("<input[^>]*id=\"sv-key\"[^>]*>", Pattern.DOTALL).matcher(view);
        assertThat(key.find()).isTrue();
        assertThat(key.group()).contains("autocomplete=\"off\"").contains("spellcheck=\"false\"");
        // The secret is shown as text in a <pre>, not in a form field a browser would restore.
        assertThat(view).contains("<pre class=\"secret-text\" id=\"sv-secret\"");
    }

    @Test
    void timeFormatDoesNotReportAZoneFromAPageWithoutACsrfToken() {
        var code = codeOnly(read(JS.resolve("time-format.js")));
        var report = code.indexOf("const report = () => {");
        assertThat(report).isPositive();
        assertThat(code.substring(report, report + 200)).contains("if (!meta('_csrf')) return;");
    }
}
