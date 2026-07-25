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

package ai.intellistream.chat.search;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLEncoder;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.SimpleSpanFragmenter;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Embedded Lucene index over message bodies.
 *
 * <p>Index layout (all per-message):
 * <ul>
 *   <li>{@code id} — StringField, stored. Message id (long), the document key.</li>
 *   <li>{@code channelId} — StringField. Used to scope searches.</li>
 *   <li>{@code body} — TextField. Tokenised + analysed Markdown body.</li>
 * </ul>
 */
public class MessageIndexService {

    private static final Logger log = LoggerFactory.getLogger(MessageIndexService.class);

    static final String F_ID = "id";
    static final String F_CHANNEL = "channelId";
    static final String F_BODY = "body";
    /** Lowercased author username — exact-match {@link StringField} so {@code @bob} filters cleanly. */
    static final String F_AUTHOR = "author";

    private final FSDirectory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    /** When true, per-message index/delete only stage the change; a scheduled task batches the
     *  {@link SearcherManager#maybeRefresh() refresh} (visibility) and the {@link IndexWriter#commit()
     *  commit} (durability) off the hot path. When false (tests), each op refreshes + commits inline
     *  so a post is immediately searchable AND committed — the original synchronous behaviour. */
    private final boolean async;
    private final java.util.concurrent.atomic.AtomicBoolean pendingRefresh =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean pendingCommit =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public MessageIndexService(String dir, boolean async) {
        this(dir, async, DEFAULT_RAM_BUFFER_MB);
    }

    /** Default in-memory buffer before the writer flushes a segment. Lucene's own default is 16 MB. */
    static final double DEFAULT_RAM_BUFFER_MB = 256;

    public MessageIndexService(String dir, boolean async, double ramBufferMb) {
        this.async = async;
        var path = Path.of(dir);
        try {
            Files.createDirectories(path);
            this.directory = FSDirectory.open(path);
            var config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            // A chat firehose indexes small documents very fast, and Lucene's 16 MB default buffer
            // turns that into a stream of tiny segments — which the merge scheduler then spends
            // real CPU rewriting (decompressing and re-writing stored fields) almost as fast as
            // they appear. A large buffer means fewer, bigger segments and far less merge traffic.
            // The cost is heap held by the writer and a longer rebuild of anything not yet
            // committed, which is bounded by the periodic commit below.
            config.setRAMBufferSizeMB(ramBufferMb);
            config.setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH);
            if (config.getMergePolicy() instanceof org.apache.lucene.index.TieredMergePolicy tiered) {
                // Tolerate more segments per tier before merging: search here is over a modest
                // corpus and latency-insensitive relative to the write path, so trading a little
                // query speed for markedly less background merge work is the right way round.
                tiered.setSegmentsPerTier(20);
                tiered.setMaxMergeAtOnce(20);
            }
            this.writer = new IndexWriter(directory, config);
            this.writer.commit();
            this.searcherManager = new SearcherManager(writer, true, true, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open Lucene index at " + path.toAbsolutePath(), e);
        }
    }

    /** Add or replace the document for a single message. Refreshes the searcher view. */
    public void index(Long messageId, Long channelId, String author, String body) {
        try {
            writer.updateDocument(new Term(F_ID, messageId.toString()),
                    toDoc(messageId, channelId, author, body));
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to index message " + messageId, e);
        }
    }

