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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.LinkPreview;
import ai.intellistream.chat.linkpreview.LinkPreviewFetcher;
import ai.intellistream.chat.linkpreview.LinkPreviewProperties;
import ai.intellistream.chat.linkpreview.LinkPreviewService;
import ai.intellistream.chat.linkpreview.OutboundUrlGuard;
import ai.intellistream.chat.repository.LinkPreviewRepository;
import ai.intellistream.chat.web.dto.LinkPreviewDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service end to end against Postgres and a loopback page: a request fetches, stores a row
 * and the image, hands the card to the callback; a read decorates from the row without fetching;
 * the same URL twice is one fetch; a page with nothing is EMPTY and yields no card; the image is
 * served back by key and only by key.
 *
 * <p>The service under test is built by hand with a loopback-tolerant guard rather than the
 * context's bean, whose production guard would refuse the test server on sight — that refusal is
 * a unit test's job ({@code LinkPreviewFetcherTest}), and this one is about the database.
 */
@Testcontainers
@SpringBootTest(classes = IntegrationTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LinkPreviewFlowIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat").withUsername("chat").withPassword("chat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-link-previews");
        TestLuceneDirs.register(registry);
    }

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    static HttpServer server;
    static String base;
    static final AtomicInteger articleHits = new AtomicInteger();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/article", ex -> {
            articleHits.incrementAndGet();
            var body = ("<html><head><meta property=\"og:title\" content=\"The Article\">"
                    + "<meta property=\"og:description\" content=\"About things\">"
                    + "<meta property=\"og:site_name\" content=\"Test Site\">"
                    + "<meta property=\"og:image\" content=\"/pic.png\"></head></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
        server.createContext("/pic.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, PNG.length);
            try (var out = ex.getResponseBody()) { out.write(PNG); }
        });
        server.createContext("/blank", ex -> {
            var body = "<html><head></head><body>nothing</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Autowired LinkPreviewRepository repository;
    @Autowired PlatformTransactionManager txManager;

    /** Poll a condition for up to ten seconds; the fetch runs on the service's own thread. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within 10s");
            Thread.sleep(50);
        }
    }

    private LinkPreviewService service() throws IOException {
        var props = new LinkPreviewProperties();
        props.setDir(Files.createTempDirectory("chat-link-previews-it-").toString());
        props.setThreads(1);
        var fetcher = new LinkPreviewFetcher(new OutboundUrlGuard(true), props.getMaxHtmlBytes(),
                props.getMaxImageBytes(), Duration.ofSeconds(5), "it");
        var s = new LinkPreviewService(props, repository, txManager, fetcher);
        s.ensureStorage();
        return s;
    }

    @Test
    void aPostedLinkBecomesARowACardAndAServedImage() throws Exception {
        var service = service();
        var url = base + "/article";
        var body = "look at this " + url + " please";
        var cards = new CopyOnWriteArrayList<LinkPreviewDto>();

        // Nothing yet: a read never fetches.
        assertThat(service.previewFor(body)).isNull();

        service.request(body, cards::add);
        awaitUntil(() -> !cards.isEmpty());

        var card = cards.getFirst();
        assertThat(card.url()).isEqualTo(url);
        assertThat(card.title()).isEqualTo("The Article");
        assertThat(card.description()).isEqualTo("About things");
        assertThat(card.siteName()).isEqualTo("Test Site");
        assertThat(card.imageUrl()).startsWith(LinkPreviewDto.IMAGE_PATH);

        // The read side now answers from the row, and the row is FETCHED with the image on disk.
        assertThat(service.previewFor(body)).isEqualTo(card);
        assertThat(service.previewsFor(List.of("no link", body, "and " + url)))
                .containsExactly(null, card, card);
        var key = card.imageUrl().substring(LinkPreviewDto.IMAGE_PATH.length());
        var stored = service.image(key).orElseThrow();
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(PNG);
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(service.image("not-a-key")).isEmpty();
        assertThat(service.image("00000000-0000-0000-0000-000000000000")).isEmpty();
        assertThat(service.liveImageKeys()).contains(key);

        // A second message with the same URL is served from the cache: one fetch, ever, while fresh.
        var before = articleHits.get();
        var again = new CopyOnWriteArrayList<LinkPreviewDto>();
        service.request("again " + url, again::add);
        awaitUntil(() -> !again.isEmpty());
        assertThat(again.getFirst()).isEqualTo(card);
        assertThat(articleHits.get()).isEqualTo(before);
    }

    @Test
    void aPageWithNothingToSayIsRememberedAsEmptyAndYieldsNoCard() throws Exception {
        var service = service();
        var url = base + "/blank";
        var cards = new CopyOnWriteArrayList<LinkPreviewDto>();
        service.request("see " + url, cards::add);

        awaitUntil(() -> repository.findByUrlHash(
                ai.intellistream.chat.linkpreview.LinkUrls.hash(url)).isPresent());
        var row = repository.findByUrlHash(ai.intellistream.chat.linkpreview.LinkUrls.hash(url)).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(LinkPreview.Status.EMPTY);
        assertThat(cards).isEmpty();
        assertThat(service.previewFor("see " + url)).isNull();
    }

    @Test
    void aRefusedAddressIsRememberedAsFailedAndYieldsNoCard() throws Exception {
        var service = service();
        var url = "http://10.0.0.1/secret";
        var cards = new CopyOnWriteArrayList<LinkPreviewDto>();
        service.request("internal " + url, cards::add);

        awaitUntil(() -> repository.findByUrlHash(
                ai.intellistream.chat.linkpreview.LinkUrls.hash(url)).isPresent());
        var row = repository.findByUrlHash(ai.intellistream.chat.linkpreview.LinkUrls.hash(url)).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(LinkPreview.Status.FAILED);
        assertThat(cards).isEmpty();
    }

    @Test
    void aBodyWithNoLinkAsksForNothing() throws Exception {
        var service = service();
        var cards = new CopyOnWriteArrayList<LinkPreviewDto>();
        service.request("just words", cards::add);
        service.request("a video https://youtu.be/dQw4w9WgXcQ", cards::add);
        Thread.sleep(200);
        assertThat(cards).isEmpty();
        assertThat(repository.findByUrlHash(ai.intellistream.chat.linkpreview.LinkUrls.hash("https://youtu.be/dQw4w9WgXcQ"))).isEmpty();
    }
}
