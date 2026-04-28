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

package ai.intellistream.radiance.service;

import ai.intellistream.radiance.domain.Message;
import ai.intellistream.radiance.domain.MessageMention;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.MessageMentionRepository;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.web.dto.MentionInboxItemDto;
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
     * @username followed by 1+ word chars, underscore, dot, or hyphen.
     * Anchored on either start-of-string or a non-word boundary so ordinary
     * email addresses (foo@bar.com) don't trigger a false positive.
     */
    static final Pattern MENTION = Pattern.compile("(?:^|(?<=[\\s(\\[]))@([A-Za-z0-9_.-]{2,100})");

    private final UserRepository userRepo;
    private final MessageMentionRepository mentionRepo;

    public MentionService(UserRepository userRepo, MessageMentionRepository mentionRepo) {
        this.userRepo = userRepo;
        this.mentionRepo = mentionRepo;
    }

    /** Extract the candidate handles a body refers to (case-preserved, deduped, in input order). */
    public Set<String> extractHandles(String body) {
        if (body == null || body.isEmpty()) return Set.of();
        var out = new LinkedHashSet<String>();
        var m = MENTION.matcher(body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Replace any existing mention rows for {@code message} with rows for users that exist for the handles in its body. */
    @Transactional
    public Set<User> syncMentions(Message message) {
        mentionRepo.deleteAllByMessage(message);
        var handles = extractHandles(message.getBodyMarkdown());
        if (handles.isEmpty()) return Set.of();
        var resolved = new LinkedHashSet<User>();
        for (var h : handles) {
            userRepo.findByUsernameIgnoreCase(h).ifPresent(resolved::add);
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
}
