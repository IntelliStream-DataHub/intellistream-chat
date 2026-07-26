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
import ai.intellistream.chat.domain.NotificationLevel;
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
 *
 * <p>Also handles the three {@link Broadcast} handles — {@code @channel}, {@code @here},
 * {@code @everyone} — which address a group rather than a person. They are <b>reserved</b>: a
 * handle that names a broadcast is never resolved against {@code users.username}, even if somebody
 * has managed to register that name, because a person called "channel" cannot be allowed to
 * silently absorb everyone else's {@code @channel}.
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

    /**
     * A handle that addresses a group.
     *
     * <p>{@link #EVERYONE} is accepted as a synonym for {@link #CHANNEL} rather than rejected.
     * Slack scopes {@code @everyone} to the workspace's default channel, which this app has no
     * equivalent of — there is no "general" flag on a channel, so the rule cannot be expressed —
     * and the alternative, refusing the word outright, means the first thing a new user reaches for
     * fails. It is a synonym, and the rendered message says so: the pill carries a title
     * explaining that it notified this channel's members, so the interpretation is visible in the
     * message rather than assumed by the writer.
     */
    public enum Broadcast {
        /** Every member of the channel. */
        CHANNEL,
        /** Every member with a live connection at the moment the message is posted. */
        HERE,
        /** Synonym for {@link #CHANNEL} — see the enum javadoc. */
        EVERYONE;

        /** The handle as typed, without the {@code @}. */
        public String handle() {
            return name().toLowerCase();
        }

        /** Whose inbox this fills. {@code EVERYONE} borrows {@code CHANNEL}'s audience. */
        public Broadcast audience() {
            return this == HERE ? HERE : CHANNEL;
        }
    }

    private static final java.util.Map<String, Broadcast> BROADCAST_HANDLES = java.util.Map.of(
            "channel", Broadcast.CHANNEL,
            "here", Broadcast.HERE,
            "everyone", Broadcast.EVERYONE);

    /** The broadcast a handle names, or {@code null} for an ordinary (possibly unknown) handle. */
    public static Broadcast broadcastFor(String handle) {
        return handle == null ? null : BROADCAST_HANDLES.get(handle.toLowerCase());
    }

    /** Broadcast handles in the order the typeahead offers them. */
    public static List<Broadcast> broadcasts() {
        return List.of(Broadcast.CHANNEL, Broadcast.HERE, Broadcast.EVERYONE);
    }

    /**
     * The broadcast a set of handles triggers, or {@code null}.
     *
     * <p>One message produces at most one fan-out. A body carrying both {@code @channel} and
     * {@code @here} resolves to {@code @channel}, whose audience contains the other's — two
     * fan-outs would only mean writing the same rows twice.
     */
    static Broadcast broadcastAmong(Set<String> handles) {
        Broadcast here = null;
        for (var h : handles) {
            var b = broadcastFor(h);
            if (b == null) continue;
            if (b.audience() == Broadcast.CHANNEL) return b;
            here = b;
        }
        return here;
    }

    /**
     * Fan-out INSERT for a broadcast mention: one statement for the whole audience.
     *
     * <p>Not {@code mentionRepo.save()} in a loop. {@code MessageMention.id} is {@code IDENTITY},
     * which disables Hibernate's insert batching outright — every {@code save} is its own round
     * trip, so a 1,000-member channel would turn one message into 1,000 of them on the send path,
     * which AGENT.md is explicit is the hot path. The {@code select … from users} form exists so
     * the id list can travel as a single expanded parameter, and {@code on conflict do nothing}
     * makes the statement idempotent against a personal mention of the same user in the same body.
     */
    private static final String FAN_OUT_SQL = """
            insert into message_mentions (message_id, user_id)
            select :messageId, u.id from users u where u.id in (:userIds)
            on conflict (message_id, user_id) do nothing
            """;

    /**
     * Ids per fan-out statement. Postgres caps a statement at 65,535 bound parameters, so an
     * unchunked {@code in (…)} would break on a large enough channel; chunking also keeps the
     * statement text repetitive enough to be worth planning once.
     */
    private static final int FAN_OUT_CHUNK = 500;

    /** Parser used only to strip code spans/blocks before mention extraction (N21). */
    private static final Parser PARSER = Parser.builder()
            .extensions(java.util.List.of(TablesExtension.create(), AutolinkExtension.create()))
            .build();

    private final UserRepository userRepo;
    private final MessageMentionRepository mentionRepo;
    private final ChannelMemberRepository memberRepo;
    /** Read-only collaborator: {@code @here} means "connected right now", and this is who knows. */
    private final PresenceTracker presence;

    /**
     * Used only by the typeahead below. Field-injected rather than constructor-injected so the
     * pure-logic unit tests can build this service from mocks without a persistence unit — they
     * exercise the pattern, which is the part that has historically gone wrong.
     */
    @PersistenceContext
    private EntityManager em;

    public MentionService(UserRepository userRepo, MessageMentionRepository mentionRepo,
                          ChannelMemberRepository memberRepo, PresenceTracker presence) {
        this.userRepo = userRepo;
        this.mentionRepo = mentionRepo;
        this.memberRepo = memberRepo;
        this.presence = presence;
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
     * @return the users to <b>notify live</b>, which is what the caller puts in
     *   {@code MessageDto.mentions}. For personal mentions that is every row written. For a
     *   broadcast it is the subset that is connected: the rows are written for the whole audience
     *   (they drive the bell inbox and the per-channel badge, which are read later), but the array
     *   riding on the broadcast frame only ever feeds a toast and a chime, and a user with no live
     *   session cannot receive either. Sending all 1,000 member names to all 1,000 subscribers
     *   would be a kilobyte of names per message for the sake of people who are not there.
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
            // Reserved: @channel / @here / @everyone address the room, and must not be looked up as
            // a person even if somebody owns the name.
            if (broadcastFor(h) != null) continue;
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
        var broadcast = broadcastAmong(handles);
        if (broadcast == null || channel == null) {
            return resolved;
        }
        var notify = new LinkedHashSet<>(resolved);
        notify.addAll(fanOut(message, channel, broadcast, resolved));
        return notify;
    }

    /**
     * Write the mention rows a broadcast implies, and return the recipients who can be told about
     * it right now.
     *
     * <p>The audience is the channel's <b>membership</b>, which keeps the N2 privacy rule intact by
     * construction: every recipient is a member, so every recipient can read the channel, in a
     * PRIVATE channel as much as a public one. No filtering after the fact is needed because
     * nothing outside the membership is ever considered.
     *
     * <p>Three exclusions, each deliberate:
     * <ul>
     *   <li><b>A muted channel.</b> {@code NONE} means "nothing from this channel", and a mute with
     *       exceptions is not a mute — the same rule the client applies to toasts, applied here to
     *       the row itself, so a broadcast cannot leave a badge on a channel the user silenced.
     *       A <em>personal</em> mention still writes its row in a muted channel: it was addressed
     *       to that person by name, and the client still suppresses its toast and chime. A
     *       broadcast is addressed to nobody in particular, so a mute ends it outright.</li>
     *   <li><b>The author.</b> Nobody needs their own bell to ring for their own announcement.</li>
     *   <li><b>Rows already written</b> for a personal mention in the same body — {@code on conflict
     *       do nothing} would cover it, but there is no reason to send the ids twice.</li>
     * </ul>
     */
    private Set<User> fanOut(Message message, Channel channel,
                             Broadcast broadcast, Set<User> alreadyMentioned) {
        var authorId = message.getAuthor() == null ? null : message.getAuthor().getId();
        var already = new HashSet<Long>();
        for (var u : alreadyMentioned) already.add(u.getId());
        var recipients = new ArrayList<User>();
        var online = new LinkedHashSet<User>();
        // One query, join-fetching the users: the member list is what decides both the rows and the
        // live-notify set, and a broadcast is a deliberate, rare act rather than every message.
        for (var membership : memberRepo.findAllByChannelOrderByJoinedAtAsc(channel)) {
            var user = membership.getUser();
            if (authorId != null && authorId.equals(user.getId())) continue;
            if (membership.effectiveNotifyLevel(user.getNotifyDefault())
                    == NotificationLevel.NONE) continue;
            var connected = presence.isOnline(user.getUsername());
            if (broadcast.audience() == Broadcast.HERE && !connected) continue;
            recipients.add(user);
            if (connected) online.add(user);
        }
        var ids = new ArrayList<Long>(recipients.size());
        for (var u : recipients) {
            if (!already.contains(u.getId())) ids.add(u.getId());
        }
        insertMentionRows(message.getId(), ids);
        return online;
    }

    /** Batched fan-out insert — see {@link #FAN_OUT_SQL} for why this isn't a save() loop. */
    private void insertMentionRows(Long messageId, List<Long> userIds) {
        if (userIds.isEmpty()) return;
        // The message row (and any personal mention rows) must be in the database before a
        // statement that references them by FK and relies on ON CONFLICT seeing them.
        mentionRepo.flush();
        for (int from = 0; from < userIds.size(); from += FAN_OUT_CHUNK) {
            var chunk = userIds.subList(from, Math.min(from + FAN_OUT_CHUNK, userIds.size()));
            em.createNativeQuery(FAN_OUT_SQL)
                    .setParameter("messageId", messageId)
                    .setParameter("userIds", chunk)
                    .executeUpdate();
        }
    }

    /** Lower-cased usernames that exist (used by the renderer to highlight known handles). */
    public Set<String> resolvedUsernames(String body) {
        var handles = extractHandles(body);
        if (handles.isEmpty()) return Set.of();
        var out = new HashSet<String>();
        for (var h : handles) {
            // Reserved handles are decorated by the renderer as broadcasts, so they must not also
            // arrive here as "a known username" — see the note on the Broadcast enum.
            if (broadcastFor(h) != null) continue;
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
     *
     * <p>The broadcast handles are offered here too, with the size of the audience attached. That
     * placement is the whole notice story for {@code @channel}: the number is in front of the user
     * while they are choosing the handle, which is earlier and less dismissible than a confirmation
     * dialog after they have written the message. They lead the list when the query is a real
     * prefix of one of them ("ch" almost certainly means {@code @channel}) and trail it when the
     * query is empty, so that a bare {@code @} doesn't put a megaphone under the first Enter.
     */
    @Transactional(readOnly = true)
    public List<MentionCandidateDto> candidatesInChannel(Channel channel, String query, int limit) {
        var q = normaliseQuery(query);
        var capped = cappedLimit(limit);
        var group = broadcastCandidates(channel, q);
        var slots = Math.max(1, capped - group.size());
        var people = candidates(IN_CHANNEL, channel.getId(), q, slots, true);
        if (people.size() < slots && channel.getType() == ChannelType.PUBLIC) {
            var padded = new ArrayList<>(people);
            padded.addAll(candidates(NOT_IN_CHANNEL, channel.getId(), q, slots - people.size(), false));
            people = padded;
        }
        if (group.isEmpty()) return people;
        var out = new ArrayList<MentionCandidateDto>(group.size() + people.size());
        if (q.isEmpty()) {
            out.addAll(people);
            out.addAll(group);
        } else {
            out.addAll(group);
            out.addAll(people);
        }
        return out;
    }

    /**
     * The broadcast rows a query matches, prefix-only: {@code @cha} offers {@code @channel},
     * {@code @chan_} offers nothing. Substring matching would put {@code @channel} under a query
     * like "ann", which is nobody's intent and a bad thing to fat-finger.
     */
    private List<MentionCandidateDto> broadcastCandidates(Channel channel, String q) {
        var out = new ArrayList<MentionCandidateDto>(3);
        Integer memberCount = null;
        for (var b : broadcasts()) {
            if (!b.handle().startsWith(q)) continue;
            int count = 0;
            if (b.audience() == Broadcast.CHANNEL) {
                // One indexed COUNT, and only when a broadcast is actually on offer.
                if (memberCount == null) memberCount = (int) memberRepo.countByChannel(channel);
                count = memberCount;
            }
            out.add(MentionCandidateDto.broadcast(b.handle(), count));
        }
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
            out.add(MentionCandidateDto.user((String) r[0], (String) r[1], member,
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
