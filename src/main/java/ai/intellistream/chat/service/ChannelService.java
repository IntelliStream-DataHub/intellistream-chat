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
    /**
     * Optional because the realtime layer is optional: the integration-test context scans service
     * and repository only, so there is no broker there to revoke a subscription on. An
     * {@code ObjectProvider} also keeps this out of the messaging beans' construction order, which
     * runs back through {@code StompAuthorizationConfig} into this class.
     */
    private final org.springframework.beans.factory.ObjectProvider<ChannelSubscriptionRevoker>
            subscriptionRevoker;

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
                          ai.intellistream.chat.moderation.StorageQuotaService quotas,
                          org.springframework.beans.factory.ObjectProvider<ChannelSubscriptionRevoker>
                                  subscriptionRevoker) {
        this.subscriptionRevoker = subscriptionRevoker;
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
        // BEFORE the cache short-circuit, and that ordering is the whole correctness of it. The
        // cache remembers "this member may write here", which stays true across an archive — what
        // changes is that nobody may. A cached positive would otherwise sail straight past the check
        // in requireWriteAccess and let the busiest members keep posting into an archived channel
        // for the rest of the TTL, which is exactly the population whose entries are warm.
        requireNotArchived(channel);
        if (channel.getId() != null && user.getId() != null
                && accessCache.hasWriteAccess(channel.getId(), user.getId())) {
            return;
        }
        requireWriteAccess(channel, user);
        if (channel.getId() != null && user.getId() != null) {
            accessCache.rememberWriteAccess(channel.getId(), user.getId());
        }
    }

    /**
     * {@link #requireMember} with the membership query served from cache after the first verified
     * success — the STOMP SUBSCRIBE authorization path.
     *
     * <p>Worth having because subscription count is now membership count: the client subscribes to
     * every channel the user is in, so a user in 200 private channels used to mean 200 uncached
     * {@code exists} queries on the threads that are accepting connections, and a mass reconnect
     * multiplies that by every client at once. PUBLIC channels never cost anything here —
     * {@link #requireMember} short-circuits before any query — so this only changes the private
     * case, where membership and write access are the same question and therefore the same cache
     * entry.
     *
     * <p>Only positives are cached, so a user who has just been invited is never wrongly refused.
     * A user whose membership is <em>removed</em> is a different matter: that is what
     * {@code ChannelAccessCache.evictMember} is for, and every leave/kick path must call it.
     */
    public void requireMemberCached(Channel channel, User user) {
        if (channel.getType() == ChannelType.PUBLIC) {
            return;
        }
        requireWriteAccessCached(channel, user);
    }

    @Transactional(readOnly = true)
    public Channel requireBySlug(String slug) {
        return channelRepository.findBySlug(slug)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Channel not found: " + slug));
    }

    /** Public channels anyone may join. Archived ones are not among them — nobody may join those. */
    @Transactional(readOnly = true)
    public List<Channel> listPublic() {
        return channelRepository.findAllByTypeAndArchivedAtIsNullOrderByNameAsc(ChannelType.PUBLIC);
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
        // Joining is the one write that cannot go through requireWriteAccess, since the whole point
        // is that you are not a member yet — so the archived check is spelled out here. Without it
        // an archived channel would still be joinable, which would put it back in the joiner's
        // sidebar and hand them a membership in something they can do nothing with.
        requireNotArchived(channel);
        // Insert-or-ignore then read (N1): ON CONFLICT blocks on a concurrent inserter then does
        // nothing, so a row always exists afterwards and the re-read never runs in an aborted tx
        // (the old saveAndFlush + catch-and-reread threw on Postgres because the failed INSERT
        // poisons the transaction).
        memberRepository.insertMemberIgnore(channel.getId(), user.getId());
        var membership = memberRepository.findByChannelAndUser(channel, user).orElseThrow();
        // A channel can now be emptied — everybody leaves — and an empty channel has no admin and
        // nothing that could ever promote one, so it would be permanently unmanageable: no
        // promote, no destroy. The first person back in becomes its admin, exactly as the creator
        // did. Two simultaneous joiners can both end up admin, since an empty result set locks
        // nothing; two admins is a harmless outcome and zero is not.
        if (memberRepository.findByChannelAndRoleForUpdate(channel, ChannelRole.ADMIN).isEmpty()) {
            membership.setRole(ChannelRole.ADMIN);
        }
        return membership;
    }

    /**
     * Leave a channel.
     *
     * <p>The messages stay. You are leaving, not deleting — and for a PRIVATE channel the leaving is
     * irreversible from your side, since coming back needs an invitation. The UI warns about that;
     * the service does not refuse it, because a channel you cannot leave is a worse trap than one
     * you cannot re-enter.
     *
     * <p>The read marker in {@code channel_reads} stays too. It is a fact about what you have seen,
     * not part of the membership, and deleting it would mean rejoining dumps the entire backlog on
     * you as unread.
     */
    @Transactional
    public void leave(Channel channel, User user) {
        removeMembership(channel, user);
    }

    /**
     * Remove another member — a channel admin's kick.
     *
     * <p>Self-removal is routed to {@link #leave} rather than refused: {@code DELETE
     * /members/{me}} plainly means "take me out", and requiring admin for it would stop a plain
     * member using the one endpoint that names them.
     */
    @Transactional
    public void removeMember(Channel channel, User target, User actor) {
        if (actor.getId().equals(target.getId())) {
            removeMembership(channel, actor);
            return;
        }
        requireAdmin(channel, actor);
        removeMembership(channel, target);
    }

    /**
     * Delete one membership row, handing over the channel first if that row was the last admin.
     *
     * <p><b>The last-admin rule: allow the departure, promote a successor.</b> The three options were
     * to refuse, to allow and leave the channel adminless, or to hand over. Refusing traps the last
     * admin in a channel forever, which is the problem this whole change exists to fix, and it
     * punishes exactly the person who took responsibility for the channel. Leaving it adminless is
     * worse than it sounds: nobody can invite to a private one, nobody can delete it, and there is no
     * path back — the channel is bricked, with no error message anywhere to explain why. So the
     * longest-standing remaining member becomes admin. Longest-standing needs no extra data, is
     * stable, and is explicable to the person it happens to.
     *
     * <p>The {@code FOR UPDATE} lock on the channel's admin rows is what makes it race-free, and it
     * is the same lock {@link #demote} takes for the same invariant. Two admins leaving at once
     * serialise on it: the second re-reads after the first commits, sees itself as the only admin
     * left, and hands over. Without the lock both read "there is another admin", both commit, and the
     * channel ends up with none.
     *
     * <p>The last <em>member</em> leaving is allowed and leaves an empty channel holding its
     * messages. {@link #join} covers the consequence for a PUBLIC one.
     */
    private void removeMembership(Channel channel, User user) {
        var membership = memberRepository.findByChannelAndUser(channel, user)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException(
                        "Not a member of this channel."));
        if (membership.getRole() == ChannelRole.ADMIN) {
            boolean anotherAdminRemains = memberRepository
                    .findByChannelAndRoleForUpdate(channel, ChannelRole.ADMIN).stream()
                    .anyMatch(m -> !m.getUser().getId().equals(user.getId()));
            if (!anotherAdminRemains) {
                memberRepository.findOthersOldestFirst(channel, user,
                                org.springframework.data.domain.PageRequest.of(0, 1))
                        .forEach(successor -> successor.setRole(ChannelRole.ADMIN));
            }
        }
        memberRepository.delete(membership);

        var channelId = channel.getId();
        var userId = user.getId();
        // Membership is no longer add-only, which is one of the two invariants ChannelAccessCache
        // rests on. Both halves of undoing a cached "yes" have to happen, and they are different
        // problems: evictMember stops the ex-member subscribing AGAIN, and the revoker takes away
        // the subscription they already hold — the broker authorises SUBSCRIBE once and never
        // re-checks, so without the second one an open socket keeps receiving a private channel's
        // messages until it happens to reconnect.
        afterCommit(() -> accessCache.evictMember(channelId, userId));
        afterCommit(() -> subscriptionRevoker.ifAvailable(r -> r.revoke(channelId, userId)));
    }

    /**
     * Rename a channel and/or rewrite its description. Channel admins only.
     *
     * <p><b>The slug moves with the name.</b> It is regenerated by exactly the rule {@link #create}
     * uses, so a channel's slug always describes its current name rather than the name it was born
     * with — a {@code #q3-planning} that reads {@code #project-x} in every URL is a small lie that
     * never stops being told. Nothing in this application resolves a channel by slug on a user-facing
     * path: pages are {@code /channels/{id}} and every API route is id-keyed, so no link breaks.
     * {@code requireBySlug} exists and has no caller; {@code ReminderScheduler} prints a slug into a
     * reminder's text, which is a rendering of the name at delivery time and correct to move.
     *
     * <p><b>The collision rule is create's.</b> Slugs are unique, so a rename that would land on
     * another channel's slug is refused with the same {@code IllegalStateException} — 409 rather than
     * a constraint violation surfacing as a 500. Renaming a channel to something that slugifies to
     * its own current slug is explicitly allowed: {@code existsBySlugAndIdNot} excludes the channel
     * being renamed, so "Deploys" → "deploys" changes the display name and keeps the URL.
     *
     * <p><b>Eviction is the point.</b> The write goes through a bulk UPDATE rather than a setter (see
     * {@code ChannelRepository.renameById}) so {@code Channel} stays immutable, and the cached copy
     * held by {@link ChannelAccessCache} is dropped after commit. The staleness a rename can cause is
     * cosmetic rather than an authorization bypass — unlike a type flip — but a cache documented as
     * safe because "channels never change" stops being safe the moment one does, and the eviction is
     * what keeps that sentence true.
     *
     * @return the channel as it now stands, re-read after the update so the caller broadcasts what
     *         was actually stored rather than what it asked for.
     */
    @Transactional
    public Channel rename(Channel channel, String name, String description, User actor) {
        requireAdmin(channel, actor);
        // An archived channel is a record, and rewriting the label on a record is not something an
        // archive should permit. Unarchive first if the name is genuinely wrong.
        requireNotArchived(channel);
        var trimmedName = name == null ? "" : name.trim();
        var slug = slugify(trimmedName);
        if (channelRepository.existsBySlugAndIdNot(slug, channel.getId())) {
            throw new IllegalStateException("Channel slug already exists: " + slug);
        }
        // Empty description means "no description", the same state a channel created without one is
        // in. Storing "" instead would give the header an empty separator bar to render.
        var trimmedDescription = description == null || description.isBlank() ? null : description.trim();
        channelRepository.renameById(channel.getId(), slug, trimmedName, trimmedDescription);
        var channelId = channel.getId();
        afterCommit(() -> accessCache.evictChannel(channelId));
        return requireById(channelId);
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

    /**
     * Star or unstar a channel for one member — the Slack / Mattermost favourite.
     *
     * <p>Membership is required, and not merely because the row is stored on it: a star is a
     * statement about your own sidebar, and there is no sidebar row to move for a channel you are
     * not in. Deliberately stricter than {@code requireMember}, which lets any authenticated user
     * read a PUBLIC channel, and the same posture {@code NotificationPreferenceService} takes for
     * the same reason.
     *
     * <p>Nothing is evicted from {@link ChannelAccessCache}: the cache holds channels and
     * write-access decisions, and a star is neither.
     */
    @Transactional
    public boolean setFavourite(Channel channel, User user, boolean favourite) {
        var membership = memberRepository.findByChannelAndUser(channel, user)
                .orElseThrow(() -> new AccessDeniedException(
                        "Join the channel before adding it to your favourites."));
        membership.setFavourite(favourite);
        return membership.isFavourite();
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

    /**
     * Archive a channel: freeze it as a read-only record and take it out of the way.
     *
     * <p>Channel admins, the same people who can rename it — plus workspace admins, for the reason
     * given on {@link #requireChannelOrWorkspaceAdmin}. Deliberately <em>not</em> the same bar as
     * {@link #destroy}, which is workspace-admin only: that asymmetry is the point of having both.
     * Archiving is the reversible action a team takes about its own finished project, and putting it
     * behind a workspace admin alone would mean nobody archives anything and the channel list keeps
     * growing, which is the problem.
     *
     * <p>Idempotent. Archiving an already-archived channel returns it unchanged rather than throwing
     * or overwriting the timestamp, so two admins clicking at once do not produce an error for the
     * slower one, and the recorded "archived by" stays the person who actually did it.
     *
     * <p><b>The eviction is not optional.</b> {@code requireWriteAccess} reads
     * {@link Channel#isArchived()} off whatever instance it is handed, and on the message send path
     * that instance comes from {@link ChannelAccessCache}. Without {@code evictChannel} the cached
     * copy keeps saying "live" for up to the TTL and the channel keeps accepting messages after
     * being archived — the identical failure the cache's documentation describes for a PUBLIC→PRIVATE
     * flip, which is why the entity has no setters and every mutation here is a bulk UPDATE.
     *
     * <p><b>Live subscriptions are deliberately left alone</b>, unlike {@link #removeMembership},
     * where revoking them is the whole point. Archiving removes no read access — {@code
     * requireMember} is untouched and an archived channel stays readable — so an open subscription
     * leaks nothing, and nothing can be broadcast on that topic anyway once every write path refuses.
     * Revoking would in fact be actively wrong: the {@code channel-archived} frame that tells open
     * clients to grey themselves out travels on that very subscription, and a client whose
     * subscription had just been torn away would never receive it and would sit there showing a live
     * composer for a channel that is not.
     */
    @Transactional
    public Channel archive(Channel channel, User actor) {
        requireChannelOrWorkspaceAdmin(channel, actor);
        if (channel.isArchived()) {
            return channel;
        }
        channelRepository.setArchivedById(channel.getId(), java.time.Instant.now(), actor,
                actor.getUsername());
        var channelId = channel.getId();
        afterCommit(() -> accessCache.evictChannel(channelId));
        return requireById(channelId);
    }

    /**
     * Unarchive: put the channel back in the sidebar and let people write to it again.
     *
     * <p>This exists because archiving without it is a one-way door, and a one-way door would make
     * archiving the more frightening of the two destructive-looking buttons — which would push people
     * towards {@link #destroy}, the one that actually cannot be undone. Both Slack and Mattermost make
     * it reversible for the same reason.
     *
     * <p>Nothing has to be restored. Memberships, favourites, notification levels and read markers
     * were never deleted, only hidden by the sidebar's query, so a channel comes back exactly as it
     * went in. Idempotent, and it evicts for the same reason {@link #archive} does — in this
     * direction a stale cached copy refuses writes to a channel that is live again, which is the more
     * visible failure of the two and the one people would report as "unarchive didn't work".
     */
    @Transactional
    public Channel unarchive(Channel channel, User actor) {
        requireChannelOrWorkspaceAdmin(channel, actor);
        if (!channel.isArchived()) {
            return channel;
        }
        channelRepository.setArchivedById(channel.getId(), null, null, null);
        var channelId = channel.getId();
        afterCommit(() -> accessCache.evictChannel(channelId));
        return requireById(channelId);
    }

    /** Every archived channel, most recently archived first — the admin console's list. */
    @Transactional(readOnly = true)
    public List<Channel> listArchived() {
        return channelRepository.findAllByArchivedAtIsNotNullOrderByArchivedAtDesc();
    }

    /**
     * Destroy a channel and everything in it. Irreversible.
     *
     * <p><b>Workspace admins only, and archiving is what everyone else gets.</b> The choice was
     * between a channel admin and a workspace admin, and the conservative one is right here for a
     * reason that is not caution for its own sake: a channel admin is whoever happened to create the
     * room, or whoever inherited it when the previous admin left, and this action destroys other
     * people's messages and other people's files with no undo of any kind. Slack draws the line in
     * the same place and pushes everyone else towards archiving, which is exactly the trade this
     * change makes available — archive is a channel-admin action, reversible, and loses nothing.
     * Anybody who genuinely needs a channel gone can ask; nobody has ever needed it gone in the next
     * thirty seconds.
     *
     * <p>The check reads Spring's live {@code ROLE_ADMIN} authority, the same way
     * {@link #requireChannelOrWorkspaceAdmin} does and for the same reason. This is a <b>narrowing</b>
     * of what the method used to require ({@code requireAdmin}, the channel role), so
     * {@code AdminAndConversationFlowIT} and {@code StorageAccountingIT} were inverted rather than
     * extended: what they asserted — that a channel admin can destroy their own channel — is the
     * behaviour being removed.
     *
     * <p>Subscription revocation is deliberately <em>not</em> here. It has to happen after the
     * {@code channel-deleted} broadcast, and the broadcast lives in the web layer, so the controller
     * owns the order: destroy, announce, then {@link #revokeAllSubscriptions}. Registering the revoke
     * as an {@code afterCommit} hook here would race the announcement it is supposed to follow, and
     * losing that race means a client is silently cut off with no idea why.
     */
    @Transactional
    public void destroy(Channel channel, User actor) {
        if (!isWorkspaceAdmin()) {
            throw new AccessDeniedException(
                    "Deleting a channel requires a workspace administrator. Archive it instead.");
        }
        // Capture the channel's message ids + attachment rows before the delete cascades them away,
        // then purge the Lucene docs, reap the files and credit the bytes back after commit —
        // otherwise all three leak forever (the index bloats, the disk fills, and everyone who ever
        // posted a file here stays charged for it) since rebuildIfEmpty never reconciles a
        // non-empty index and there is no reconcile at all for storage usage. Mirrors
        // MessageService.delete.
        //
        // findIdsByChannel is deliberately unfiltered — it includes soft-deleted messages, whose rows
        // are going with the channel and whose index documents would otherwise be stale forever
        // (nothing reconciles a non-empty index down, so allIndexedIds would keep them alive as
        // "present"). The attachment query is the opposite: it excludes tombstones, because those
        // bytes were already reaped and already credited when the uploader deleted the file, and
        // crediting them twice is unrecoverable. See both queries' javadoc.
        var messageIds = messageRepository.findIdsByChannel(channel);
        var doomedAttachments = attachmentRepository.findLiveByChannelWithAuthor(channel);
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

    /**
     * Tear down every live subscription to a destroyed channel's topics.
     *
     * <p>Separate from {@link #destroy} so the caller controls the order relative to its
     * {@code channel-deleted} broadcast — that frame travels on the very subscription this removes,
     * so it has to go out first. A no-op wherever there is no broker, which is the integration-test
     * context.
     */
    public void revokeAllSubscriptions(long channelId) {
        subscriptionRevoker.ifAvailable(r -> r.revokeAll(channelId));
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

    /**
     * Which of {@code userIds} are currently members of {@code channel} — one query, not one per id.
     *
     * <p>Used to narrow a derived audience (thread participants) to people who are still here.
     * Membership at the time somebody posted is not membership now: they may have left, or been
     * removed, and neither should keep receiving the channel's traffic.
     */
    @Transactional(readOnly = true)
    public java.util.Set<Long> membersAmong(Channel channel, java.util.Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(memberRepository.findMemberUserIds(channel, userIds));
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
        requireNotArchived(channel);
        if (!isMember(channel, user)) {
            throw new AccessDeniedException("Join the channel before posting.");
        }
    }

    /**
     * An archived channel accepts no writes. <b>This is the only place that says so.</b>
     *
     * <p>Put here, at the bottom of {@link #requireWriteAccess} and {@link #requireWriteAccessCached},
     * rather than at each call site, because the call sites are the problem. Posting, the HTTP and
     * WebSocket send paths, thread replies, edits, reactions, attachments, typing pings, invites,
     * poll votes and {@code /remind} are nine or ten separate entry points across five classes, and
     * a rule enforced in ten places is a rule enforced in nine as soon as an eleventh is written.
     * AGENTS.md already tells new write endpoints to call {@code requireWriteAccess}; hanging this off
     * that instruction means a new one inherits the rule without its author having heard of
     * archiving. The two paths that genuinely cannot use it — {@link #join}, where you are not a
     * member yet, and {@link #rename} — call this directly, and they are the exceptions precisely
     * because they say so out loud.
     *
     * <p><b>Two things deliberately still work.</b> Reading: {@link #requireMember} is untouched, so
     * an archived channel's history stays readable and searchable, which is the difference between
     * archiving and deleting. And deleting a message: that goes through an author-or-admin check
     * rather than this one, and it stays that way on purpose — archiving freezes the channel as a
     * record, so <em>changing</em> what the record says is refused, while <em>removing</em> something
     * from it (a leak, a mistake, a moderator takedown) must not require unarchiving the channel,
     * making it writable again for the duration, and re-archiving it afterwards.
     *
     * <p>{@link AccessDeniedException} rather than a new exception type: 403 is the honest answer to
     * "may I write here", the UI already hides the composer and every other control behind the same
     * flag, so reaching this is a backstop rather than a normal user experience, and the alternative
     * meant teaching {@code ApiExceptionHandler} a new envelope for a message nobody should see.
     */
    private static void requireNotArchived(Channel channel) {
        if (channel.isArchived()) {
            throw new AccessDeniedException(
                    "This channel is archived and read-only. Unarchive it to post again.");
        }
    }

    public void requireAdmin(Channel channel, User user) {
        if (!isAdmin(channel, user)) {
            throw new AccessDeniedException("Channel admin role required.");
        }
    }

    /**
     * Channel admin <em>or</em> workspace admin — the bar for archiving and unarchiving.
     *
     * <p>Wider than {@link #requireAdmin} for one specific reason, and it is not administrative
     * convenience: without it, archiving is a trap. Every channel admin can leave a channel (the
     * last one hands over, but the successor need not be interested), and an archived channel cannot
     * be joined, so a channel whose remaining members are all plain members would be archived
     * forever with nobody on earth able to bring it back. The task the workspace admin performs here
     * is unsticking that, which is exactly what a workspace admin is for.
     *
     * <p>The check reads Spring's {@code ROLE_ADMIN} authority off the live request, not
     * {@code User.isAdmin()} — that column is a login-time cache whose own javadoc says never to make
     * an access decision from it alone. {@code SearchService.isPlatformAdmin} asks the same question
     * the same way for {@code searchEverywhere}. In a context with no {@code SecurityContext} at all
     * (the service-layer integration tests) it answers false, so those tests exercise the
     * channel-admin path and nothing is accidentally permitted by the absence of a principal.
     */
    private void requireChannelOrWorkspaceAdmin(Channel channel, User user) {
        if (isAdmin(channel, user) || isWorkspaceAdmin()) {
            return;
        }
        throw new AccessDeniedException("Channel admin or workspace admin role required.");
    }

    private static boolean isWorkspaceAdmin() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
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
