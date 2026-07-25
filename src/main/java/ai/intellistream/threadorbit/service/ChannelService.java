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

package ai.intellistream.threadorbit.service;

import ai.intellistream.threadorbit.domain.Channel;
import ai.intellistream.threadorbit.domain.ChannelMember;
import ai.intellistream.threadorbit.domain.ChannelRole;
import ai.intellistream.threadorbit.domain.ChannelType;
import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.repository.AttachmentRepository;
import ai.intellistream.threadorbit.repository.ChannelMemberRepository;
import ai.intellistream.threadorbit.repository.ChannelRepository;
import ai.intellistream.threadorbit.repository.MessageRepository;
import ai.intellistream.threadorbit.search.MessageIndexService;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final MessageIndexService messageIndex;
    private final AttachmentService attachmentService;
    private final ChannelAccessCache accessCache;

    public ChannelService(ChannelRepository channelRepository,
                          ChannelMemberRepository memberRepository,
                          MessageRepository messageRepository,
                          AttachmentRepository attachmentRepository,
                          MessageIndexService messageIndex,
                          // @Lazy breaks the ChannelService <-> AttachmentService construction cycle.
                          @Lazy AttachmentService attachmentService,
                          ChannelAccessCache accessCache) {
        this.accessCache = accessCache;
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.messageIndex = messageIndex;
        this.attachmentService = attachmentService;
    }

    @Transactional
    public Channel create(String name, String description, ChannelType type, User creator) {
        var slug = slugify(name);
        channelRepository.findBySlug(slug).ifPresent(c -> {
            throw new IllegalStateException("Channel slug already exists: " + slug);
        });
        var channel = channelRepository.save(new Channel(slug, name, description, type, creator));
        memberRepository.save(new ChannelMember(channel, creator, ChannelRole.ADMIN));
        return channel;
    }

    @Transactional(readOnly = true)
    public Channel requireById(Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new ai.intellistream.threadorbit.security.ResourceNotFoundException("Channel not found: " + id));
    }

    /**
     * Cached channel lookup for the messaging hot path (WebSocket send / typing), where the same
     * handful of channels are fetched thousands of times a second and the fetch was measurably the
     * second-largest cost of handling a message.
     *
     * <p>Returns a <b>detached</b> entity: read its own columns freely, but don't mutate it (the
     * entity has no setters, so you can't) and don't touch {@code createdBy}, which stays an
     * uninitialized lazy proxy. Anything that needs a managed instance — or the lazy association —
     * must use {@link #requireById}. See {@link ChannelAccessCache} for why caching is sound here.
     */
    public Channel requireByIdForMessaging(Long id) {
        return accessCache.channel(id, this::requireById);
    }

    /**
     * {@link #requireWriteAccess} with the membership query served from cache after the first
     * verified success. Only positives are cached, so a user who has just joined is never wrongly
     * refused; see {@link ChannelAccessCache}.
     */
    public void requireWriteAccessCached(Channel channel, User user) {
        if (channel.getId() != null && user.getId() != null
                && accessCache.hasWriteAccess(channel.getId(), user.getId())) {
            return;
        }
        requireWriteAccess(channel, user);
        if (channel.getId() != null && user.getId() != null) {
            accessCache.rememberWriteAccess(channel.getId(), user.getId());
        }
    }

    @Transactional(readOnly = true)
    public Channel requireBySlug(String slug) {
        return channelRepository.findBySlug(slug)
                .orElseThrow(() -> new ai.intellistream.threadorbit.security.ResourceNotFoundException("Channel not found: " + slug));
    }

    @Transactional(readOnly = true)
    public List<Channel> listPublic() {
        return channelRepository.findAllByTypeOrderByNameAsc(ChannelType.PUBLIC);
    }

    @Transactional(readOnly = true)
    public List<Channel> listForUser(User user) {
        return memberRepository.findChannelsForUser(user);
    }

    @Transactional(readOnly = true)
    public List<ChannelMember> members(Channel channel) {
        return memberRepository.findAllByChannelOrderByJoinedAtAsc(channel);
    }

    @Transactional
    public ChannelMember join(Channel channel, User user) {
        if (channel.getType() != ChannelType.PUBLIC) {
            throw new AccessDeniedException("Channel is private; ask an admin to invite you.");
        }
        // Insert-or-ignore then read (N1): ON CONFLICT blocks on a concurrent inserter then does
        // nothing, so a row always exists afterwards and the re-read never runs in an aborted tx
        // (the old saveAndFlush + catch-and-reread threw on Postgres because the failed INSERT
        // poisons the transaction).
        memberRepository.insertMemberIgnore(channel.getId(), user.getId());
        return memberRepository.findByChannelAndUser(channel, user).orElseThrow();
    }

    @Transactional
    public ChannelMember invite(Channel channel, User invitee, User actor) {
        // Slack / Mattermost default: any channel member can invite. Channel admins keep
        // exclusive control over promote/demote and eventual destructive actions.
        // Writes require actual membership — using requireMember here would let any
        // authenticated user force-join others into PUBLIC channels.
        requireWriteAccess(channel, actor);
        // Insert-or-ignore then read (N1) — race-free without a poisoned-tx catch block.
        memberRepository.insertMemberIgnore(channel.getId(), invitee.getId());
        return memberRepository.findByChannelAndUser(channel, invitee).orElseThrow();
    }

    @Transactional
    public void promote(Channel channel, User target, User actor) {
        requireAdmin(channel, actor);
        var membership = memberRepository.findByChannelAndUser(channel, target)
                .orElseThrow(() -> new IllegalArgumentException("User is not a member"));
        membership.setRole(ChannelRole.ADMIN);
    }

    /**
     * Strip the ADMIN role from a member, leaving them a plain MEMBER. Refuses to demote
     * the last admin — every channel must keep at least one — so the actor can't paint
     * themselves into a corner where the channel has no one who can manage it.
     */
    @Transactional
    public void demote(Channel channel, User target, User actor) {
        requireAdmin(channel, actor);
        var membership = memberRepository.findByChannelAndUser(channel, target)
                .orElseThrow(() -> new IllegalArgumentException("User is not a member"));
        if (membership.getRole() != ChannelRole.ADMIN) return;
        // Lock the channel's ADMIN rows (FOR UPDATE) before counting so two admins demoting each
        // other serialize — otherwise both read otherAdmins >= 1 and both commit, leaving the
        // channel with zero admins (TOCTOU the last-admin guard exists to prevent).
        long otherAdmins = memberRepository.findByChannelAndRoleForUpdate(channel, ChannelRole.ADMIN).stream()
                .filter(m -> !m.getUser().getId().equals(target.getId()))
                .count();
        if (otherAdmins == 0) {
            throw new IllegalStateException(
                    "Cannot demote the last admin — promote someone else first");
        }
        membership.setRole(ChannelRole.MEMBER);
    }

    @Transactional
    public void destroy(Channel channel, User actor) {
        requireAdmin(channel, actor);
        // Capture the channel's message ids + attachment storage keys before the delete cascades
        // the rows away, then purge the Lucene docs and reap the files after commit — otherwise
        // both leak forever (the index bloats, disk fills) since rebuildIfEmpty never reconciles
        // a non-empty index. Mirrors MessageService.delete.
        var messageIds = messageRepository.findIdsByChannel(channel);
        var fileKeys = attachmentRepository.findStorageKeysByChannel(channel);
        channelRepository.delete(channel);
        var channelId = channel.getId();
        // Destroy is the one event that can invalidate a cached channel or a cached "may write"
        // decision — everything else about a channel is immutable and membership is add-only.
        afterCommit(() -> accessCache.evictChannel(channelId));
        afterCommit(() -> messageIndex.deleteAll(messageIds));
        afterCommit(() -> attachmentService.deleteFiles(fileKeys));
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelService.class);

    /** Run after a successful commit; if no tx is active, run now. Body guarded so a failing index
     *  purge doesn't skip the file-cleanup hook registered after it (BUG-21). */
    private static void afterCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Post-commit channel cleanup (index / files) failed", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }

    @Transactional(readOnly = true)
    public boolean isMember(Channel channel, User user) {
        return memberRepository.existsByChannelAndUser(channel, user);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(Channel channel, User user) {
        return memberRepository.findByChannelAndUser(channel, user)
                .map(m -> m.getRole() == ChannelRole.ADMIN)
                .orElse(false);
    }

    /**
     * Read access. PUBLIC channels are readable by any authenticated user — the listing
     * endpoint exposes their existence to everyone, so it would be inconsistent to gate
     * message reads on membership. PRIVATE channels still require actual membership.
     */
    public void requireMember(Channel channel, User user) {
        if (channel.getType() == ChannelType.PUBLIC) {
            return;
        }
        if (!isMember(channel, user)) {
            throw new AccessDeniedException("Not a member of this channel.");
        }
    }

    /**
     * Write access. Always requires actual membership, regardless of channel type. A
     * non-member can still see a public channel's messages (via {@link #requireMember}),
     * but posting / editing / reacting / inviting requires them to first join. This
     * matches the Slack / Mattermost convention and the way the sidebar's "Join" button
     * is presented to non-members.
     */
    public void requireWriteAccess(Channel channel, User user) {
        if (!isMember(channel, user)) {
            throw new AccessDeniedException("Join the channel before posting.");
        }
    }

    public void requireAdmin(Channel channel, User user) {
        if (!isAdmin(channel, user)) {
            throw new AccessDeniedException("Channel admin role required.");
        }
    }

    private static String slugify(String input) {
        // Locale.ROOT — under tr_TR the bare toLowerCase() turns "I" into dotless "ı",
        // which then doesn't match [a-z] and produces a slug with stripped letters.
        var lower = input == null ? "" : input.toLowerCase(java.util.Locale.ROOT);
        var slug = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("Channel name must contain alphanumeric characters");
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80);
            // Truncation may have left a trailing '-' (e.g. cut mid-separator); strip it
            // so URLs stay clean and the unique-key matches what the user sees.
            slug = slug.replaceAll("-+$", "");
        }
        return slug;
    }
}
