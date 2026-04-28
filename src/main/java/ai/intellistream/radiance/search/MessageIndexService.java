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

package ai.intellistream.radiance.search;

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
import java.util.UUID;

/**
 * Embedded Lucene index over message bodies.
 *
 * <p>Index layout (all per-message):
 * <ul>
 *   <li>{@code id} — StringField, stored. Message UUID, the document key.</li>
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

    public MessageIndexService(String dir) {
        var path = Path.of(dir);
        try {
            Files.createDirectories(path);
            this.directory = FSDirectory.open(path);
            var config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            this.writer = new IndexWriter(directory, config);
            this.writer.commit();
            this.searcherManager = new SearcherManager(writer, true, true, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open Lucene index at " + path.toAbsolutePath(), e);
        }
    }

    /** Add or replace the document for a single message. Refreshes the searcher view. */
    public void index(UUID messageId, UUID channelId, String author, String body) {
        var doc = new Document();
        doc.add(new StringField(F_ID, messageId.toString(), Field.Store.YES));
        doc.add(new StringField(F_CHANNEL, channelId.toString(), Field.Store.NO));
        doc.add(new StringField(F_AUTHOR, normalizeAuthor(author), Field.Store.NO));
        doc.add(new TextField(F_BODY, body == null ? "" : body, Field.Store.NO));
        try {
            writer.updateDocument(new Term(F_ID, messageId.toString()), doc);
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to index message " + messageId, e);
        }
    }

    private static String normalizeAuthor(String author) {
        return author == null ? "" : author.toLowerCase(java.util.Locale.ROOT);
    }

    /** Remove a single message from the index. */
    public void delete(UUID messageId) {
        try {
            writer.deleteDocuments(new Term(F_ID, messageId.toString()));
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete message " + messageId + " from index", e);
        }
    }

    /** Remove a batch of messages from the index in one commit. */
    public void deleteAll(Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        var terms = messageIds.stream()
                .map(id -> new Term(F_ID, id.toString()))
                .toArray(Term[]::new);
        try {
            writer.deleteDocuments(terms);
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + messageIds.size() + " messages from index", e);
        }
    }

    /** Search a single channel's messages, returning message IDs in relevance order. */
    public List<UUID> searchInChannel(UUID channelId, String query, Collection<String> authors, int limit) {
        var main = mainQuery(query, authors);
        if (main == null) return List.of();
        var scoped = new BooleanQuery.Builder()
                .add(main, BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(F_CHANNEL, channelId.toString())), BooleanClause.Occur.FILTER)
                .build();
        return runSearch(scoped, limit);
    }

    /** Search across multiple channels, returning message IDs in relevance order. */
    public List<UUID> searchInChannels(Collection<UUID> channelIds, String query,
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
    public List<UUID> searchEverywhere(String query, Collection<String> authors, int limit) {
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
                var doc = new Document();
                doc.add(new StringField(F_ID, row.id().toString(), Field.Store.YES));
                doc.add(new StringField(F_CHANNEL, row.channelId().toString(), Field.Store.NO));
                doc.add(new StringField(F_AUTHOR, normalizeAuthor(row.author()), Field.Store.NO));
                doc.add(new TextField(F_BODY, row.body() == null ? "" : row.body(), Field.Store.NO));
                writer.addDocument(doc);
            }
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rebuild Lucene index", e);
        }
    }

    @PreDestroy
    void close() {
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

    private List<UUID> runSearch(Query query, int limit) {
        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                var top = searcher.search(query, Math.max(limit, 1));
                var ids = new ArrayList<UUID>(top.scoreDocs.length);
                var storedFields = searcher.storedFields();
                for (var hit : top.scoreDocs) {
                    var doc = storedFields.document(hit.doc);
                    var idStr = doc.get(F_ID);
                    if (idStr != null) {
                        ids.add(UUID.fromString(idStr));
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
    public record IndexedMessage(UUID id, UUID channelId, String author, String body) {}
}