    /**
     * Index a batch of <b>newly created</b> messages — ids that have never been indexed before.
     *
     * <p>Uses {@code addDocument} rather than {@code updateDocument}: replacing a document means
     * buffering a delete-by-term, which goes through the writer's global pending-deletes structure
     * and is a contention point when many threads index at once. A brand-new message id provably
     * has no prior document, so that work is pure overhead on the hottest path in the system.
     * Anything that can hit an existing id — an edit, the reconcile sweep, the bootstrap rebuild —
     * must keep using {@link #index}/{@link #reindex}.
     */
    public void indexNew(Collection<IndexedMessage> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            for (var row : rows) {
                writer.addDocument(toDoc(row.id(), row.channelId(), row.author(), row.body()));
            }
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to index " + rows.size() + " new message(s)", e);
        }
    }

    /** Add/replace many docs in a single commit — used by the reconcile sweep so a large backlog
     *  doesn't fsync-commit once per document while holding a DB connection (N26). */
    public void reindex(java.util.Collection<IndexedMessage> rows) {
        if (rows.isEmpty()) return;
        try {
            for (var row : rows) {
                writer.updateDocument(new Term(F_ID, row.id().toString()),
                        toDoc(row.id(), row.channelId(), row.author(), row.body()));
            }
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to reindex batch", e);
        }
    }

    private Document toDoc(Long messageId, Long channelId, String author, String body) {
        var doc = new Document();
        doc.add(new StringField(F_ID, messageId.toString(), Field.Store.YES));
        doc.add(new StringField(F_CHANNEL, channelId.toString(), Field.Store.NO));
        doc.add(new StringField(F_AUTHOR, normalizeAuthor(author), Field.Store.NO));
        doc.add(new TextField(F_BODY, body == null ? "" : body, Field.Store.NO));
        return doc;
    }

    private static String normalizeAuthor(String author) {
        return author == null ? "" : author.toLowerCase(java.util.Locale.ROOT);
    }

    /** Remove a single message from the index. */
    public void delete(Long messageId) {
        try {
            writer.deleteDocuments(new Term(F_ID, messageId.toString()));
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete message " + messageId + " from index", e);
        }
    }

    /** Remove a batch of messages from the index in one commit. */
    public void deleteAll(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        var terms = messageIds.stream()
                .map(id -> new Term(F_ID, id.toString()))
                .toArray(Term[]::new);
        try {
            writer.deleteDocuments(terms);
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + messageIds.size() + " messages from index", e);
        }
    }

    /** After a per-message write: async => stage refresh+commit for the batched maintainer;
     *  sync => flush both now (immediate visibility + durability — the test/original path). */
    private void afterWrite() throws IOException {
        if (async) {
            pendingRefresh.set(true);
            pendingCommit.set(true);
        } else {
            searcherManager.maybeRefresh();
            writer.commit();
        }
    }

    /** Search a single channel's messages, returning message IDs in relevance order. */
    public List<Long> searchInChannel(Long channelId, String query, Collection<String> authors, int limit) {
        var main = mainQuery(query, authors);
        if (main == null) return List.of();
        var scoped = new BooleanQuery.Builder()
                .add(main, BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(F_CHANNEL, channelId.toString())), BooleanClause.Occur.FILTER)
                .build();
        return runSearch(scoped, limit);
    }

    /** Search across multiple channels, returning message IDs in relevance order. */
    public List<Long> searchInChannels(Collection<Long> channelIds, String query,
                                       Collection<String> authors, int limit) {
        if (channelIds.isEmpty()) return List.of();
        var main = mainQuery(query, authors);
        if (main == null) return List.of();
        var ids = channelIds.stream().map(c -> new BytesRef(c.toString())).toList();
        var filter = new TermInSetQuery(F_CHANNEL, ids);
        var scoped = new BooleanQuery.Builder()
                .add(main, BooleanClause.Occur.MUST)
                .add(filter, BooleanClause.Occur.FILTER)
                .build();
        return runSearch(scoped, limit);
    }

    /** Search every indexed message across every channel. Caller is responsible for authorization. */
    public List<Long> searchEverywhere(String query, Collection<String> authors, int limit) {
        var main = mainQuery(query, authors);
        if (main == null) return List.of();
        return runSearch(main, limit);
    }

    /**
     * Combine a body fuzzy-match clause with an optional {@code @username} author filter.
     * Returns {@code null} when the user supplied neither (so the caller can short-circuit),
     * the body alone when authors aren't provided, or a {@code MatchAllDocsQuery + filter}
     * when only authors are given (e.g. {@code @alice} with no keyword).
     */
    private Query mainQuery(String query, Collection<String> authors) {
        Query bodyQuery = parse(query);
        Query authorFilter = authorFilter(authors);
        if (bodyQuery == null && authorFilter == null) return null;
        if (authorFilter == null) return bodyQuery;
        if (bodyQuery == null) {
            return new BooleanQuery.Builder()
                    .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                    .add(authorFilter, BooleanClause.Occur.FILTER)
                    .build();
        }
        return new BooleanQuery.Builder()
                .add(bodyQuery, BooleanClause.Occur.MUST)
                .add(authorFilter, BooleanClause.Occur.FILTER)
                .build();
    }

    private static Query authorFilter(Collection<String> authors) {
        if (authors == null || authors.isEmpty()) return null;
        var terms = authors.stream()
                .map(MessageIndexService::normalizeAuthor)
                .filter(s -> !s.isEmpty())
                .map(BytesRef::new)
                .toList();
        return terms.isEmpty() ? null : new TermInSetQuery(F_AUTHOR, terms);
    }

    /**
     * Every message id currently in the index (the stored {@code F_ID} of each live doc). Used by
     * the Lucene↔DB reconcile sweep (CLEAN-3) to diff the index against the {@code messages} table.
     */
    public java.util.Set<Long> allIndexedIds() {
        try {
            searcherManager.maybeRefresh();
            var searcher = searcherManager.acquire();
            try {
                var reader = searcher.getIndexReader();
                var ids = new java.util.HashSet<Long>(Math.max(16, reader.numDocs()));
                var liveDocs = org.apache.lucene.index.MultiBits.getLiveDocs(reader);
                for (int i = 0; i < reader.maxDoc(); i++) {
                    if (liveDocs != null && !liveDocs.get(i)) continue; // skip deleted docs
                    var doc = searcher.storedFields().document(i, java.util.Set.of(F_ID));
                    var v = doc.get(F_ID);
                    if (v != null) {
                        try { ids.add(Long.parseLong(v)); } catch (NumberFormatException ignored) { }
                    }
                }
                return ids;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to enumerate Lucene index ids", e);
        }
    }

    /** True if no documents are indexed yet. Used by the bootstrap reindex. */
    public boolean isEmpty() {
        try {
            searcherManager.maybeRefresh();
            var searcher = searcherManager.acquire();
            try {
                return searcher.getIndexReader().numDocs() == 0;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect Lucene index", e);
        }
    }

    /**
     * Replace the entire index with a fresh build. Used by the startup bootstrap when the
     * persisted index is missing or empty. Single-threaded; intended for app startup only.
     */
    public void rebuild(Iterable<IndexedMessage> rows) {
        try {
            writer.deleteAll();
            for (var row : rows) {
                // updateDocument (not addDocument) so that a live index() landing for the same id
                // between deleteAll and here — the bootstrap runs on ApplicationReadyEvent while the
                // server already accepts posts — can't leave two docs with the same id (N25).
                writer.updateDocument(new Term(F_ID, row.id().toString()),
                        toDoc(row.id(), row.channelId(), row.author(), row.body()));
            }
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rebuild Lucene index", e);
        }
    }

    /**
     * Batches the fsync-heavy {@link IndexWriter#commit()} off the per-message write path: commits
     * at most once per interval when there are pending changes (durability), instead of once per
     * message. Docs are already searchable via the per-op NRT refresh; a crash before the next
     * commit loses only the not-yet-committed index docs, which the DB→index reconcile (CLEAN-3) /
     * empty-index bootstrap rebuild from the source-of-truth {@code messages} table.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${ichat.search.flush-interval-ms:250}")
    void maintain() {
        try {
            if (pendingRefresh.compareAndSet(true, false)) searcherManager.maybeRefresh(); // visibility
        } catch (IOException e) {
            pendingRefresh.set(true);
            log.warn("Deferred Lucene refresh failed; will retry", e);
        }
        if (!pendingCommit.compareAndSet(true, false)) return;
        try {
            writer.commit(); // durability — batched across all messages since the last tick
        } catch (IOException e) {
            pendingCommit.set(true); // retry next tick
            log.warn("Deferred Lucene commit failed; will retry", e);
        }
    }

    @PreDestroy
    void close() {
        try {
            if (pendingRefresh.getAndSet(false)) searcherManager.maybeRefresh();
            if (pendingCommit.getAndSet(false)) writer.commit(); // durably flush anything pending
        } catch (IOException e) {
            log.warn("Final Lucene flush/commit on shutdown failed", e);
        }
        try {
            searcherManager.close();
        } catch (IOException e) {
            log.warn("Error closing SearcherManager", e);
        }
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("Error closing IndexWriter", e);
        }
        try {
            directory.close();
        } catch (IOException e) {
            log.warn("Error closing Directory", e);
        }
    }

    private Query parse(String query) {
        if (query == null) return null;
        var trimmed = query.trim();
        if (trimmed.length() < 2) return null;
        var parser = new FuzzyTermQueryParser(F_BODY, analyzer);
        // websearch_to_tsquery treats unquoted whitespace as AND; preserve that semantic.
        parser.setDefaultOperator(QueryParser.Operator.AND);
        try {
            return parser.parse(trimmed);
        } catch (ParseException e) {
            // Malformed query (e.g. unbalanced quote) — return no results rather than 500.
            return null;
        }
    }

    /**
     * Subclass of Lucene's {@link QueryParser} that turns naked {@code TermQuery}s into
     * {@link FuzzyQuery}s so typos like {@code wrold} still match {@code world}.
     * Edit distance scales with term length: 1 for 3-char terms, 2 for 4+ chars
     * (Lucene's max). Phrase queries, boolean operators, negation, prefix queries,
     * wildcards, and explicitly-fuzzy {@code term~N} input are all routed through
     * the parser's other hooks and stay unaffected.
     */
    private static final class FuzzyTermQueryParser extends QueryParser {
        FuzzyTermQueryParser(String field, Analyzer analyzer) {
            super(field, analyzer);
        }

        @Override
        protected Query newTermQuery(Term term, float boost) {
            var text = term.text();
            // Tier edits by length so we stay around the user's "70% correct" target:
            // < 3 chars   → strict (edit distance 2 matches almost anything)
            // 3–5 chars   → 1 edit  (4-char word + 2 edits = 50% — too loose)
            // 6–31 chars  → 2 edits (Lucene's default cap)
            // 32+ chars   → strict (Levenshtein-automaton state space grows with the term;
            //                       no real word is this long, so refuse fuzziness as a DoS guard)
            if (text.length() < 3 || text.length() >= 32) {
                return super.newTermQuery(term, boost);
            }
            int edits = text.length() < 6 ? 1 : FuzzyQuery.defaultMaxEdits;
            var fq = new FuzzyQuery(term, edits);
            return boost == 1f ? fq : new org.apache.lucene.search.BoostQuery(fq, boost);
        }

        // Refuse user-supplied wildcards. A query like `a*` would scan every term in the
        // index that starts with 'a' — easy DoS for an authenticated attacker. Naked `*`
        // becomes a MatchAllDocsQuery in Lucene's default parser and would dump every
        // message the viewer can read. Returning null at the parser level makes the
        // surrounding parse() return null too (no results) instead of throwing.
        @Override protected Query getWildcardQuery(String field, String termStr) { return null; }
        @Override protected Query getPrefixQuery(String field, String termStr)   { return null; }
        @Override protected Query getRegexpQuery(String field, String termStr)   { return null; }
    }

    private List<Long> runSearch(Query query, int limit) {
        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                var top = searcher.search(query, Math.max(limit, 1));
                var ids = new ArrayList<Long>(top.scoreDocs.length);
                var storedFields = searcher.storedFields();
                for (var hit : top.scoreDocs) {
                    var doc = storedFields.document(hit.doc);
                    var idStr = doc.get(F_ID);
                    if (idStr != null) {
                        ids.add(Long.parseLong(idStr));
                    }
                }
                return Collections.unmodifiableList(ids);
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Search failed", e);
        }
    }

    /**
     * Build a snippet for a message body that bolds the matched terms (wrapped in
     * {@code <mark>} tags). Used by the search dropdown to show the user where their
     * query hit in each result. Returns {@code null} when the query yields no parser
     * tree or the highlighter can't find a fragment — the caller falls back to the
     * unhighlighted body.
     *
     * <p>Output is HTML-safe: {@link SimpleHTMLEncoder} HTML-escapes the source body
     * before the formatter wraps tokens, so a body that literally contains
     * {@code <script>} comes out as {@code &lt;script&gt;} with the matched word
     * (if any) wrapped in {@code <mark>}. Frontend can render via {@code innerHTML}.
     *
     * @param query  user-supplied query string (already trimmed by caller)
     * @param body   message body (Markdown source, untrusted text)
     * @param maxLen approximate snippet length in characters
     */
    public String highlight(String query, String body, int maxLen) {
        if (body == null || body.isBlank()) return null;
        Query q = parse(query);
        if (q == null) return null;
        var formatter = new SimpleHTMLFormatter("<mark>", "</mark>");
        var encoder = new SimpleHTMLEncoder();
        var scorer = new QueryScorer(q, F_BODY);
        var highlighter = new Highlighter(formatter, encoder, scorer);
        highlighter.setTextFragmenter(new SimpleSpanFragmenter(scorer, Math.max(40, maxLen)));
        try (var stream = analyzer.tokenStream(F_BODY, new java.io.StringReader(body))) {
            return highlighter.getBestFragment(stream, body);
        } catch (IOException | org.apache.lucene.search.highlight.InvalidTokenOffsetsException e) {
            return null;
        }
    }

    /** Minimal projection used by {@link #rebuild(Iterable)}. */
    public record IndexedMessage(Long id, Long channelId, String author, String body) {}
}
