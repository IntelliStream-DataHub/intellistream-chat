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

import ai.intellistream.chat.attachments.AttachmentBytes;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The outbound half of link previews: GET a page, read what it says about itself, and — if it
 * offers one — copy its picture. Every request goes through {@link OutboundUrlGuard}, every
 * redirect hop again, and every body is read to a cap and no further, under one deadline that
 * covers the whole exchange rather than just the headers.
 *
 * <p><b>A page is read until its head closes, not to a byte count.</b> The cap used to be the only
 * stopping rule, on the assumption that Open Graph sits near the top of the file. It does not
 * always: a YouTube watch page carries some 700 KB of inline script and JSON <em>before</em> its
 * {@code <title>}, so a 512 KB cut produced a document with no title at all — the card came back
 * empty and a video link was left with a bare play button on a black panel, which is how this was
 * found. So {@link LimitedBodySubscriber} stops at {@code </head>} and the cap is only the
 * backstop for a page that never closes one. Stopping early is what makes the backstop affordable:
 * an ordinary page still costs a few KB, because nothing past its head is ever read.
 *
 * <p>Deliberately dumb about the page: Open Graph first ({@code og:title}, {@code og:description},
 * {@code og:site_name}, {@code og:image}), Twitter cards as the fallback, then the plain
 * {@code <title>} and {@code <meta name=description>}. No JavaScript, no oEmbed, no per-site
 * special cases. A page that wants a good card publishes Open Graph; nearly all do.
 *
 * <p>Redirects are followed by hand rather than by the client, because the client would follow
 * them without asking the guard. Five hops, then give up.
 *
 * <p>Nothing here touches the database or the broker; {@link LinkPreviewService} owns that. The
 * tests ({@code LinkPreviewFetcherTest}) run this against a loopback HTTP server, which is why
 * the guard is injected rather than constructed here.
 */
public class LinkPreviewFetcher {

    private static final Logger log = LoggerFactory.getLogger(LinkPreviewFetcher.class);

    /** What the page said about itself. All fields may be null except {@code title}. */
    public record PageMeta(String title, String description, String siteName, URI imageUrl) {}

    /** The bytes of a copied image and the type Tika says they are. */
    public record ImageBytes(byte[] bytes, String contentType) {}

    static final int MAX_REDIRECTS = 5;
    static final int TITLE_MAX = 300;
    static final int DESCRIPTION_MAX = 600;
    static final int SITE_NAME_MAX = 120;
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final HttpClient client;
    private final OutboundUrlGuard guard;
    private final long maxHtmlBytes;
    private final long maxImageBytes;
    private final Duration timeout;
    private final String userAgent;

    public LinkPreviewFetcher(OutboundUrlGuard guard, LinkPreviewProperties props) {
        this(guard, props.getMaxHtmlBytes(), props.getMaxImageBytes(), props.getTimeout(), props.getUserAgent());
    }

