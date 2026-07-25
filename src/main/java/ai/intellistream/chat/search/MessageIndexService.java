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
 * Embedded Lucene index over message bodies. One index holds <b>two</b> kinds of document:
 * channel messages ({@code messages}) and conversation messages ({@code conversation_messages},
 * i.e. DMs and group conversations).
 *
 * <p>Index layout:
 * <ul>
 *   <li>{@code id} — StringField, stored. The document key. For a channel message it is the bare
 *       numeric message id ({@code "123"}); for a conversation message it is
 *       {@code "conv:123"}. The two tables have independent identity sequences, so a shared
 *       index needs a namespaced key or message 42 of a channel and message 42 of a DM would
 *       overwrite each other.</li>
 *   <li>{@code channelId} — StringField, channel documents only. Scopes channel searches.</li>
 *   <li>{@code conversationId} — StringField, conversation documents only. Scopes conversation
 *       searches and carries the membership ACL filter (see {@link #searchAccessible}).</li>
 *   <li>{@code kind} — StringField, <b>conversation documents only</b>, constant value
 *       {@code "conversation"}.</li>
 *   <li>{@code author} — StringField, lowercased username.</li>
 *   <li>{@code body} — TextField. Tokenised + analysed Markdown body.</li>
 * </ul>
 *
 * <h2>Why the asymmetry (bare vs prefixed key, kind on one side only)</h2>
 * An index built by an earlier version of this application is on disk in production and is only
 * rebuilt from scratch when it is <em>empty</em> — the startup path otherwise reconciles it in
 * place. Every legacy document is a channel document with a bare numeric {@code id} and no
 * {@code kind}. Leaving channel documents exactly as they were means the upgrade needs no
 * reindex, no format version and no migration: existing docs keep matching
 * {@code updateDocument}/{@code deleteDocuments} by their {@code id} term, and "is this a
 * conversation document?" is answered by a term that legacy docs provably don't carry — so the
 * admin-only cross-channel search excludes DMs with a {@code MUST_NOT kind:conversation} that is
 * correct for old and new channel documents alike.
 */
public class MessageIndexService {

    private static final Logger log = LoggerFactory.getLogger(MessageIndexService.class);

    static final String F_ID = "id";
    static final String F_CHANNEL = "channelId";
    static final String F_CONVERSATION = "conversationId";
    static final String F_BODY = "body";
    /** Lowercased author username — exact-match {@link StringField} so {@code @bob} filters cleanly. */
    static final String F_AUTHOR = "author";
    /** Present only on conversation documents; see the class javadoc for why it is one-sided. */
    static final String F_KIND = "kind";
    static final String K_CONVERSATION = "conversation";
    /** Namespace prefix on the stored document key of a conversation message. */
    static final String CONVERSATION_KEY_PREFIX = "conv:";

    /** Which table a hit came from. */
    public enum Scope { CHANNEL, CONVERSATION }

    /** One search result: the table it came from plus that table's primary key. */
    public record Hit(Scope scope, Long id) {}

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

    private Document toConversationDoc(Long messageId, Long conversationId, String author, String body) {
        var doc = new Document();
        doc.add(new StringField(F_ID, conversationKey(messageId), Field.Store.YES));
        doc.add(new StringField(F_CONVERSATION, conversationId.toString(), Field.Store.NO));
        doc.add(new StringField(F_KIND, K_CONVERSATION, Field.Store.NO));
        doc.add(new StringField(F_AUTHOR, normalizeAuthor(author), Field.Store.NO));
        doc.add(new TextField(F_BODY, body == null ? "" : body, Field.Store.NO));
        return doc;
    }

    static String conversationKey(Long messageId) {
        return CONVERSATION_KEY_PREFIX + messageId;
    }

    private static Term conversationTerm(Long messageId) {
        return new Term(F_ID, conversationKey(messageId));
    }

    private static String normalizeAuthor(String author) {
        return author == null ? "" : author.toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------ conversation writes ----

    /** Add or replace the document for a single conversation (DM / group) message. */
    public void indexConversationMessage(Long messageId, Long conversationId, String author, String body) {
        try {
            writer.updateDocument(conversationTerm(messageId),
                    toConversationDoc(messageId, conversationId, author, body));
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to index conversation message " + messageId, e);
        }
    }

    /** Remove a single conversation message from the index. */
    public void deleteConversationMessage(Long messageId) {
        try {
            writer.deleteDocuments(conversationTerm(messageId));
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete conversation message " + messageId + " from index", e);
        }
    }

    /** Remove a batch of conversation messages from the index in one commit. */
    public void deleteAllConversationMessages(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        var terms = messageIds.stream()
                .map(MessageIndexService::conversationTerm)
                .toArray(Term[]::new);
        try {
            writer.deleteDocuments(terms);
            afterWrite();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete " + messageIds.size() + " conversation messages from index", e);
        }
    }

    /** Add/replace many conversation documents in a single commit — the reconcile/backfill path. */
    public void reindexConversations(Collection<IndexedConversationMessage> rows) {
        if (rows.isEmpty()) return;
        try {
            for (var row : rows) {
                writer.updateDocument(conversationTerm(row.id()),
                        toConversationDoc(row.id(), row.conversationId(), row.author(), row.body()));
            }
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to reindex conversation batch", e);
        }
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
        return ids(runSearch(scoped, limit));
    }

    /**
     * Search a single conversation's messages, returning conversation-message IDs in relevance
     * order. The caller must have already established that the viewer is a member — this method
     * scopes, it does not authorize.
     */
    public List<Long> searchInConversation(Long conversationId, String query,
                                           Collection<String> authors, int limit) {
        var main = mainQuery(query, authors);
        if (main == null) return List.of();
        var scoped = new BooleanQuery.Builder()
                .add(main, BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(F_CONVERSATION, conversationId.toString())),
                        BooleanClause.Occur.FILTER)
                .build();
        return ids(runSearch(scoped, limit));
    }

    /**
     * The viewer-scoped search: everything they can read, channels and conversations together,
     * ranked as one result list.
     *
     * <h2>The ACL is part of the query, not a post-filter</h2>
     * The membership sets are combined into the Lucene query as a required {@code FILTER} clause:
     *
     * <pre>
     *   +body:…                                          (the user's query)
     *   #( channelId IN (joined…)  OR  conversationId IN (member-of…) )   (the ACL)
     * </pre>
     *
     * Nothing the viewer cannot read is ever <em>collected</em>, so it cannot influence the score
     * distribution, the {@code TopDocs} cut-off, the total hit count, the pagination window or the
     * snippets that get highlighted. Running an unrestricted search and dropping forbidden rows
     * from the result list afterwards leaks through every one of those channels — a non-member can
     * infer that a term occurs in a private DM from a result page that comes back one row short,
     * or from a hit count that doesn't match what they were shown. It also silently degrades:
     * post-filtering a top-50 can return zero rows for a query with 50 accessible matches ranked
     * 51st onward.
     *
     * <h2>Why {@link TermInSetQuery}, and what happens when the id set is big</h2>
     * "Conversations I belong to" is small for a typical user (tens), but it is unbounded in
     * principle — a long-lived account, a bot, or a support agent can accumulate thousands of
     * group conversations, and the joined-channel set has the same shape. That rules out the
     * obvious encoding, a {@code BooleanQuery} with one {@code SHOULD TermQuery} per id: Lucene
     * caps a BooleanQuery at {@link org.apache.lucene.search.IndexSearcher#getMaxClauseCount()}
     * clauses (1024 by default) and throws {@code TooManyClauses} above it. That failure is worse
     * than it looks, because the tempting fixes are all wrong — raising the cap makes the query
     * quadratic-ish in scorer bookkeeping, and truncating the id list turns a hard error into
     * <em>silently incomplete search results</em>.
     *
     * <p>{@link TermInSetQuery} exists for exactly this: it is a single clause holding an
     * arbitrarily large, sorted term set, evaluated by seeking the term dictionary once per term
     * per segment into a shared bitset. It has no clause limit, so it cannot throw and cannot be
     * truncated; cost grows linearly in the number of ids and it is skipped entirely for segments
     * that contain none of them.
     *
     * <p>The considered alternative was to invert the relationship and index the member user ids
     * on every document, making the filter a single {@code TermQuery} on the viewer's id
     * regardless of how many conversations they are in. Rejected: membership then lives in the
     * index, so adding one person to a group conversation means rewriting every document in it
     * (an unbounded reindex triggered by a cheap user action), and a stale document is a
     * <em>leak</em> rather than a missing result. Here the ACL is read from the database at query
     * time, so a removed member stops matching on their very next search.
     *
     * @param channelIds      channels the viewer may read (joined + public, resolved by the caller)
     * @param conversationIds conversations the viewer is a member of
     */
    public List<Hit> searchAccessible(Collection<Long> channelIds, Collection<Long> conversationIds,
                                      String query, Collection<String> authors, int limit) {
        // No accessible container ⇒ no results. Deliberately explicit: an empty id collection must
        // never degrade into "no filter", which is how this kind of code turns into a total leak.
        if (channelIds.isEmpty() && conversationIds.isEmpty()) return List.of();
        var main = mainQuery(query, authors);
        if (main == null) return List.of();
        var acl = new BooleanQuery.Builder();
        if (!channelIds.isEmpty()) {
            acl.add(termsIn(F_CHANNEL, channelIds), BooleanClause.Occur.SHOULD);
        }
        if (!conversationIds.isEmpty()) {
            acl.add(termsIn(F_CONVERSATION, conversationIds), BooleanClause.Occur.SHOULD);
        }
        acl.setMinimumNumberShouldMatch(1);
        var scoped = new BooleanQuery.Builder()
                .add(main, BooleanClause.Occur.MUST)
                .add(acl.build(), BooleanClause.Occur.FILTER)
                .build();
        return runSearch(scoped, limit);
    }

    /**
     * Search every indexed message across every <b>channel</b>. Caller is responsible for
     * authorization (it is admin-only).
     *
     * <p>Conversations are excluded on purpose, and the exclusion is structural rather than a
     * policy the caller could forget: private messages are not workspace content, and "admin" is
     * not "member". A compliance-export feature that needs DMs should be a separate, audited
     * surface, not a query parameter on the same endpoint everybody uses.
     */
    public List<Long> searchEverywhere(String query, Collection<String> authors, int limit) {
        var main = mainQuery(query, authors);
        if (main == null) return List.of();
        var channelsOnly = new BooleanQuery.Builder()
                .add(main, BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(F_KIND, K_CONVERSATION)), BooleanClause.Occur.MUST_NOT)
                .build();
        return ids(runSearch(channelsOnly, limit));
    }

    private static TermInSetQuery termsIn(String field, Collection<Long> values) {
        return new TermInSetQuery(field, values.stream().map(v -> new BytesRef(v.toString())).toList());
    }

    private static List<Long> ids(List<Hit> hits) {
        return hits.stream().map(Hit::id).toList();
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
     * Every <b>channel</b> message id currently in the index. Used by the Lucene↔DB reconcile
     * sweep (CLEAN-3) to diff the index against the {@code messages} table.
     *
     * <p>Conversation documents are skipped: their key is prefixed, so they are simply absent from
     * this set — which is what keeps the channel reconcile from mistaking every DM document for a
     * message that has vanished from {@code messages} and deleting it.
     */
    public java.util.Set<Long> allIndexedIds() {
        return allIndexedIds(Scope.CHANNEL);
    }

    /** The conversation-document counterpart of {@link #allIndexedIds()}. */
    public java.util.Set<Long> allIndexedConversationIds() {
        return allIndexedIds(Scope.CONVERSATION);
    }

    private java.util.Set<Long> allIndexedIds(Scope scope) {
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
                    var hit = parseKey(doc.get(F_ID));
                    if (hit != null && hit.scope() == scope) {
                        ids.add(hit.id());
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
     * Replace the entire index with a fresh build from the {@code messages} table. Used by the
     * startup bootstrap when the persisted index is missing or empty. Single-threaded; intended
     * for app startup only.
     *
     * <p>{@code deleteAll()} clears <b>conversation</b> documents too, so this must run before the
     * conversation backfill, never after it — {@code LuceneBootstrap} orders them that way.
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

    private List<Hit> runSearch(Query query, int limit) {
        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                var top = searcher.search(query, Math.max(limit, 1));
                var hits = new ArrayList<Hit>(top.scoreDocs.length);
                var storedFields = searcher.storedFields();
                for (var scoreDoc : top.scoreDocs) {
                    var doc = storedFields.document(scoreDoc.doc);
                    var hit = parseKey(doc.get(F_ID));
                    if (hit != null) {
                        hits.add(hit);
                    }
                }
                return Collections.unmodifiableList(hits);
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Search failed", e);
        }
    }

    /** Decode a stored document key back into (table, primary key). {@code null} if unparseable. */
    private static Hit parseKey(String key) {
        if (key == null) return null;
        try {
            return key.startsWith(CONVERSATION_KEY_PREFIX)
                    ? new Hit(Scope.CONVERSATION,
                              Long.parseLong(key.substring(CONVERSATION_KEY_PREFIX.length())))
                    : new Hit(Scope.CHANNEL, Long.parseLong(key));
        } catch (NumberFormatException e) {
            return null;
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

    /** Minimal projection used by {@link #reindexConversations(Collection)}. */
    public record IndexedConversationMessage(Long id, Long conversationId, String author, String body) {

        /** Map flat {@code (id, conversationId, authorUsername, bodyMarkdown)} repository rows. */
        public static List<IndexedConversationMessage> fromRows(List<Object[]> rows) {
            var docs = new ArrayList<IndexedConversationMessage>(rows.size());
            for (var r : rows) {
                docs.add(new IndexedConversationMessage(
                        ((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                        (String) r[2], (String) r[3]));
            }
            return docs;
        }
    }
}
