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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Flow;
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

    /**
     * The YouTube case, and the reason the cap is measured in megabytes: a watch page carries some
     * 700 KB of inline script before its {@code <title>}, and a cut above that is a page with no
     * title — an empty card, and a video link left with a play button on a black panel.
     */
    @Test
    void aHeadThatStartsHundredsOfKilobytesInIsStillRead() throws Exception {
        var script = new char[700_000];
        Arrays.fill(script, 'x');
        html("/late", "<html><head><script>var a=\"" + new String(script) + "\";</script>"
                + "<title>Late</title><meta property=\"og:image\" content=\"/pic.png\">"
                + "</head><body></body></html>");
        var meta = fetcher(2 << 20, 1 << 20).fetchPage(base + "/late").orElseThrow();
        assertThat(meta.title()).isEqualTo("Late");
        assertThat(meta.imageUrl()).isEqualTo(URI.create(base + "/pic.png"));
    }

    /**
     * …and the reason that cap is affordable: the read stops at the end of the head, so an ordinary
     * page costs its head and not its cap. Without this, raising the limit would mean pulling a
     * megabyte off every site anyone links.
     */
    @Test
    void theBodyAfterTheHeadIsNeverRead() throws Exception {
        var filler = new char[900_000];
        Arrays.fill(filler, 'x');
        html("/fat", "<html><HEAD><title>Small head</title></HeAd><body>" + new String(filler) + "</body></html>");
        var fetched = fetcher(2 << 20, 1 << 20)
                .get(URI.create(base + "/fat"), "text/html", 2 << 20, true);
        assertThat(fetched.body().length).isLessThan(1000);
        assertThat(new String(fetched.body(), StandardCharsets.UTF_8)).endsWith("</HeAd");
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

    /**
     * The subscriber's own contract, because the loopback server above cannot exercise it: it
     * speaks HTTP/1.1, and it is over HTTP/2 that cancelling the stream fails the response future.
     * {@code send} then falls back on these two methods, so a deliberate stop has to be
     * distinguishable from a real failure and the bytes have to survive it.
     */
    @Test
    void aDeliberateStopKeepsItsBytesAndSaysSo() throws Exception {
        var cancelled = new AtomicInteger();
        var sub = new LinkPreviewFetcher.LimitedBodySubscriber(1 << 20, true);
        sub.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { cancelled.incrementAndGet(); }
        });
        // The tag is split across two reads, and the third would never be looked at.
        sub.onNext(List.of(ByteBuffer.wrap("<html><HEAD><title>T</title></HE".getBytes(StandardCharsets.UTF_8))));
        sub.onNext(List.of(ByteBuffer.wrap("AD><body>ignored".getBytes(StandardCharsets.UTF_8))));
        sub.onNext(List.of(ByteBuffer.wrap("also ignored".getBytes(StandardCharsets.UTF_8))));

        assertThat(new String(sub.collected(), StandardCharsets.UTF_8))
                .isEqualTo("<html><HEAD><title>T</title></HEAD");
        assertThat(sub.stoppedOnPurpose()).isTrue();
        assertThat(cancelled.get()).isOne();
        // And the failure the cancellation provokes must not overwrite what we collected.
        sub.onError(new IOException("Stream 1 cancelled"));
        assertThat(sub.stoppedOnPurpose()).isTrue();
        assertThat(sub.collected()).hasSize(34);
    }

    @Test
    void anImageOverTheCapIsAFailureNotADeliberateStop() {
        var sub = new LinkPreviewFetcher.LimitedBodySubscriber(8, false);
        sub.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { }
        });
        sub.onNext(List.of(ByteBuffer.wrap(new byte[64])));
        assertThat(sub.stoppedOnPurpose()).isFalse();
    }

    @Test
    void longFieldsAreClippedNotRejected() {
        assertThat(LinkPreviewFetcher.clip("  a   b  ", 100)).isEqualTo("a b");
        assertThat(LinkPreviewFetcher.clip("x".repeat(500), 300)).hasSize(300).endsWith("…");
        assertThat(LinkPreviewFetcher.clip("   ", 10)).isNull();
    }
}
