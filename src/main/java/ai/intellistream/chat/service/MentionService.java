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
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.MessageMention;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.web.dto.MentionCandidateDto;
import ai.intellistream.chat.web.dto.MentionInboxItemDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses {@code @username} mentions out of a markdown body, persists matched-user
 * rows on the message, and exposes the set of recognised usernames so the renderer
 * can highlight them.
 */
@Service
public class MentionService {

    /**
     * @username: 2–100 chars, starting and ending with an alphanumeric/underscore so trailing
     * sentence punctuation isn't captured ("thanks @bob." → {@code bob}, not {@code bob.}, which
     * would resolve to nobody — N22). Anchored on start-of-string or a whitespace/paren/bracket so
     * ordinary email addresses (foo@bar.com) don't trigger a false positive.
     */
    static final Pattern MENTION = Pattern.compile(
            "(?:^|(?<=[\\s(\\[]))@([A-Za-z0-9_][A-Za-z0-9_.-]{0,98}[A-Za-z0-9_])");

    /** Parser used only to strip code spans/blocks before mention extraction (N21). */
    private static final Parser PARSER = Parser.builder()
            .extensions(java.util.List.of(TablesExtension.create(), AutolinkExtension.create()))
            .build();

    private final UserRepository userRepo;
    private final MessageMentionRepository mentionRepo;
    private final ChannelMemberRepository memberRepo;

    /**
     * Used only by the typeahead below. Field-injected rather than constructor-injected so the
     * pure-logic unit tests can build this service from mocks without a persistence unit — they
     * exercise the pattern, which is the part that has historically gone wrong.
     */
    @PersistenceContext
    private EntityManager em;

    public MentionService(UserRepository userRepo, MessageMentionRepository mentionRepo,
                          ChannelMemberRepository memberRepo) {
        this.userRepo = userRepo;
        this.mentionRepo = mentionRepo;
        this.memberRepo = memberRepo;
    }

