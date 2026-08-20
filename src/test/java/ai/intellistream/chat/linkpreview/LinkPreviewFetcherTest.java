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

package ai.intellistream.chat.linkpreview;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fetcher against a loopback HTTP server: Open Graph is read, redirects are followed and
 * guarded, bodies are capped, and a page that is not HTML or has no title yields no card. The
 * guard is constructed with loopback allowed — the one place that switch is used — because
 * production would refuse this server on sight, which is its own test below.
 */
class LinkPreviewFetcherTest {

    private HttpServer server;
    private String base;

    /** A 1x1 PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LinkPreviewFetcher fetcher(long maxHtml, long maxImage) {
        return new LinkPreviewFetcher(new OutboundUrlGuard(true), maxHtml, maxImage, Duration.ofSeconds(5), "test-agent");
    }

    private void html(String path, String body) {
        serve(path, 200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void serve(String path, int status, String contentType, byte[] body) {
        server.createContext(path, ex -> {
            if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
    }

    private void redirect(String path, String location) {
        server.createContext(path, ex -> {
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
    }

    @Test
    void readsOpenGraphAndFallsBackToTitleAndDescription() throws Exception {
        html("/og", """
                <html><head>
                <meta property="og:title" content="  An Article  ">
                <meta property="og:description" content="What it says">
                <meta property="og:site_name" content="Example News">
                <meta property="og:image" content="/pic.png">
                <title>ignored when og:title is present</title>
                </head><body></body></html>""");
        html("/plain", """
                <html><head><title>Plain Page</title>
                <meta name="description" content="Meta description"></head><body></body></html>""");

        var og = fetcher(1 << 20, 1 << 20).fetchPage(base + "/og").orElseThrow();
        assertThat(og.title()).isEqualTo("An Article");
        assertThat(og.description()).isEqualTo("What it says");
        assertThat(og.siteName()).isEqualTo("Example News");
        assertThat(og.imageUrl()).isEqualTo(URI.create(base + "/pic.png"));

        var plain = fetcher(1 << 20, 1 << 20).fetchPage(base + "/plain").orElseThrow();
        assertThat(plain.title()).isEqualTo("Plain Page");
        assertThat(plain.description()).isEqualTo("Meta description");
        assertThat(plain.siteName()).isEqualTo("127.0.0.1");
        assertThat(plain.imageUrl()).isNull();
    }

    @Test
    void aPageWithNoTitleAtAllIsEmptyNotAnError() throws Exception {
        html("/blank", "<html><head></head><body>hello</body></html>");
        assertThat(fetcher(1 << 20, 1 << 20).fetchPage(base + "/blank")).isEmpty();
    }

    @Test
    void redirectsAreFollowedAndTheFinalUrlResolvesRelativeImages() throws Exception {
        redirect("/short", base + "/long/article");
        html("/long/article", "<html><head><title>Landed</title><meta property=\"og:image\" content=\"img.png\"></head></html>");
        var meta = fetcher(1 << 20, 1 << 20).fetchPage(base + "/short").orElseThrow();
        assertThat(meta.title()).isEqualTo("Landed");
        assertThat(meta.imageUrl()).isEqualTo(URI.create(base + "/long/img.png"));
    }

    @Test
    void aRedirectLoopGivesUp() {
        redirect("/a", base + "/b");
        redirect("/b", base + "/a");
        assertThatThrownBy(() -> fetcher(1 << 20, 1 << 20).fetchPage(base + "/a"))
                .isInstanceOf(IOException.class).hasMessageContaining("too many redirects");
    }

    @Test
    void aRedirectToAForbiddenAddressIsRefusedAtThatHop() {
        redirect("/evil", "http://169.254.169.254/latest/meta-data/");
        assertThatThrownBy(() -> fetcher(1 << 20, 1 << 20).fetchPage(base + "/evil"))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class);
    }

    @Test
    void productionGuardRefusesLoopbackBeforeAnyRequestIsMade() {
        var hits = new AtomicInteger();
        server.createContext("/never", ex -> { hits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close(); });
        var production = new LinkPreviewFetcher(new OutboundUrlGuard(), 1 << 20, 1 << 20, Duration.ofSeconds(5), "t");
        assertThatThrownBy(() -> production.fetchPage(base + "/never"))
                .isInstanceOf(OutboundUrlGuard.RefusedException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    void notHtmlIsAnError() {
        serve("/data.json", 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> fetcher(1 << 20, 1 << 20).fetchPage(base + "/data.json"))
                .isInstanceOf(IOException.class).hasMessageContaining("not HTML");
    }

    @Test
    void anErrorStatusIsAnError() {
        serve("/gone", 404, "text/html", "<html><head><title>404</title></head></html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> fetcher(1 << 20, 1 << 20).fetchPage(base + "/gone"))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP 404");
    }

    @Test
    void aHugePageIsCutAfterTheCapAndStillYieldsItsHead() throws Exception {
        var head = "<html><head><title>Big</title></head><body>";
        var filler = new char[200_000];
        Arrays.fill(filler, 'x');
        html("/big", head + new String(filler) + "</body></html>");
        var meta = fetcher(4096, 1 << 20).fetchPage(base + "/big").orElseThrow();
        assertThat(meta.title()).isEqualTo("Big");
    }

    @Test
    void imagesAreCopiedSniffedAndCapped() {
        serve("/pic.png", 200, "image/png", PNG);
        serve("/lie.png", 200, "image/png", "<html>not a picture</html>".getBytes(StandardCharsets.UTF_8));
        serve("/huge.png", 200, "image/png", new byte[10_000]);

        var f = fetcher(1 << 20, 4096);
        var copied = f.fetchImage(URI.create(base + "/pic.png")).orElseThrow();
        assertThat(copied.contentType()).isEqualTo("image/png");
        assertThat(copied.bytes()).isEqualTo(PNG);

        assertThat(f.fetchImage(URI.create(base + "/lie.png"))).as("declared png, sniffed html").isEmpty();
        assertThat(f.fetchImage(URI.create(base + "/huge.png"))).as("over the image cap").isEmpty();
        assertThat(f.fetchImage(URI.create("http://169.254.169.254/x.png"))).as("refused address").isEmpty();
    }

    @Test
    void longFieldsAreClippedNotRejected() {
        assertThat(LinkPreviewFetcher.clip("  a   b  ", 100)).isEqualTo("a b");
        assertThat(LinkPreviewFetcher.clip("x".repeat(500), 300)).hasSize(300).endsWith("…");
        assertThat(LinkPreviewFetcher.clip("   ", 10)).isNull();
    }
}
