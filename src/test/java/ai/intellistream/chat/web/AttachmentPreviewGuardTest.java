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

import ai.intellistream.chat.attachments.PreviewableAttachments.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard for the in-page attachment viewer — the button on a chip that shows a markdown or
 * HTML file without downloading it.
 *
 * <p>Two things are pinned here, and the second one is the important one.
 *
 * <p><b>One chip builder.</b> The channel page and the DM page each used to carry their own copy
 * of the attachment tray, identical down to the comments, and templates/channels.html and
 * conversation.html a third and fourth in Thymeleaf. That is how the image lightbox came to exist
 * on one page and not the other for months — nobody diffs two copies that already agree. The
 * builder now lives in {@code ChatKit.buildAttachmentTray} with one Thymeleaf mirror.
 *
 * <p><b>An uploaded HTML file is shown in a sandbox, or not at all.</b> {@code /api/attachments/…
 * /html} returns the file's own markup, unsanitised — sanitising it would strip the {@code <style>}
 * that makes it worth previewing. The only thing that makes serving it defensible is where it
 * lands: the {@code srcdoc} of an iframe with a bare {@code sandbox} attribute, which is an opaque
 * origin with scripting off. Adding {@code allow-scripts} or {@code allow-same-origin}, or putting
 * that markup anywhere near {@code innerHTML}, turns a preview into stored XSS in this
 * application's own origin. The project runs no JS in tests (see AGENTS.md), so this scan is what
 * stands between that edit and a release.
 */
class AttachmentPreviewGuardTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** chat-kit.js implements the seam, so it is the one file allowed to build a chip or a frame. */
    private static final String SEAM_FILE = "chat-kit.js";

    /** The Thymeleaf mirror of the builder — one fragment, included by every page with a feed. */
    private static final Path FRAGMENT = TEMPLATES.resolve("fragments/attachments.html");

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    /**
     * Drop comment lines. The comments in these files spell out at length which sandbox tokens are
     * deliberately absent, so a scan for one has to look at code or it matches the prose warning
     * against it. Same trick as {@link MessageBodyRenderGuardTest}.
     */
    private static String codeOnly(String src) {
        return src.lines()
                .filter(line -> !line.stripLeading().startsWith("//"))
                .filter(line -> !line.stripLeading().startsWith("*"))
                .collect(java.util.stream.Collectors.joining("\n"));
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

    @Test
    void chatKitOwnsTheOnlyAttachmentChipBuilder() throws Exception {
        var src = read(JS.resolve(SEAM_FILE));
        assertThat(src)
                .as("the builder must exist and be exported")
                .contains("const buildAttachmentEl = (a)")
                .contains("const buildAttachmentTray = (attachments)")
                .contains("    buildAttachmentEl,")
                .contains("    buildAttachmentTray,");

        // `className = 'attachment'` / `'attachment-image'` is the shape of the old copies.
        for (Path p : jsSources()) {
            if (p.getFileName().toString().equals(SEAM_FILE)) continue;
            assertThat(read(p))
                    .as("%s builds attachment chips itself — use ChatKit.buildAttachmentTray, or "
                            + "the preview button will exist on one page and not the other", p)
                    .doesNotContain("className = 'attachment'")
                    .doesNotContain("className = 'attachment-image'");
        }
    }

    @Test
    void bothFeedsRenderTheirTrayThroughTheSeam() throws Exception {
        for (String script : List.of("chat/index.js", "conversation.js")) {
            assertThat(read(JS.resolve(script)))
                    .as("%s must build its attachment tray through ChatKit", script)
                    .contains("window.ChatKit.buildAttachmentTray(attachments)")
                    // The click delegate is what makes both the image links and the preview
                    // buttons do anything at all — including on the rows Thymeleaf rendered,
                    // which no page script ever touches.
                    .as("%s must wire the attachment viewer", script)
                    .contains("window.ChatKit.wireAttachmentViewer()");
        }
    }

    @Test
    void serverRenderedMessagesUseTheOneFragment() throws Exception {
        assertThat(Files.exists(FRAGMENT)).as("the shared attachments fragment must exist").isTrue();
        for (String page : List.of("channels.html", "conversation.html")) {
            var html = read(TEMPLATES.resolve(page));
            assertThat(html)
                    .as("%s must include the shared attachments fragment", page)
                    .contains("~{fragments/attachments :: tray(${msg.attachments})}");
            assertThat(html)
                    .as("%s has grown its own attachment tray again — there is one fragment", page)
                    .doesNotContain("class=\"message-attachments\"");
        }
    }

    @Test
    void thePreviewButtonIsDrawnFromTheServersAnswer() throws Exception {
        // previewUrl / previewKind are the server's decision (PreviewableAttachments). A client
        // that sniffed filenames itself would offer previews the endpoints then refuse.
        var kit = read(JS.resolve(SEAM_FILE));
        assertThat(kit)
                .as("the chip's preview button must be gated on the DTO's previewUrl")
                .contains("if (a.previewUrl) row.append(buildPreviewButton(a));")
                .contains("button.dataset.previewKind = a.previewKind");
        assertThat(read(FRAGMENT))
                .as("the Thymeleaf mirror must gate on the same field and carry the same data-*")
                .contains("th:if=\"${a.previewUrl != null}\"")
                .contains("class=\"attachment-preview\"")
                .contains("data-preview-url=@{${a.previewUrl}}")
                .contains("data-preview-kind=${a.previewKind}");
    }

    @Test
    void anUploadedHtmlFileIsOnlyEverShownInABareSandbox() throws Exception {
        var kit = read(JS.resolve(SEAM_FILE));
        assertThat(kit)
                .as("the iframe must carry a bare sandbox attribute, set before its content")
                .contains("frame.setAttribute('sandbox', '')");
        int build = kit.indexOf("const buildSandboxedFrame");
        assertThat(build).as("buildSandboxedFrame block").isGreaterThan(-1);
        assertThat(kit.substring(build, kit.indexOf("};", build)))
                .as("the frame's content goes to srcdoc — a src on this origin would be a URL "
                        + "that renders somebody's upload as a top-level page")
                .contains("frame.srcdoc = source");

        // No sandbox token anywhere, in any file: allow-scripts runs the uploader's code,
        // allow-same-origin hands them this origin, and together they are a full compromise.
        for (Path p : jsSources()) {
            assertThat(codeOnly(read(p)))
                    .as("%s loosens the preview sandbox — there is no version of this feature "
                            + "that needs allow-scripts or allow-same-origin", p)
                    .doesNotContain("allow-scripts")
                    .doesNotContain("allow-same-origin");
        }
    }

    @Test
    void theUnsanitisedSourceNeverReachesThePageItself() throws Exception {
        // The field is named `source`, not `html`, precisely because it has not been through the
        // safelist. innerHTML or setHTML on it is the whole bug this feature could ship.
        for (Path p : jsSources()) {
            var src = read(p);
            assertThat(src)
                    .as("%s puts an uploaded file's own markup into this document", p)
                    .doesNotContain("innerHTML = data.source")
                    .doesNotContain("setHTML(data.source")
                    .doesNotContain("innerHTML = source");
        }
        // The markdown half is the opposite: server-sanitised, so it goes through the shared
        // message-body seam and comes out highlighted like any other body.
        assertThat(read(JS.resolve(SEAM_FILE)))
                .contains("renderMessageBody(el.querySelector('.lightbox-doc-body'), data.html || '')");
    }

    @Test
    void theEndpointsMatchTheKindsTheDtoAdvertises() throws Exception {
        // The DTO builds its previewUrl from Kind.slug(); if a route were renamed, every button
        // would 404 and nothing in Java would have failed to compile.
        var channel = read(Path.of("src/main/java/ai/intellistream/chat/web/AttachmentRestController.java"));
        var dm = read(Path.of("src/main/java/ai/intellistream/chat/web/ConversationRestController.java"));
        for (Kind kind : Kind.values()) {
            assertThat(channel)
                    .as("channel attachments need a %s route", kind.slug())
                    .contains("@GetMapping(\"/api/attachments/{id}/" + kind.slug() + "\")");
            assertThat(dm)
                    .as("conversation attachments need a %s route", kind.slug())
                    .contains("@GetMapping(\"/{conversationId}/attachments/{attachmentId}/" + kind.slug() + "\")");
        }
    }

    @Test
    void theButtonHasAnIconToDraw() throws Exception {
        // Icons come from the sprite, never emoji (AGENTS.md) — and a <use href> pointing at a
        // symbol that isn't there renders as nothing at all, silently.
        assertThat(read(TEMPLATES.resolve("fragments/icon-sprite.html")))
                .as("the preview button's icon must be in the sprite")
                .contains("<symbol id=\"icon-eye\"");
        assertThat(read(JS.resolve(SEAM_FILE))).contains("#icon-eye");
        assertThat(read(FRAGMENT)).contains("#icon-eye");
    }
}
