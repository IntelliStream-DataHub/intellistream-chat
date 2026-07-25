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

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelMember;
import ai.intellistream.chat.domain.ChannelRole;
import java.time.Duration;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.domain.ChannelCreationPolicy;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
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
    private final AppSettingsService appSettings;
    private final RateLimiter rateLimiter;
    private final ai.intellistream.chat.moderation.StorageQuotaService quotas;

    public ChannelService(ChannelRepository channelRepository,
                          ChannelMemberRepository memberRepository,
                          MessageRepository messageRepository,
                          AttachmentRepository attachmentRepository,
                          MessageIndexService messageIndex,
                          // @Lazy breaks the ChannelService <-> AttachmentService construction cycle.
                          @Lazy AttachmentService attachmentService,
                          ChannelAccessCache accessCache,
                          AppSettingsService appSettings,
                          RateLimiter rateLimiter,
                          ai.intellistream.chat.moderation.StorageQuotaService quotas) {
        this.quotas = quotas;
        this.accessCache = accessCache;
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.messageIndex = messageIndex;
        this.attachmentService = attachmentService;
        this.appSettings = appSettings;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    /**
     * Create a channel.
     *
     * <p>Two gates, and they answer different questions. The <b>policy</b> answers "is this
     * workspace one where members may create channels at all", which an admin sets and which
     * mirrors how Slack and Mattermost model it. The <b>rate limit</b> answers "is this account
     * behaving", and applies even under the permissive policy, because the abuse case is not a
     * member creating a channel, it is one account creating four hundred of them in a minute.
     * Neither subsumes the other: tightening the policy to admins-only should not be the only
     * defence available, and a rate limit alone would still let a spam wave create one channel per
     * account per minute indefinitely.
     */
    public Channel create(String name, String description, ChannelType type, User creator) {
        requireMayCreateChannel(creator);
        var slug = slugify(name);
        channelRepository.findBySlug(slug).ifPresent(c -> {
            throw new IllegalStateException("Channel slug already exists: " + slug);
        });
        var channel = channelRepository.save(new Channel(slug, name, description, type, creator));
        memberRepository.save(new ChannelMember(channel, creator, ChannelRole.ADMIN));
        return channel;
    }

    /**
     * Enforce the workspace policy and the per-account burst limit.
     *
     * <p>The limiter is checked <em>after</em> the policy so that a member in an admins-only
     * workspace gets told the workspace does not allow it, rather than being told to slow down
     * about something they were never permitted to do.
     */
    private void requireMayCreateChannel(User creator) {
        if (appSettings.channelCreationPolicy() == ChannelCreationPolicy.ADMINS_ONLY && !creator.isAdmin()) {
            throw new AccessDeniedException(
                    "Only workspace administrators may create channels in this workspace.");
        }
        // Deliberately modest. A person creating channels is doing it a handful of times an hour
        // at most; a script is doing it hundreds of times a minute. Ten an hour separates those
        // two populations without ever being reached by ordinary use.
        if (!rateLimiter.tryAcquire(creator.getUsername(), "channel-create", 10, Duration.ofHours(1))) {
            throw new RateLimitExceededException(
                    "Too many channels created recently. Try again later.");
        }
    }

    @Transactional(readOnly = true)
    public Channel requireById(Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Channel not found: " + id));
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
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Channel not found: " + slug));
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
        // Capture the channel's message ids + attachment rows before the delete cascades them away,
        // then purge the Lucene docs, reap the files and credit the bytes back after commit —
        // otherwise all three leak forever (the index bloats, the disk fills, and everyone who ever
        // posted a file here stays charged for it) since rebuildIfEmpty never reconciles a
        // non-empty index and there is no reconcile at all for storage usage. Mirrors
        // MessageService.delete.
        var messageIds = messageRepository.findIdsByChannel(channel);
        var doomedAttachments = attachmentRepository.findByChannelWithAuthor(channel);
        var fileKeys = doomedAttachments.stream().map(Attachment::getStorageKey).toList();
        // Unlike a single message's attachments, a channel's belong to everyone who ever posted in
        // it — hence the per-account map rather than one uploader. Read here for the usual reason:
        // after the cascade the rows naming those accounts are gone.
        // Applied inside this transaction, not after it: the delete and the refund then commit or
        // roll back together and the recorded usage can never disagree with the rows. Crediting
        // after the commit means a failed credit charges an account forever for bytes that are
        // gone, and UserStorage exposes only an atomic delta so nothing can repair it. A failed
        // file cleanup below leaves an orphan whose bytes read as free, which the orphan sweep
        // already reconciles — a recoverable inconsistency in place of an unrecoverable one.
        quotas.releaseAll(AttachmentService.creditsFor(doomedAttachments));
        channelRepository.delete(channel);
        var channelId = channel.getId();
        // Destroy is the one event that can invalidate a cached channel or a cached "may write"
        // decision — everything else about a channel is immutable and membership is add-only.
        afterCommit(() -> accessCache.evictChannel(channelId));
        afterCommit(() -> messageIndex.deleteAll(messageIds));
        afterCommit(() -> attachmentService.deleteFiles(fileKeys));
    }

    // NOTE, kept because it cost real time to find: an afterCommit hook runs while the finished
    // transaction's resources are still bound to the thread, so a plain REQUIRED database write
    // there joins a transaction that has ALREADY COMMITTED — the UPDATE is issued, nothing commits
    // it, and there is no exception and no log line. Every afterCommit hook registered above is
    // therefore Lucene, cache or filesystem work. A post-commit database write needs REQUIRES_NEW
    // and must go through the proxy.

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
