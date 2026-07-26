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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.repository.ConversationMemberRepository;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.security.PublicBadRequestException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Search over the embedded Lucene index, across both message stores: channels and conversations
 * (direct messages + group conversations).
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>{@link #searchChannel} — requires read access to the channel (public or member).</li>
 *   <li>{@link #searchConversation} — requires membership of that conversation.</li>
 *   <li>{@link #searchAccessible} — everything the viewer can read: every PUBLIC channel, the
 *       PRIVATE ones they joined, and the conversations they belong to, ranked as one list.</li>
 *   <li>{@link #searchEverywhere} — every channel <em>including private ones the admin has not
 *       joined</em>, and no conversations; admin only (Spring authority {@code ROLE_ADMIN}).
 *       Public channels being in the default scope is what leaves this tier with exactly one
 *       thing to add, and it is the sensitive one.</li>
 * </ul>
 *
 * <h2>Query syntax</h2>
 * Parsed by {@link #parsed}, which is where the meaning of each token is decided:
 * <ul>
 *   <li>{@code from:bob} / {@code from:@bob} — written by bob.</li>
 *   <li>{@code @bob} — <em>mentions</em> bob. Not the same question as the line above, and the
 *       opposite of what this token used to mean here.</li>
 *   <li>{@code in:#general} — one channel, resolved to an id the viewer may read; an unknown or
 *       unreadable name is a {@link PublicBadRequestException}, never a silently dropped filter.</li>
 *   <li>Anything else, including an unrecognised {@code word:} prefix, is free text — matched
 *       against the message body <em>and</em> against the names of the files it shared, so
 *       {@code quarterly-report.pdf} finds the message that posted it even when nobody typed the
 *       name. See {@code MessageIndexService} for the field and its analysis.</li>
 * </ul>
 * {@code before:} / {@code after:} / {@code has:} are not implemented and are therefore searched
 * for as literal text like any other unknown prefix.
 *
 * <h2>Where access control happens</h2>
 * For the scoped searches it happens here, before the query runs. For {@link #searchAccessible}
 * it happens <em>inside</em> the Lucene query: the viewer's channel and conversation ids become a
 * required filter clause, so a document they may not read is never scored, never counted, never
 * ranked and never highlighted. Nothing in this class filters a result list after the fact, and
 * nothing should start to — see {@code MessageIndexService.searchAccessible} for why the
 * distinction is load-bearing rather than stylistic.
 */
@Service
public class SearchService {

    /**
     * Most results one request may ask for. Unchanged in value, deliberate in meaning: this is a
     * <em>page</em> size now, not a ceiling on how much of a result set is reachable. Reaching
     * further is {@link #searchPage}'s job, and it is bounded separately by
     * {@link MessageIndexService#MAX_WINDOW} — one cap on the work a single query does, one on how
     * deep the sequence of queries can go. Collapsing them into "raise the number" is how a search
     * endpoint becomes a way to ask for 100,000 rows.
     */
    static final int MAX_PAGE_SIZE = 100;
    /** What the results page asks for when nothing else says otherwise. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final String FROM_PREFIX = "from:";
    private static final String IN_PREFIX = "in:";
    /** What may follow a bare {@code @} for the token to read as a handle rather than as text.
     *  Underscored / hyphenated / dotted usernames are honoured (matches
     *  {@link ai.intellistream.chat.service.UserService#SAFE_USERNAME}). */
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9._-]+");

    private final MessageRepository messageRepository;
    private final ChannelMemberRepository memberRepository;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final MessageIndexService messageIndex;
    private final ConversationMessageRepository conversationMessageRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final ConversationService conversationService;

    public SearchService(MessageRepository messageRepository,
                         ChannelMemberRepository memberRepository,
                         ChannelRepository channelRepository,
                         ChannelService channelService,
                         MessageIndexService messageIndex,
                         ConversationMessageRepository conversationMessageRepository,
                         ConversationMemberRepository conversationMemberRepository,
                         ConversationService conversationService) {
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.channelRepository = channelRepository;
        this.channelService = channelService;
        this.messageIndex = messageIndex;
        this.conversationMessageRepository = conversationMessageRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.conversationService = conversationService;
    }

    /** One result, from whichever store it came from. */
    public sealed interface SearchHit {
        /**
         * @param joined whether the viewer is a member of the channel this hit came from. False is
         *   an ordinary outcome now that search spans every public channel, and the UI has to say
         *   so: a result from a room you have never opened, presented like any other, reads as a
         *   room you are in — and then the missing composer looks like a bug rather than a state.
         */
        record ChannelHit(Message message, boolean joined) implements SearchHit {}
        record ConversationHit(ConversationMessage message) implements SearchHit {}
    }

    /**
     * Which corpus a search covers. Deliberately the same four options the results page offers as
     * a control, so "what the user picked" and "what the service was asked for" are one vocabulary
     * rather than a mapping somebody has to keep in sync.
     */
    public enum ScopeKind { CHANNEL, CONVERSATION, ACCESSIBLE, EVERYWHERE }

    /**
     * One page of results, with enough about the whole set to draw a count and a pager.
     *
     * @param total             matching messages, subject to {@code totalIsLowerBound}
     * @param totalIsLowerBound the count is a floor, not a number — see
     *   {@link MessageIndexService.Page}. The UI must say "1,000+" rather than pick a side.
     */
    public record ResultPage(List<SearchHit> hits, long total, boolean totalIsLowerBound,
                             int page, int pageSize) {

        public static ResultPage empty(int page, int pageSize) {
            return new ResultPage(List.of(), 0L, false, page, pageSize);
        }

        /** True when a further page exists and is reachable without exceeding the paging window. */
        public boolean hasNext() {
            return (long) (page + 1) * pageSize < Math.min(total, MessageIndexService.MAX_WINDOW);
        }

        public boolean hasPrevious() {
            return page > 0;
        }

        /**
         * True when there are more matches than paging can reach. Worth saying out loud rather
         * than just stopping: a pager that runs out while the count still climbs looks broken,
         * whereas "narrow your search" is an instruction.
         */
        public boolean windowExhausted() {
            return !hasNext() && total > MessageIndexService.MAX_WINDOW;
        }

        /** One-based index of the first result on this page, for "showing 21–40 of 240". */
        public long firstResultNumber() {
            return hits.isEmpty() ? 0 : (long) page * pageSize + 1;
        }

        public long lastResultNumber() {
            return hits.isEmpty() ? 0 : firstResultNumber() + hits.size() - 1;
        }
    }

    @Transactional(readOnly = true)
    public List<Message> searchChannel(Channel channel, User viewer, String query, int limit) {
        return channelMessagesOf(
                searchPage(viewer, query, ScopeKind.CHANNEL, channel, null, 0, limit));
    }

    /**
     * Search one conversation. Membership is required — unlike a PUBLIC channel there is no
     * "anyone may read" tier for a DM or a group conversation, so this is the only check and it
     * is unconditional.
     */
    @Transactional(readOnly = true)
    public List<ConversationMessage> searchConversation(Conversation conversation, User viewer,
                                                        String query, int limit) {
        return searchPage(viewer, query, ScopeKind.CONVERSATION, null, conversation, 0, limit)
                .hits().stream()
                .filter(h -> h instanceof SearchHit.ConversationHit)
                .map(h -> ((SearchHit.ConversationHit) h).message())
                .toList();
    }

    /**
     * Search everything the viewer can read — every PUBLIC channel, the PRIVATE ones they have
     * joined, and the conversations they are a member of — as a single relevance-ranked list.
     * This is what the global search box calls.
     *
     * <h2>Why public channels they never joined are in scope</h2>
     * {@link ChannelService#requireMember} short-circuits for PUBLIC channels: any signed-in user
     * may open one, read its history and download its attachments. This method used to build its
     * filter from joined channels alone, so the one surface that could <em>find</em> that content
     * was the one that pretended it wasn't there — you could read every word of #incidents and
     * search would tell you the word "outage" appears nowhere in the workspace. Slack searches
     * every public channel for the same reason: in a workspace of any size, search is how you
     * discover a channel exists at all, and that is truer here now that the sidebar lists only
     * what you have joined.
     *
     * <p>PRIVATE channels the viewer has not joined stay out, which is the whole distinction
     * between the two channel types and the one this widening must not blur.
     *
     * <p>Every id set is read from the database on each call rather than cached anywhere: they are
     * the ACL, and a search must reflect a membership — or a channel — that changed a second ago.
     */
    @Transactional(readOnly = true)
    public List<SearchHit> searchAccessible(User viewer, String query, int limit) {
        return searchPage(viewer, query, ScopeKind.ACCESSIBLE, null, null, 0, limit).hits();
    }

    /** Channel ids the viewer belongs to. */
    @Transactional(readOnly = true)
    public Set<Long> joinedChannelIds(User viewer) {
        return memberRepository.findChannelsForUser(viewer).stream()
                .map(Channel::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Joined ∪ every PUBLIC channel — the read rule of {@link ChannelService#requireMember}, as ids. */
    private List<Long> readableChannelIds(Set<Long> joinedIds) {
        var ids = new LinkedHashSet<>(joinedIds);
        ids.addAll(channelRepository.findIdsByType(ChannelType.PUBLIC));
        return List.copyOf(ids);
    }

    /**
     * Search every message in every channel, <b>including private channels the admin is not a
     * member of</b>. Only available to platform admins (Keycloak realm role {@code ichat-admin} →
     * Spring authority {@code ROLE_ADMIN}).
     *
     * <p>Now that {@link #searchAccessible} covers every public channel, private channels are the
     * only thing this tier adds — which is an argument for keeping it, not for dropping it, but it
     * does change what the scope is <em>for</em>. It was "search the whole workspace"; it is now
     * "read rooms you were not invited to", and anything describing it to a user should say so.
     *
     * <p>Direct and group conversations are <b>not</b> included, at any role. The index-level query
     * excludes them structurally.
     */
    @Transactional(readOnly = true)
    public List<Message> searchEverywhere(User viewer, String query, int limit) {
        return channelMessagesOf(
                searchPage(viewer, query, ScopeKind.EVERYWHERE, null, null, 0, limit));
    }

    /**
     * One page of results for any scope — the single implementation every search goes through, and
     * what the {@code /search} page calls.
     *
     * <p>The four methods above are thin wrappers over this one rather than parallel
     * implementations, because they and the results page differ only in how much of the same result
     * set they ask for. Two code paths would have meant two places to keep the {@code in:} handling,
     * the access checks and the ACL filter correct, and the second one would be the one nobody looks
     * at.
     *
     * @param scope        which corpus; the same four options the results page offers
     * @param channel      required for {@link ScopeKind#CHANNEL}, ignored otherwise
     * @param conversation required for {@link ScopeKind#CONVERSATION}, ignored otherwise
     * @param page         zero-based page number
     * @param pageSize     results per page, clamped to {@link #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public ResultPage searchPage(User viewer, String query, ScopeKind scope,
                                 Channel channel, Conversation conversation,
                                 int page, int pageSize) {
        int size = clamp(pageSize);
        int pageNumber = Math.max(page, 0);
        int offset = pageNumber * size;
        // Access checks happen before the query is even parsed, so a malformed query can never be
        // the reason an unauthorised search "succeeded" with empty results.
        switch (scope) {
            case CHANNEL -> channelService.requireMember(channel, viewer);
            case CONVERSATION -> conversationService.requireMember(conversation, viewer);
            case EVERYWHERE -> {
                if (!isPlatformAdmin()) {
                    throw new AccessDeniedException("Admin role required for cross-channel search.");
                }
            }
            case ACCESSIBLE -> { /* the ACL is the filter; nothing to check up front */ }
        }
        var p = parsed(query);
        if (p == null) return ResultPage.empty(pageNumber, size);
        // Joined is read separately from readable, not derived from it: it is what marks a hit as
        // coming from a room the viewer has never opened, and that has to be true per channel.
        Set<Long> joinedIds = joinedChannelIds(viewer);
        var raw = switch (scope) {
            case CHANNEL -> channelScopedPage(channel, viewer, p, offset, size);
            case CONVERSATION -> conversationScopedPage(conversation, p, offset, size);
            case ACCESSIBLE -> accessiblePage(viewer, p, joinedIds, offset, size);
            case EVERYWHERE -> everywherePage(viewer, p, offset, size);
        };
        return new ResultPage(resolveHits(raw.hits(), joinedIds), raw.totalHits(),
                raw.totalIsLowerBound(), pageNumber, size);
    }

    private MessageIndexService.Page channelScopedPage(Channel channel, User viewer, Parsed p,
                                                       int offset, int size) {
        // An in: that names this channel is redundant; one that names another is a contradiction,
        // and an empty result is the honest answer to a query that asks for two scopes at once.
        // Resolving it either way still rejects a channel the viewer can't read, visibly.
        if (p.inChannel() != null
                && !resolveInChannel(p.inChannel(), viewer, true).equals(channel.getId())) {
            return MessageIndexService.Page.EMPTY;
        }
        return messageIndex.searchInChannelPage(
                channel.getId(), p.body(), p.authors(), p.mentions(), offset, size);
    }

    private MessageIndexService.Page conversationScopedPage(Conversation conversation, Parsed p,
                                                            int offset, int size) {
        // in: names a channel; a conversation is not one, so the two scopes cannot both hold.
        if (p.inChannel() != null) return MessageIndexService.Page.EMPTY;
        return messageIndex.searchInConversationPage(
                conversation.getId(), p.body(), p.authors(), p.mentions(), offset, size);
    }

    private MessageIndexService.Page accessiblePage(User viewer, Parsed p, Set<Long> joinedIds,
                                                    int offset, int size) {
        List<Long> channelIds;
        List<Long> conversationIds;
        if (p.inChannel() != null) {
            // in: narrows, and it can only narrow: the id it resolves to has already been checked
            // against the viewer's read rules, so the filter handed to Lucene is still a subset of
            // what they may see. Conversations drop out — in: names a channel.
            channelIds = List.of(resolveInChannel(p.inChannel(), viewer, true));
            conversationIds = List.of();
        } else {
            channelIds = readableChannelIds(joinedIds);
            conversationIds = conversationMemberRepository.findConversationIdsForUser(viewer);
        }
        if (channelIds.isEmpty() && conversationIds.isEmpty()) {
            return MessageIndexService.Page.EMPTY;
        }
        return messageIndex.searchAccessiblePage(channelIds, conversationIds,
                p.body(), p.authors(), p.mentions(), offset, size);
    }

    private MessageIndexService.Page everywherePage(User viewer, Parsed p, int offset, int size) {
        if (p.inChannel() != null) {
            // No read check: this scope reads every channel by definition, so requiring membership
            // of the named one would reject exactly the channels the scope exists to reach.
            return messageIndex.searchInChannelPage(resolveInChannel(p.inChannel(), viewer, false),
                    p.body(), p.authors(), p.mentions(), offset, size);
        }
        return messageIndex.searchEverywherePage(p.body(), p.authors(), p.mentions(), offset, size);
    }

    private static List<Message> channelMessagesOf(ResultPage page) {
        return page.hits().stream()
                .filter(h -> h instanceof SearchHit.ChannelHit)
                .map(h -> ((SearchHit.ChannelHit) h).message())
                .toList();
    }

    /**
     * The display label for a conversation hit: the group's title, or for a DM the other
     * participant. Resolved for a whole result page in one query.
     *
     * <p>Takes conversations, not ids, and is only ever fed the ones a search already returned to
     * this viewer. It does no membership check of its own and must not become a general lookup —
     * called with arbitrary ids it would happily name the participants of a stranger's DM.
     *
     * @return conversation id → label, absent when nothing better than the id is available
     */
    @Transactional(readOnly = true)
    public Map<Long, String> conversationLabels(User viewer, Collection<Conversation> conversations) {
        if (conversations.isEmpty()) return Map.of();
        var labels = new java.util.HashMap<Long, String>();
        var directIds = new java.util.ArrayList<Long>();
        for (var c : conversations) {
            if (c.getType() == ConversationType.GROUP) {
                if (c.getTitle() != null) labels.put(c.getId(), c.getTitle());
            } else {
                directIds.add(c.getId());
            }
        }
        if (!directIds.isEmpty()) {
            for (var row : conversationMemberRepository.findCounterparts(directIds, viewer.getId())) {
                var convId = (Long) row[0];
                var username = (String) row[1];
                var displayName = (String) row[2];
                labels.putIfAbsent(convId, displayName == null || displayName.isBlank()
                        ? username : displayName);
            }
        }
        return labels;
    }

    /**
     * The live attachment filenames of a result page's messages, so the page can say which file a
     * hit matched on.
     *
     * <p>Read from Postgres rather than stored in the index. The index needs the filenames to
     * <em>match</em>, not to display, and a stored copy would be a second answer to "what is this
     * file called" that a rename or a tombstone could leave disagreeing with the rows — the sort of
     * disagreement that shows up as a search result offering a file that is not there.
     *
     * <p>Two lookups, keyed separately, because the two tables' id sequences are independent:
     * message 42 of a channel and message 42 of a DM are different messages.
     */
    @Transactional(readOnly = true)
    public HitFilenames attachmentFilenames(List<SearchHit> hits) {
        if (hits.isEmpty()) return HitFilenames.EMPTY;
        var channelIds = new java.util.ArrayList<Long>();
        var conversationIds = new java.util.ArrayList<Long>();
        for (var hit : hits) {
            if (hit instanceof SearchHit.ChannelHit c) channelIds.add(c.message().getId());
            else if (hit instanceof SearchHit.ConversationHit c) conversationIds.add(c.message().getId());
        }
        return new HitFilenames(
                channelIds.isEmpty() ? Map.of()
                        : MessageIndexService.groupFilenames(
                                messageRepository.findIndexFilenamesByIds(channelIds)),
                conversationIds.isEmpty() ? Map.of()
                        : MessageIndexService.groupFilenames(
                                conversationMessageRepository.findIndexFilenamesByIds(conversationIds)));
    }

    /** Per-store filename lookups for one result page. @see #attachmentFilenames */
    public record HitFilenames(Map<Long, List<String>> channel, Map<Long, List<String>> conversation) {

        public static final HitFilenames EMPTY = new HitFilenames(Map.of(), Map.of());

        /** The filenames on this hit's message, in upload order; empty when it carries none. */
        public List<String> of(SearchHit hit) {
            return switch (hit) {
                case SearchHit.ChannelHit c -> channel.getOrDefault(c.message().getId(), List.of());
                case SearchHit.ConversationHit c ->
                        conversation.getOrDefault(c.message().getId(), List.of());
            };
        }
    }

    /**
     * Resolve Lucene hit IDs to {@link Message} entities while preserving Lucene's relevance order.
     * Drops any IDs the DB no longer has (defensive — should be rare).
     */
    private List<Message> resolve(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        // Join-fetch the author so MessageDto.from(...) in the controller doesn't trip
        // a LazyInitializationException once this @Transactional scope closes
        // (open-in-view is off).
        Map<Long, Message> byId = messageRepository.findAllByIdWithAuthor(orderedIds).stream()
                .collect(Collectors.toMap(Message::getId, Function.identity()));
        return orderedIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<ConversationMessage> resolveConversation(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ConversationMessage> byId =
                conversationMessageRepository.findAllByIdWithAuthor(orderedIds).stream()
                        .collect(Collectors.toMap(ConversationMessage::getId, Function.identity()));
        return orderedIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Hydrate a mixed result page: two batched lookups, then re-interleave in Lucene's order.
     *
     * <p>This is hydration only. It must never be given the job of deciding what the viewer may
     * see — by the time ids get here the filtering has already happened, in the query.
     */
    private List<SearchHit> resolveHits(List<MessageIndexService.Hit> hits, Set<Long> joinedIds) {
        if (hits.isEmpty()) {
            return List.of();
        }
        var channelIds = hits.stream()
                .filter(h -> h.scope() == MessageIndexService.Scope.CHANNEL)
                .map(MessageIndexService.Hit::id)
                .toList();
        var conversationIds = hits.stream()
                .filter(h -> h.scope() == MessageIndexService.Scope.CONVERSATION)
                .map(MessageIndexService.Hit::id)
                .toList();
        Map<Long, Message> channelRows = channelIds.isEmpty() ? Map.of()
                : messageRepository.findAllByIdWithAuthor(channelIds).stream()
                        .collect(Collectors.toMap(Message::getId, Function.identity()));
        Map<Long, ConversationMessage> conversationRows = conversationIds.isEmpty() ? Map.of()
                : conversationMessageRepository.findAllByIdWithAuthor(conversationIds).stream()
                        .collect(Collectors.toMap(ConversationMessage::getId, Function.identity()));
        var out = new java.util.ArrayList<SearchHit>(hits.size());
        for (var hit : hits) {
            if (hit.scope() == MessageIndexService.Scope.CHANNEL) {
                var row = channelRows.get(hit.id());
                if (row != null) {
                    out.add(new SearchHit.ChannelHit(row, joinedIds.contains(row.getChannel().getId())));
                }
            } else {
                var row = conversationRows.get(hit.id());
                if (row != null) out.add(new SearchHit.ConversationHit(row));
            }
        }
        return List.copyOf(out);
    }

    /**
     * One user-supplied query, split into the parts the index takes separately.
     *
     * @param body      what is left after the modifiers are removed, handed to the Lucene parser
     * @param authors   lowercased usernames from {@code from:}
     * @param mentions  lowercased handles from bare {@code @handle} tokens
     * @param inChannel the raw argument of {@code in:}, unresolved — turning a name into an id the
     *                  viewer may read needs the database and an access check, which is each search
     *                  method's job rather than the parser's
     */
    record Parsed(String body, Set<String> authors, Set<String> mentions, String inChannel) {}

    /**
     * Split a raw query into body text and modifiers.
     *
     * <h2>{@code @bob} means "mentions bob", not "written by bob"</h2>
     * It used to mean the latter, which is the exact opposite of what the same token does in
     * Slack and Mattermost — so somebody searching {@code @bob} to find where they had pinged him
     * got Bob's own messages back, with nothing on screen to say the query had been read the other
     * way round. The author filter is now spelled {@code from:bob} (or {@code from:@bob}); the
     * bare {@code @handle} filters on the mention field. Both names are borrowed rather than
     * invented, because a search syntax nobody can guess is one nobody uses.
     *
     * <h2>An unrecognised {@code foo:} is text, not a modifier</h2>
     * Lucene's own parser reads {@code foo:bar} as "field foo contains bar", so a typo'd or
     * imagined modifier used to become a query against a field that does not exist and quietly
     * matched nothing. Any prefix this method doesn't recognise has its colon escaped and is
     * searched for literally: {@code befor:friday} finds messages containing "befor" and "friday"
     * instead of silently finding none. A modifier that finds nothing is a bad day; one that
     * silently changes the result set is a wrong answer nobody checks.
     *
     * <p>Double-quoted runs are left exactly as they arrive, so {@code "from: the top"} is a
     * phrase and not a filter, and phrase/negation/boolean syntax reaches Lucene untouched.
     *
     * @return {@code null} when nothing searchable was supplied — no body keyword of 2+ characters,
     *         no {@code from:}, no {@code @handle}. An {@code in:} on its own is a scope with
     *         nothing to search for and counts as nothing; callers short-circuit to empty results.
     */
    static Parsed parsed(String raw) {
        if (raw == null) return null;
        Set<String> authors = new LinkedHashSet<>();
        Set<String> mentions = new LinkedHashSet<>();
        String inChannel = null;
        var body = new StringBuilder();
        for (var token : tokenize(raw)) {
            var lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith(FROM_PREFIX)) {
                var value = strip(token.substring(FROM_PREFIX.length()), '@');
                if (!value.isEmpty()) {
                    authors.add(value.toLowerCase(Locale.ROOT));
                    continue;
                }
            } else if (lower.startsWith(IN_PREFIX)) {
                var value = strip(token.substring(IN_PREFIX.length()), '#');
                if (!value.isEmpty()) {
                    inChannel = value; // last one wins; two in: modifiers is a contradiction anyway
                    continue;
                }
            } else if (token.length() > 1 && token.charAt(0) == '@') {
                var value = token.substring(1);
                if (HANDLE.matcher(value).matches()) {
                    mentions.add(value.toLowerCase(Locale.ROOT));
                    continue;
                }
            }
            if (!body.isEmpty()) body.append(' ');
            body.append(asBodyText(token));
        }
        var text = body.toString().trim();
        var bodyOrNull = text.length() < 2 ? null : text;
        if (bodyOrNull == null && authors.isEmpty() && mentions.isEmpty()) return null;
        return new Parsed(bodyOrNull == null ? "" : bodyOrNull, authors, mentions, inChannel);
    }

    /**
     * The part of a raw query the highlighter should mark: the body text, with every modifier
     * removed. Handing it the raw string instead makes it try to highlight {@code from:} and
     * {@code in:} as content — harmless for the field-qualified forms, which the body scorer
     * ignores, but a bare {@code @bob} used to come through the analyzer as the plain word "bob"
     * and get marked in bodies that merely happen to contain it.
     *
     * @return the body clause, or an empty string when the query has none
     */
    public static String highlightableBody(String rawQuery) {
        var p = parsed(rawQuery);
        return p == null ? "" : p.body();
    }

    /**
     * Whitespace-separated tokens, except that a double-quoted run stays one token (quotes
     * included). Without this a phrase search for {@code "from: first principles"} would lose its
     * first two words to the author filter.
     */
    static List<String> tokenize(String raw) {
        var out = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out;
    }

    /**
     * A token on its way into the body clause. Colons in an unquoted token are escaped so Lucene
     * reads them as text rather than as a field qualifier — see {@link #parsed}. Quoted runs are
     * already literal to the parser and are passed through untouched.
     */
    private static String asBodyText(String token) {
        if (token.startsWith("\"") || token.indexOf(':') < 0) return token;
        return token.replace(":", "\\:");
    }

    /** Drop one leading sigil if present, so {@code from:@bob} and {@code from:bob} agree. */
    private static String strip(String value, char sigil) {
        return !value.isEmpty() && value.charAt(0) == sigil ? value.substring(1) : value;
    }

    /**
     * Resolve an {@code in:} argument to a channel id the viewer may actually read.
     *
     * <p>Unknown and unreadable produce the <em>same</em> message on purpose. Distinguishing them
     * would turn the search box into an oracle for private channel names: type {@code in:#} and a
     * guess, and "you can't read that" versus "no such channel" tells you which private channels
     * exist. Either way it is a visible failure rather than a filter that quietly does nothing,
     * which is the whole point of resolving it here instead of dropping it.
     *
     * @param enforceRead false only for the admin-wide scope, which reads every channel by definition
     */
    private Long resolveInChannel(String name, User viewer, boolean enforceRead) {
        var channel = lookupChannel(name)
                .orElseThrow(() -> new PublicBadRequestException(unknownChannelMessage(name)));
        if (enforceRead) {
            try {
                channelService.requireMember(channel, viewer);
            } catch (AccessDeniedException e) {
                throw new PublicBadRequestException(unknownChannelMessage(name));
            }
        }
        return channel.getId();
    }

    private java.util.Optional<Channel> lookupChannel(String name) {
        var direct = channelRepository.findFirstBySlugOrNameIgnoreCase(name);
        if (direct.isPresent()) return direct;
        // "in:#product team" can't happen (whitespace splits tokens), but "in:#Product-Team" and
        // "in:#product_team" both plausibly mean the channel whose slug is product-team.
        var slugish = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slugish.equals(name) ? java.util.Optional.empty()
                : channelRepository.findFirstBySlugOrNameIgnoreCase(slugish);
    }

    private static String unknownChannelMessage(String name) {
        return "No channel called #" + name + " that you can read.";
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    }

    private static boolean isPlatformAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_ROLE.equals(a.getAuthority()));
    }
}
