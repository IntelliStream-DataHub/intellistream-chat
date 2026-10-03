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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard for the one code path a live-rendered attachment takes to the DOM, and for the
 * video player that hangs off it.
 *
 * <p>The channel page and the DM page each carried an identical private attachment builder. A
 * video player added to one of them would have left the other offering a download — the same
 * shape of bug as the code highlighting {@link MessageBodyRenderGuardTest} pins, where four of
 * eight copies forgot a line. The builder now lives once, in {@code ChatKit.buildAttachmentEl} /
 * {@code buildAttachmentTray}, and server-rendered history is upgraded by the same function on
 * DOM-ready. Like its sibling this is a string scan, because the project runs no JS in tests.
 */
class AttachmentRenderGuardTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final String SEAM_FILE = "chat-kit.js";

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    private static String codeOnly(String src) {
        return src.lines()
                .filter(line -> !line.stripLeading().startsWith("//"))
                .filter(line -> !line.stripLeading().startsWith("*"))
                .collect(Collectors.joining("\n"));
    }

    private static List<Path> jsSources() throws IOException {
        try (Stream<Path> walk = Files.walk(JS)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".js"))
                    .filter(p -> !p.toString().contains("/vendor/"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .sorted()
                    .toList();
        }
    }

    private static String block(String src, String start) {
        int from = src.indexOf(start);
        assertThat(from).as("%s must exist", start).isGreaterThanOrEqualTo(0);
        return src.substring(from, src.indexOf("\n  };", from));
    }

    @Test
    void chatKitOwnsTheAttachmentBuilderAndExportsIt() throws Exception {
        var src = read(JS.resolve(SEAM_FILE));
        assertThat(src)
                .contains("const buildAttachmentEl = (a) =>")
                .contains("const buildAttachmentTray = (attachments) =>")
                .contains("    buildAttachmentEl,")
                .contains("    buildAttachmentTray,");
        // A video card is upgraded as it is built, so a live message plays exactly like history.
        assertThat(block(src, "const buildAttachmentEl"))
                .contains("link.dataset.videoType = a.videoType")
                .contains("upgradeVideoCard(link)");
    }

    @Test
    void noPageBuildsItsOwnAttachmentLinks() throws Exception {
        for (var file : jsSources()) {
            var rel = JS.relativize(file).toString();
            if (rel.equals(SEAM_FILE)) continue;
            var code = codeOnly(read(file));
            assertThat(code)
                    .as("%s must use ChatKit.buildAttachmentTray, not build attachment cards itself", rel)
                    .doesNotContain("'attachment-image'")
                    .doesNotContain("className = 'attachment'")
                    .doesNotContain("buildAttachmentLink");
        }
        for (var page : List.of("chat/index.js", "conversation.js")) {
            assertThat(read(JS.resolve(page))).as(page).contains("ChatKit.buildAttachmentTray(");
        }
    }

    @Test
    void serverRenderedVideoCardsAreUpgradedOnLoad() throws Exception {
        // The history Thymeleaf drew is upgraded here, once for every page; without it, a refresh
        // turns every player back into a download card.
        var src = read(JS.resolve(SEAM_FILE));
        assertThat(src).contains("upgradeVideoCards(document)");
        assertThat(block(src, "const upgradeVideoCards"))
                .contains("a.attachment[data-video-type]:not([data-video-state])");
    }

    @Test
    void aRebuiltTrayKeepsThePlayerSomeoneIsWatching() throws Exception {
        // Every update to a message (a reaction, an edit, a reply count) rebuilds its tray. A new
        // <video> each time rewinds whoever is watching to 0:00 the moment anyone reacts.
        var src = read(JS.resolve(SEAM_FILE));
        var build = block(src, "const buildAttachmentEl");
        int reuse = build.indexOf("takeDetachedPlayer(a.downloadUrl)");
        assertThat(reuse).as("the builder must offer a detached player back first").isGreaterThanOrEqualTo(0);
        assertThat(reuse)
                .as("…before it builds a fresh card")
                .isLessThan(build.indexOf("link.className = 'attachment'"));
        var upgrade = block(src, "const upgradeVideoCard = (card)");
        assertThat(upgrade).contains("rememberPlayer(href, wrap)");
        assertThat(upgrade.substring(upgrade.indexOf("addEventListener('error'")))
                .as("a player that failed must not be handed back to the next tray")
                .contains("forgetPlayer(href, wrap)");
    }

    @Test
    void thePlayerIsOfferedOnlyWhatTheBrowserSaysItCanPlayAndFallsBackOnError() throws Exception {
        var upgrade = block(read(JS.resolve(SEAM_FILE)), "const upgradeVideoCard = (card)");
        assertThat(upgrade)
                .as("canPlayType is the gate — '' means the card stays and no request is made")
                .contains("canPlayVideo(card.dataset.videoType)");
        int listener = upgrade.indexOf("addEventListener('error'");
        int src = upgrade.indexOf("video.src =");
        assertThat(listener).as("the error fallback must exist").isGreaterThanOrEqualTo(0);
        assertThat(src).as("the player must be given a source").isGreaterThanOrEqualTo(0);
        assertThat(listener)
                .as("the error listener must be attached before src is set, or a fast failure is missed")
                .isLessThan(src);
        assertThat(upgrade.substring(listener, src))
                .as("on error the card comes back, saying why")
                .contains("replaceWith(card)")
                .contains("markUnplayable(card)");
    }
}
