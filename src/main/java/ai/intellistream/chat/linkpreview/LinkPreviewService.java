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

import ai.intellistream.chat.domain.LinkPreview;
import ai.intellistream.chat.repository.LinkPreviewRepository;
import ai.intellistream.chat.web.dto.LinkPreviewDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Link previews: the card under a message that contains a URL.
 *
 * <p><b>Read side.</b> {@link #previewsFor} takes message bodies and answers, for each, the card to
 * show or null — one query for a page of messages, keyed by the URL that {@link LinkUrls} picks
 * out of the body. Nothing is stored on the message: which URL it shows is derived at read time,
 * so the {@code messages} table and the write-behind INSERT are untouched, and an edit that swaps
 * the link is looked up under the new one next time. A read never fetches and never writes.
 *
 * <p><b>Write side.</b> {@link #request} is called once a message is durable. It finds the URL,
 * hands it to a small bounded pool, and — if the fetch yields a card — invokes the caller's
 * callback with it, which is how the {@code link-preview} event reaches the room a few hundred
 * milliseconds after the message did. The pool is the whole budget: {@code threads} fetches at a
 * time, {@code queue-capacity} waiting, and past that a request is dropped with a log line — the
 * message was already delivered, only its card is missing, and a flood of links must not become
 * a flood of outbound connections. The same URL in flight twice is fetched once.
 *
 * <p><b>Cache.</b> One row per URL. A row younger than {@code refresh-after} is served as is (and
 * its {@code last_seen_at} bumped); an {@code EMPTY} or {@code FAILED} row is retried only after
 * {@code retry-failed-after}. Retention purges rows nobody has posted in {@code retention}, with
 * their image files. All three are {@code ichat.link-previews.*}.
 *
 * <p><b>Image.</b> Copied once, stored under {@code ichat.link-previews.dir} by a random key that
 * is the only thing the client is given, served by {@code LinkPreviewImageController}. Not
 * hotlinked: the CSP's {@code img-src 'self'} forbids it, and it would leak every reader's
 * address to every site anyone ever linked. Copying is also what makes the picture safe to show:
 * Tika says what the bytes are, and only png/jpeg/gif/webp are kept.
 *
 * <p><b>SSRF.</b> Every fetch — page, redirect hop, image — goes through {@link OutboundUrlGuard}
 * first. That class is the reason this feature can exist; read it before changing anything here.
 */
@Service
public class LinkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(LinkPreviewService.class);
    private static final Pattern IMAGE_KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final LinkPreviewProperties props;
    private final LinkPreviewRepository repository;
    private final LinkPreviewFetcher fetcher;
    private final TransactionTemplate tx;
    private final Path storageRoot;
    private final ExecutorService pool;
    private final ConcurrentHashMap<String, CompletableFuture<Optional<LinkPreviewDto>>> inFlight = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LinkPreviewService(LinkPreviewProperties props,
                              LinkPreviewRepository repository,
                              PlatformTransactionManager transactionManager) {
        this(props, repository, transactionManager, new LinkPreviewFetcher(new OutboundUrlGuard(), props));
    }

    /** The fetcher is injectable so the integration tests can point it at a loopback server. */
    public LinkPreviewService(LinkPreviewProperties props,
                              LinkPreviewRepository repository,
                              PlatformTransactionManager transactionManager,
                              LinkPreviewFetcher fetcher) {
        this.props = props;
        this.repository = repository;
        this.fetcher = fetcher;
        this.tx = new TransactionTemplate(transactionManager);
        this.storageRoot = Path.of(props.getDir()).toAbsolutePath().normalize();
        var threads = Math.max(1, props.getThreads());
        this.pool = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, props.getQueueCapacity())),
                r -> {
                    var t = new Thread(r, "link-preview");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> log.warn("Link preview queue full ({} waiting); dropping a fetch — the message "
                        + "is delivered, only its card is missing", props.getQueueCapacity()));
    }

    @PostConstruct
    public void ensureStorage() throws IOException {
        Files.createDirectories(storageRoot);
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** Where the copied images live; the orphan sweep reads this. */
    public Path storageRoot() {
        return storageRoot;
    }

    // ---------------------------------------------------------------- read side

    /**
     * For each body, the card to show or null. One query for the lot; a body with no previewable
     * URL costs nothing.
     */
    @Transactional(readOnly = true)
    public List<LinkPreviewDto> previewsFor(List<String> bodies) {
        if (!props.isEnabled() || bodies.isEmpty()) {
            return nulls(bodies.size());
        }
        var urls = bodies.stream().map(b -> LinkUrls.firstPreviewable(b).orElse(null)).toList();
        var hashes = urls.stream().filter(u -> u != null).map(LinkUrls::hash).distinct().toList();
        if (hashes.isEmpty()) {
            return nulls(bodies.size());
        }
        Map<String, LinkPreviewDto> byHash = new HashMap<>();
        for (var row : repository.findAllByUrlHashIn(hashes)) {
            if (row.isShowable()) byHash.put(row.getUrlHash(), LinkPreviewDto.from(row));
        }
        return urls.stream().map(u -> u == null ? null : byHash.get(LinkUrls.hash(u))).toList();
    }

    public LinkPreviewDto previewFor(String body) {
        return previewsFor(List.of(body == null ? "" : body)).getFirst();
    }

    /** The image file for a key the client was given, if the row still references it. */
    @Transactional(readOnly = true)
    public Optional<StoredImage> image(String key) {
        if (key == null || !IMAGE_KEY.matcher(key).matches()) return Optional.empty();
        return repository.findByImageKey(key)
                .filter(LinkPreview::isShowable)
                .map(p -> new StoredImage(storageRoot.resolve(p.getImageKey()).normalize(), p.getImageContentType()))
                .filter(s -> s.path().startsWith(storageRoot) && Files.isRegularFile(s.path()));
    }

    public record StoredImage(Path path, String contentType) {}

    // ---------------------------------------------------------------- write side

    /**
     * Unfurl the URL in {@code bodyMarkdown}, if there is one, and hand the card to
     * {@code onReady} when there is one to hand over. Returns at once; the work is on the pool.
     * Call this after the message is durable and broadcast, never before — a card for a message
     * nobody has yet is a card nobody can attach.
     */
    public void request(String bodyMarkdown, Consumer<LinkPreviewDto> onReady) {
        if (!props.isEnabled()) return;
        var url = LinkUrls.firstPreviewable(bodyMarkdown).orElse(null);
        if (url == null) return;
        resolve(url).thenAccept(preview -> preview.ifPresent(dto -> {
            try {
                onReady.accept(dto);
            } catch (RuntimeException e) {
                log.warn("Link preview callback failed for {}: {}", hostOf(url), e.toString());
            }
        }));
    }

    /**
     * The card for {@code url}, from the cache when fresh, else fetched — on the pool, once per
     * URL even if asked twice while in flight. Package-private for the tests; the future
     * completes with empty for a URL that yields no card and never completes exceptionally.
     */
    CompletableFuture<Optional<LinkPreviewDto>> resolve(String url) {
        var hash = LinkUrls.hash(url);
        return inFlight.computeIfAbsent(hash, h -> {
            var future = new CompletableFuture<Optional<LinkPreviewDto>>();
            try {
                pool.execute(() -> {
                    try {
                        future.complete(refresh(url, hash));
                    } catch (Throwable t) {
                        log.warn("Link preview for {} failed unexpectedly: {}", hostOf(url), t.toString());
                        future.complete(Optional.empty());
                    } finally {
                        inFlight.remove(h);
                    }
                });
            } catch (RuntimeException rejected) {
                // The rejection handler has already logged; nobody gets a card this time.
                inFlight.remove(h);
                future.complete(Optional.empty());
            }
            return future;
        });
    }

    /** Runs on the pool: consult the cache, maybe fetch, record the outcome, bump last-seen. */
    private Optional<LinkPreviewDto> refresh(String url, String hash) {
        var existing = tx.execute(status -> repository.findByUrlHash(hash).orElse(null));
        if (existing != null && isFresh(existing)) {
            tx.executeWithoutResult(status -> repository.findByUrlHash(hash).ifPresent(LinkPreview::seen));
            return existing.isShowable() ? Optional.of(LinkPreviewDto.from(existing)) : Optional.empty();
        }

        // Network, outside any transaction.
        Outcome outcome = fetch(url);

        var saved = tx.execute(status -> {
            var row = repository.findByUrlHash(hash).orElseGet(() -> new LinkPreview(hash, url));
            var previousImage = row.getImageKey();
            switch (outcome.kind) {
                case FETCHED -> row.fetched(outcome.meta.title(), outcome.meta.description(), outcome.meta.siteName(),
                        outcome.imageKey, outcome.imageContentType);
                case EMPTY -> row.empty();
                case FAILED -> row.failed();
            }
            row.seen();
            try {
                var persisted = repository.saveAndFlush(row);
                if (previousImage != null && !previousImage.equals(persisted.getImageKey())) {
                    deleteImageQuietly(previousImage);
                }
                return persisted;
            } catch (DataIntegrityViolationException raced) {
                // Two nodes (or a restart mid-flight) inserted the same URL first. Theirs wins;
                // ours — including the image we just wrote — is discarded.
                status.setRollbackOnly();
                if (outcome.imageKey != null) deleteImageQuietly(outcome.imageKey);
                return repository.findByUrlHash(hash).orElse(null);
            }
        });
        if (saved == null) return Optional.empty();
        return saved.isShowable() ? Optional.of(LinkPreviewDto.from(saved)) : Optional.empty();
    }

    private boolean isFresh(LinkPreview row) {
        var age = Duration.between(row.getFetchedAt(), Instant.now());
        return row.isShowable()
                ? age.compareTo(props.getRefreshAfter()) < 0
                : age.compareTo(props.getRetryFailedAfter()) < 0;
    }

    private enum Kind { FETCHED, EMPTY, FAILED }

    private record Outcome(Kind kind, LinkPreviewFetcher.PageMeta meta, String imageKey, String imageContentType) {
        static Outcome failed() { return new Outcome(Kind.FAILED, null, null, null); }
        static Outcome empty() { return new Outcome(Kind.EMPTY, null, null, null); }
    }

    private Outcome fetch(String url) {
        Optional<LinkPreviewFetcher.PageMeta> meta;
        try {
            meta = fetcher.fetchPage(url);
        } catch (OutboundUrlGuard.RefusedException e) {
            log.info("Link preview refused for {}: {}", hostOf(url), e.getMessage());
            return Outcome.failed();
        } catch (IOException e) {
            log.debug("Link preview for {} not fetched: {}", hostOf(url), e.getMessage());
            return Outcome.failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failed();
        }
        if (meta.isEmpty()) {
            return Outcome.empty();
        }
        var page = meta.get();
        String imageKey = null;
        String imageType = null;
        if (page.imageUrl() != null) {
            var image = fetcher.fetchImage(page.imageUrl());
            if (image.isPresent()) {
                try {
                    var key = UUID.randomUUID().toString();
                    Files.write(storageRoot.resolve(key), image.get().bytes());
                    imageKey = key;
                    imageType = image.get().contentType();
                } catch (IOException e) {
                    log.warn("Link preview image for {} not stored: {}", hostOf(url), e.toString());
                }
            }
        }
        return new Outcome(Kind.FETCHED, page, imageKey, imageType);
    }

    // ---------------------------------------------------------------- housekeeping

    /** Retention: rows nobody has posted in {@code ichat.link-previews.retention}, and their images. */
    @Scheduled(fixedDelayString = "${ichat.link-previews.purge-interval-ms:86400000}",
               initialDelayString = "${ichat.link-previews.purge-initial-delay-ms:600000}")
    public void purgeStale() {
        if (!props.isEnabled()) return;
        var cutoff = Instant.now().minus(props.getRetention());
        var stale = tx.execute(status -> {
            var rows = repository.findAllByLastSeenAtBefore(cutoff);
            repository.deleteAll(rows);
            return rows.stream().map(LinkPreview::getImageKey).filter(k -> k != null).toList();
        });
        if (stale == null || stale.isEmpty()) return;
        stale.forEach(this::deleteImageQuietly);
        log.info("Link previews: purged {} unseen since {}", stale.size(), cutoff);
    }

    /** Every image key a row still references — the live set for the orphan sweep. */
    @Transactional(readOnly = true)
    public Collection<String> liveImageKeys() {
        return repository.findAllImageKeys();
    }

    private void deleteImageQuietly(String key) {
        try {
            Files.deleteIfExists(storageRoot.resolve(key));
        } catch (IOException e) {
            log.debug("Link preview image {} not deleted: {}", key, e.toString());
        }
    }

    private static List<LinkPreviewDto> nulls(int n) {
        return java.util.Collections.nCopies(n, null);
    }

    private static String hostOf(String url) {
        try {
            var host = java.net.URI.create(url).getHost();
            return host == null ? "?" : host;
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }
}