    public LinkPreviewFetcher(OutboundUrlGuard guard, long maxHtmlBytes, long maxImageBytes,
                              Duration timeout, String userAgent) {
        this.guard = guard;
        this.maxHtmlBytes = maxHtmlBytes;
        this.maxImageBytes = maxImageBytes;
        this.timeout = timeout;
        this.userAgent = userAgent;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    /**
     * Fetch the page and read its metadata. Empty when the page answered but offers no title;
     * throws when it could not be fetched at all (refused, unreachable, not HTML, too many hops).
     */
    public Optional<PageMeta> fetchPage(String url)
            throws IOException, OutboundUrlGuard.RefusedException, InterruptedException {
        // Everything Open Graph offers is inside the head, so the read stops when the head does;
        // a page that never closes one is cut at the cap, not refused.
        var fetched = get(URI.create(url), "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1", maxHtmlBytes, true);
        var type = contentType(fetched.headers());
        if (!type.startsWith("text/html") && !type.startsWith("application/xhtml+xml")) {
            throw new IOException("not HTML: " + (type.isEmpty() ? "(no content-type)" : type));
        }
        Document doc = Jsoup.parse(new String(fetched.body(), charsetOf(type)), fetched.uri().toString());
        return parse(doc, fetched.uri());
    }

    /**
     * Copy the page's image. Empty when there is none worth keeping — wrong type, too large,
     * refused, unreachable — because a card without a picture is fine and a failed image must
     * never cost the card.
     */
    public Optional<ImageBytes> fetchImage(URI imageUrl) {
        try {
            var fetched = get(imageUrl, "image/*", maxImageBytes, false);
            if (fetched.body().length == 0) return Optional.empty();
            var declared = contentType(fetched.headers());
            String sniffed;
            try (var in = new BufferedInputStream(new ByteArrayInputStream(fetched.body()))) {
                sniffed = AttachmentBytes.sniffContentType(in, declared);
            }
            if (!IMAGE_TYPES.contains(sniffed)) {
                log.debug("Link preview image at {} is {}, not an allowed image type", imageUrl.getHost(), sniffed);
                return Optional.empty();
            }
            return Optional.of(new ImageBytes(fetched.body(), sniffed));
        } catch (TooLargeException e) {
            log.debug("Link preview image at {} exceeds {} bytes; card goes without", imageUrl.getHost(), maxImageBytes);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Link preview image at {} not copied: {}", imageUrl.getHost(), e.toString());
            return Optional.empty();
        }
    }

    record Fetched(URI uri, HttpHeaders headers, byte[] body) {}

    /**
     * GET with manual, guarded redirects and a single deadline over headers and body. Returns a
     * 2xx response with at most {@code maxBytes} of body. {@code headOnly} is the HTML case: the
     * read stops at {@code </head>}, and a body that reaches the cap is truncated rather than
     * refused. Without it — the image case — the cap is a hard limit and overshooting it throws
     * {@link TooLargeException}.
     */
    Fetched get(URI start, String accept, long maxBytes, boolean headOnly)
            throws IOException, OutboundUrlGuard.RefusedException, InterruptedException {
        var chain = new ArrayList<URI>();
        var uri = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            guard.check(uri);
            chain.add(uri);
            var request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", accept)
                    .header("Accept-Language", "en")
                    .build();
            var response = send(request, maxBytes, headOnly);
            int status = response.status();
            if (status >= 200 && status < 300) {
                // The client never redirects for us, so the request URI is the final one — which
                // matters, because the deliberate-stop path below has no HttpResponse to ask.
                return new Fetched(uri, response.headers(), response.body());
            }
            if (status >= 300 && status < 400) {
                var location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("redirect without Location from " + chain.getLast().getHost()));
                try {
                    uri = uri.resolve(new URI(location.trim()));
                } catch (URISyntaxException | IllegalArgumentException e) {
                    throw new IOException("bad redirect Location from " + chain.getLast().getHost());
                }
                continue;
            }
            throw new IOException("HTTP " + status + " from " + OutboundUrlGuard.describe(chain));
        }
        throw new IOException("too many redirects: " + OutboundUrlGuard.describe(chain));
    }

    /** Status, headers and however much body {@link LimitedBodySubscriber} decided to keep. */
    private record Response(int status, HttpHeaders headers, byte[] body) {}

    /**
     * One request, with the response we asked for even when we hung up on it.
     *
     * <p>Reading only as far as the head means cancelling the subscription mid-body, and over
     * HTTP/2 that cancellation comes back here as {@code IOException: Stream N cancelled} — the
     * response future fails although the body subscriber completed with exactly the bytes we
     * wanted. Treating that as a failed fetch is how a YouTube page became a card with nothing in
     * it. So when the subscriber stopped on purpose, its bytes are the answer, and the
     * {@code ResponseInfo} the handler was called with supplies the status and headers that the
     * (never delivered) {@code HttpResponse} would have. It does not show up on the loopback
     * server the tests use, which speaks HTTP/1.1 — only against a real HTTP/2 site.
     */
    private Response send(HttpRequest request, long maxBytes, boolean headOnly)
            throws IOException, InterruptedException {
        var info = new AtomicReference<HttpResponse.ResponseInfo>();
        var subscriber = new AtomicReference<LimitedBodySubscriber>();
        try {
            var response = client.sendAsync(request, in -> {
                        info.set(in);
                        var s = new LimitedBodySubscriber(maxBytes, headOnly);
                        subscriber.set(s);
                        return s;
                    })
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .get();
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (ExecutionException e) {
            var stopped = subscriber.get();
            var head = info.get();
            if (stopped != null && head != null && stopped.stoppedOnPurpose()) {
                return new Response(head.statusCode(), head.headers(), stopped.collected());
            }
            var cause = e.getCause();
            if (cause instanceof TooLargeException tle) throw tle;
            if (cause instanceof IOException io) throw io;
            if (cause instanceof TimeoutException) throw new IOException("timed out after " + timeout);
            throw new IOException(cause == null ? e.toString() : cause.toString());
        }
    }

    static Optional<PageMeta> parse(Document doc, URI pageUri) {
        var title = firstNonBlank(
                meta(doc, "property", "og:title"),
                meta(doc, "name", "twitter:title"),
                doc.title());
        if (title == null) {
            return Optional.empty();
        }
        var description = firstNonBlank(
                meta(doc, "property", "og:description"),
                meta(doc, "name", "twitter:description"),
                meta(doc, "name", "description"));
        var siteName = firstNonBlank(meta(doc, "property", "og:site_name"), pageUri.getHost());
        var image = firstNonBlank(
                meta(doc, "property", "og:image"),
                meta(doc, "property", "og:image:url"),
                meta(doc, "name", "twitter:image"));
        URI imageUri = null;
        if (image != null) {
            try {
                var resolved = pageUri.resolve(new URI(image.trim()));
                if (LinkUrls.isHttpUrl(resolved.toString())) {
                    imageUri = resolved;
                }
            } catch (URISyntaxException | IllegalArgumentException ignored) {
                // A malformed og:image is a page problem, not ours; the card goes without.
            }
        }
        return Optional.of(new PageMeta(
                clip(title, TITLE_MAX), clip(description, DESCRIPTION_MAX), clip(siteName, SITE_NAME_MAX), imageUri));
    }

    private static String meta(Document doc, String attr, String key) {
        var el = doc.selectFirst("meta[" + attr + "=" + key + "]");
        if (el == null) return null;
        var content = el.attr("content");
        return content == null ? null : content.trim();
    }

    private static String firstNonBlank(String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        var collapsed = s.replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) return null;
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max - 1).trim() + "…";
    }

    private static String contentType(HttpHeaders headers) {
        return headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).trim();
    }

    private static Charset charsetOf(String contentType) {
        int i = contentType.indexOf("charset=");
        if (i < 0) return StandardCharsets.UTF_8;
        var cs = contentType.substring(i + "charset=".length());
        int semi = cs.indexOf(';');
        if (semi >= 0) cs = cs.substring(0, semi);
        try {
            return Charset.forName(cs.trim().replace("\"", ""));
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    static final class TooLargeException extends IOException {
        TooLargeException(long max) {
            super("body exceeds " + max + " bytes");
        }
    }

    /**
     * Collects a body up to a cap, and — in {@code headOnly} mode — no further than the end of the
     * document head, which is where every field this class reads lives. Past the cap it either
     * stops and hands back what it has (HTML) or fails (an image too big to keep). Either way the
     * connection is cancelled, so a hostile or merely huge body costs at most the cap in bandwidth
     * and memory, and an ordinary page costs only its head.
     */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        /** Matched case-insensitively over the raw bytes; the closing {@code >} is not needed. */
        private static final byte[] HEAD_END = "</head".getBytes(StandardCharsets.US_ASCII);

        private final long max;
        private final boolean headOnly;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long total;
        /** How much of {@link #HEAD_END} the bytes so far end with — carried across chunks. */
        private int matched;
        /** Set when we hung up ourselves, which is what makes the failure that follows harmless. */
        private volatile boolean stopped;

        LimitedBodySubscriber(long max, boolean headOnly) {
            this.max = max;
            this.headOnly = headOnly;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (var buffer : buffers) {
                int n = buffer.remaining();
                long room = max - total;
                if (n > room) {
                    if (headOnly) {
                        var chunk = new byte[(int) Math.max(0, room)];
                        buffer.get(chunk);
                        out.write(chunk, 0, chunk.length);
                        total += chunk.length;
                        stop();
                    } else {
                        subscription.cancel();
                        result.completeExceptionally(new TooLargeException(max));
                    }
                    return;
                }
                var chunk = new byte[n];
                buffer.get(chunk);
                int keep = headOnly ? headEnd(chunk) : -1;
                if (keep < 0) keep = n;
                out.write(chunk, 0, keep);
                total += keep;
                if (keep < n || (headOnly && matched == HEAD_END.length)) {
                    stop();
                    return;
                }
            }
        }

        /**
         * How many bytes of {@code chunk} to keep when it completes {@code </head}, or -1 when the
         * head has not closed yet. The match state is carried between chunks, so a tag split
         * across two network reads is still seen.
         */
        private int headEnd(byte[] chunk) {
            for (int i = 0; i < chunk.length; i++) {
                byte c = lower(chunk[i]);
                if (c == HEAD_END[matched]) {
                    if (++matched == HEAD_END.length) return i + 1;
                } else {
                    matched = c == HEAD_END[0] ? 1 : 0;
                }
            }
            return -1;
        }

        private static byte lower(byte b) {
            return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
        }

        /** We have what we came for: answer first, then hang up — the cancel may raise an error. */
        private void stop() {
            stopped = true;
            result.complete(out.toByteArray());
            subscription.cancel();
        }

        /** Whether the read ended because we stopped it rather than because it failed. */
        boolean stoppedOnPurpose() {
            return stopped && result.isDone() && !result.isCompletedExceptionally();
        }

        /** What we kept. Only meaningful once the body future is done. */
        byte[] collected() {
            return result.getNow(new byte[0]);
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }
}