    /** Extract the candidate handles a body refers to (case-preserved, deduped, in input order).
     *  Reads only non-code text so an {@code @user} inside inline/fenced code neither notifies nor
     *  highlights — matching the renderer, which never decorates mentions in code (N21). */
    public Set<String> extractHandles(String body) {
        if (body == null || body.isEmpty()) return Set.of();
        // Every mention starts with '@', so a body without one cannot contain a handle. Bail out
        // before nonCodeText(), which parses the whole body as Markdown just to strip code spans —
        // on the render path that was a second full CommonMark parse of every message, almost all
        // of which mention nobody.
        if (body.indexOf('@') < 0) return Set.of();
        var out = new LinkedHashSet<String>();
        var m = MENTION.matcher(nonCodeText(body));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Concatenate the markdown's plain-text (Text) nodes. Inline {@code Code} and fenced/indented
     *  code blocks hold their content as a literal, not as Text children, so they're excluded. */
    private static String nonCodeText(String markdown) {
        var sb = new StringBuilder();
        PARSER.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                sb.append(text.getLiteral()).append('\n'); // newline keeps each node start anchorable
            }
        });
        return sb.toString();
    }

    /** Replace any existing mention rows for {@code message} with rows for users that exist for the handles in its body. */
    @Transactional
    public Set<User> syncMentions(Message message) {
        return syncMentions(message, false);
    }

    /**
     * @param freshlyInserted {@code true} when the message row was created earlier in this same
     *   transaction, so it provably has no mention rows yet and the clearing DELETE + flush can be
     *   skipped. That pair is two round trips on every single post — pure waste on the hot path,
     *   since only an edit can find rows to clear.
     */
    @Transactional
    public Set<User> syncMentions(Message message, boolean freshlyInserted) {
        if (!freshlyInserted) {
            mentionRepo.deleteAllByMessage(message);
            // Flush the deletes before re-inserting: MessageMention.id is IDENTITY, so save()
            // triggers an immediate INSERT while the derived-delete above is still queued in the
            // action list. Without this, editing a message that keeps an existing mention
            // re-inserts the same (message_id, user_id) pair and trips uk_message_mentions,
            // rolling back the whole edit. (PollService.castVote flushes for the same reason.)
            mentionRepo.flush();
        }
        var handles = extractHandles(message.getBodyMarkdown());
        if (handles.isEmpty()) return Set.of();
        var resolved = new LinkedHashSet<User>();
        for (var h : handles) {
            userRepo.findByUsernameIgnoreCase(h).ifPresent(resolved::add);
        }
        // N2: never create a mention row (which the inbox/bell surface with the body snippet and
        // channel name) for a user who can't read the channel — a PRIVATE-channel mention must not
        // leak to a non-member. PUBLIC channels are readable by anyone, so no filter there. This
        // also stops the live "you were mentioned" notification (driven by the returned set).
        var channel = message.getChannel();
        if (channel != null && channel.getType() != ChannelType.PUBLIC && !resolved.isEmpty()) {
            var ids = resolved.stream().map(User::getId).toList();
            var members = new HashSet<>(memberRepo.findMemberUserIds(channel, ids));
            resolved.removeIf(u -> !members.contains(u.getId()));
        }
        for (var u : resolved) {
            mentionRepo.save(new MessageMention(message, u));
        }
        return resolved;
    }

    /** Lower-cased usernames that exist (used by the renderer to highlight known handles). */
    public Set<String> resolvedUsernames(String body) {
        var handles = extractHandles(body);
        if (handles.isEmpty()) return Set.of();
        var out = new HashSet<String>();
        for (var h : handles) {
            userRepo.findByUsernameIgnoreCase(h).ifPresent(u -> out.add(u.getUsername().toLowerCase()));
        }
        return out;
    }

    /** Total unread mentions for the topbar bell badge. */
    @Transactional(readOnly = true)
    public long unreadInboxCount(User viewer) {
        return mentionRepo.countUnreadFor(viewer.getId());
    }

    /**
     * Inbox rows for the dropdown: at most {@code limit} most-recent unread mentions, newest first.
     * "Unread" matches the per-channel badge logic — once the viewer marks a channel read, mentions
     * older than that read marker drop off the list.
     */
    @Transactional(readOnly = true)
    public List<MentionInboxItemDto> unreadInbox(User viewer, int limit) {
        var capped = Math.min(Math.max(limit, 1), 50);
        var rows = mentionRepo.findUnreadInbox(viewer.getId(), capped);
        var out = new ArrayList<MentionInboxItemDto>(rows.size());
        for (var r : rows) {
            var ts = r[7] instanceof Instant i ? i : ((java.sql.Timestamp) r[7]).toInstant();
            out.add(MentionInboxItemDto.of(
                    (Long) r[0], (Long) r[1], (String) r[2], (String) r[3],
                    (String) r[4], (String) r[5], (String) r[6], ts));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Typeahead — "who can I @-mention here"
    // ---------------------------------------------------------------------------------------

    /** Hard ceiling on a typeahead page, whatever limit the caller asks for. */
    private static final int MAX_CANDIDATES = 25;
    /** Longer than any handle the MENTION pattern can match, so nothing legitimate is truncated. */
    private static final int MAX_QUERY_LEN = 100;

    /**
     * Candidate rows, ordered so the obvious match is first: a username starting with the query,
     * then a display name starting with it, then a display name whose <em>later</em> words start
     * with it ("an" → "Alice <b>An</b>derson"), then any remaining substring hit. Ties break on
     * display name so the order is stable between keystrokes.
     *
     * <p>Ranked in SQL rather than in Java on purpose: the alternative is fetching the whole
     * membership on every keystroke and sorting it in the service, which is a table scan of a
     * 5,000-member channel per typed character. Here the database does the work and returns at
     * most {@link #MAX_CANDIDATES} rows.
     *
     * <p>An empty query is legal and means "the first few names" — typing a bare {@code @} should
     * open a list, exactly like Slack, rather than wait for a letter.
     */
    private static final String CANDIDATE_SQL = """
            select u.username,
                   u.display_name,
                   (u.avatar_storage_key is not null) as has_avatar,
                   coalesce(cast(extract(epoch from u.avatar_updated_at) * 1000 as bigint), 0) as avatar_version
              from users u
             where %s
               and (position(:q in lower(u.username)) > 0
                 or position(:q in lower(coalesce(u.display_name, ''))) > 0)
             order by case
                        when starts_with(lower(u.username), :q) then 0
                        when starts_with(lower(coalesce(u.display_name, '')), :q) then 1
                        when position(' ' || :q in lower(coalesce(u.display_name, ''))) > 0 then 2
                        else 3
                      end,
                      lower(coalesce(u.display_name, u.username)),
                      u.username
             limit :lim
            """;

    private static final String IN_CHANNEL = """
            exists (select 1 from channel_members cm
                     where cm.channel_id = :scopeId and cm.user_id = u.id)""";
    private static final String NOT_IN_CHANNEL = """
            not exists (select 1 from channel_members cm
                         where cm.channel_id = :scopeId and cm.user_id = u.id)""";
    private static final String IN_CONVERSATION = """
            exists (select 1 from conversation_members cv
                     where cv.conversation_id = :scopeId and cv.user_id = u.id)""";

    /**
     * Who the author of a message in {@code channel} can reasonably mean by {@code @query}.
     *
     * <p>Members come first and always. A {@code PUBLIC} channel then pads the remaining slots
     * with people who aren't in it yet, marked {@code member=false}: a public channel is readable
     * by the whole workspace and {@link #syncMentions} deliberately lets a mention there reach a
     * non-member, so a typeahead that hid them would be hiding something that works.
     *
     * <p>A {@code PRIVATE} channel is never padded. Answering prefix queries about people who are
     * not in the room would turn a private conversation's composer into a workspace directory,
     * which is the one thing this endpoint must not become — and the caller is authorised against
     * the channel, not against the directory.
     */
    @Transactional(readOnly = true)
    public List<MentionCandidateDto> candidatesInChannel(Channel channel, String query, int limit) {
        var q = normaliseQuery(query);
        var capped = cappedLimit(limit);
        var members = candidates(IN_CHANNEL, channel.getId(), q, capped, true);
        if (members.size() >= capped || channel.getType() != ChannelType.PUBLIC) {
            return members;
        }
        var out = new ArrayList<>(members);
        out.addAll(candidates(NOT_IN_CHANNEL, channel.getId(), q, capped - members.size(), false));
        return out;
    }

    /**
     * The same for a DM or group conversation: its participants, and nobody else. There is no
     * public tier for a conversation, so there is no padding branch here either.
     */
    @Transactional(readOnly = true)
    public List<MentionCandidateDto> candidatesInConversation(Conversation conversation, String query, int limit) {
        return candidates(IN_CONVERSATION, conversation.getId(),
                normaliseQuery(query), cappedLimit(limit), true);
    }

    private List<MentionCandidateDto> candidates(String scopeClause, Long scopeId, String q,
                                                 int limit, boolean member) {
        if (limit <= 0) return List.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(CANDIDATE_SQL.formatted(scopeClause))
                .setParameter("scopeId", scopeId)
                .setParameter("q", q)
                .setParameter("lim", limit)
                .getResultList();
        var out = new ArrayList<MentionCandidateDto>(rows.size());
        for (var r : rows) {
            out.add(new MentionCandidateDto((String) r[0], (String) r[1], member,
                    Boolean.TRUE.equals(r[2]), ((Number) r[3]).longValue()));
        }
        return out;
    }

    /**
     * Lower-case, trimmed, and with a leading {@code @} dropped — the client sends the text after
     * the {@code @} but a paste can easily include it, and "@@alice" matching nobody would look
     * like the feature is broken rather than like the input was odd.
     */
    private static String normaliseQuery(String query) {
        if (query == null) return "";
        var q = query.trim();
        while (q.startsWith("@")) q = q.substring(1);
        if (q.length() > MAX_QUERY_LEN) q = q.substring(0, MAX_QUERY_LEN);
        return q.toLowerCase();
    }

    private static int cappedLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_CANDIDATES);
    }
}
