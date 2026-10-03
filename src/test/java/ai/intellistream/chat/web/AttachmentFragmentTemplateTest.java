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

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.i18n.TimeFormats;
import ai.intellistream.chat.web.dto.AttachmentDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attachment tray both server-rendered feeds share ({@code fragments/attachment.html}),
 * rendered for real from DTOs built the way the controllers build them.
 *
 * <p>What it guards: a video row has to leave the server as a card carrying
 * {@code data-video-type}, because that attribute is the whole hand-off to {@code chat-kit.js},
 * which is what turns it into a player. Drop it and every video in the history is a download
 * again — while live messages, built by the script, still play. That split is invisible until
 * someone refreshes. The rows here come through {@link AttachmentDto#from}, so a file stored
 * before the player existed, under the container type Tika gave it, is covered too.
 */
class AttachmentFragmentTemplateTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static SpringTemplateEngine engine;
    private static GenericApplicationContext applicationContext;

    @BeforeAll
    static void engine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        applicationContext = new GenericApplicationContext();
        applicationContext.refresh();

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    private String render(List<AttachmentDto> attachments) {
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        var response = new MockHttpServletResponse();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(request, response);

        var variables = new HashMap<String, Object>();
        variables.put(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));
        variables.put("fmt", new TimeFormats("UTC").forUser(null, Locale.ENGLISH));
        variables.put("attachments", attachments);
        return engine.process("fragments/attachment", Set.of("tray"),
                new WebContext(exchange, Locale.ENGLISH, variables));
    }

    private static final User UPLOADER = new User("kc-alice", "alice", "alice@example.com", "Alice A");

    private static AttachmentDto row(long id, String filename, String contentType) {
        var channel = new Channel("general", "general", null, ChannelType.PUBLIC, UPLOADER);
        var attachment = new Attachment(new Message(channel, UPLOADER, ""), filename, contentType, 2048, "key-" + id);
        try {
            var field = Attachment.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(attachment, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return AttachmentDto.from(attachment);
    }

    /** The opening tag of the element whose href is this attachment's download URL. */
    private static String tagFor(String html, long id) {
        var m = Pattern.compile("<a[^>]*href=\"/api/attachments/" + id + "/download\"[^>]*>").matcher(html);
        assertThat(m.find()).as("a link for attachment %d", id).isTrue();
        return m.group();
    }

    @Test
    void aVideoIsACardThatTellsTheScriptWhatToAskTheBrowser() {
        var html = render(List.of(row(1, "clip.mp4", "video/mp4")));

        var tag = tagFor(html, 1);
        assertThat(tag).contains("class=\"attachment\"").contains("data-video-type=\"video/mp4\"");
        // The card is the fallback, so it must still be a working download on its own.
        assertThat(html).contains("clip.mp4").contains("#icon-video");
    }

    @Test
    void aVideoStoredUnderItsContainerTypeIsStillOfferedAsAVideo() {
        // An existing row from before the player: Tika saw EBML and could not tell WebM from
        // Matroska, so the row says application/x-matroska. Nothing was migrated; it still plays.
        var tag = tagFor(render(List.of(row(2, "screen.webm", "application/x-matroska"))), 2);
        assertThat(tag).contains("data-video-type=\"video/webm\"");
    }

    @Test
    void anythingElseIsAPlainCardWithNoVideoAttribute() {
        var html = render(List.of(row(3, "report.pdf", "application/pdf"), row(4, "song.mp3", "audio/mpeg")));

        assertThat(tagFor(html, 3)).doesNotContain("data-video-type");
        assertThat(tagFor(html, 4)).doesNotContain("data-video-type");
        assertThat(html).doesNotContain("#icon-video");
    }

    @Test
    void imagesStillGoToTheLightboxAndTombstonesAreNotLinks() {
        var image = row(5, "cat.png", "image/png");
        var gone = row(6, "clip.mp4", "video/mp4");
        var deleted = new AttachmentDto(gone.id(), gone.filename(), gone.contentType(), gone.sizeBytes(),
                null, gone.createdAt(), java.time.Instant.parse("2026-10-01T12:00:00Z"), "alice", null);
        var html = render(List.of(image, deleted));

        assertThat(tagFor(html, 5)).contains("class=\"attachment-image\"");
        assertThat(html).contains("attachment-removed").contains("by alice");
        assertThat(html).doesNotContain("/api/attachments/6/download").doesNotContain("data-video-type");
    }

    @Test
    void anEmptyListRendersNothing() {
        assertThat(render(List.of())).doesNotContain("message-attachments");
    }

    @Test
    void bothFeedsUseTheSharedFragment() throws IOException {
        // The fragment exists because the two pages each carried the same forty lines; a page that
        // grows its own copy again is a page whose history never gets the player.
        for (var page : List.of("channels.html", "conversation.html")) {
            var src = Files.readString(TEMPLATES.resolve(page));
            assertThat(src).as(page).contains("~{fragments/attachment :: tray(${msg.attachments})}");
            assertThat(src).as(page + " must not render attachment cards itself")
                    .doesNotContain("class=\"attachment-image\"")
                    .doesNotContain("class=\"attachment\"");
        }
    }
}
