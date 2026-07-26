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
import ai.intellistream.chat.domain.ChannelMember;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMember;
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ConversationMemberRepository;
import ai.intellistream.chat.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads and writes the notification control: one account-wide default per user, plus an override on
 * each membership — channel <em>and</em> conversation.
 *
 * <p>Conversations arrived second and are deliberately not a second mechanism. Muting a noisy group
 * DM and muting a noisy channel are the same request; giving them separate machinery would give the
 * account default two meanings, and would mean a user who changes it watches half their rooms move.
 *
 * <p>A DM has one thing a channel does not: it always used to notify, with no way to say otherwise.
 * That is what {@link NotificationLevel#NONE} is for here. Note that a conversation reads the same
 * three levels slightly differently — a conversation has no bystanders, so ALL and MENTIONS both
 * deliver and only NONE silences. The reasoning lives on {@code ConversationAlertPublisher}, which
 * is the one place that acts on it.
 *
 * <p>The service never resolves an override on write. A channel that is following the account
 * default stores {@link NotificationLevel#DEFAULT}, so changing the account default moves every
 * such channel at once and leaves explicitly-set ones alone. Resolution happens on read, in
 * {@link #effectiveLevelFor} — see {@link NotificationLevel} for why that direction matters.
 *
 * <p><b>Membership is required</b> for everything channel-scoped, including reads. This is
 * deliberately stricter than {@code ChannelService.requireMember}, which lets any authenticated
 * user read a PUBLIC channel: a notification preference is a fact about a person, not about the
 * channel, and a non-member has neither one to read nor standing to set one. A missing channel is
 * still a 404 (the caller resolves it through {@code ChannelService.requireById} first) and a
 * non-member is a 403, which is the same 404-vs-403 split the rest of the channel API uses.
 */
@Service
public class NotificationPreferenceService {

    private final ChannelMemberRepository memberRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final UserRepository userRepository;

    public NotificationPreferenceService(ChannelMemberRepository memberRepository,
                                         ConversationMemberRepository conversationMemberRepository,
                                         UserRepository userRepository) {
        this.memberRepository = memberRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * The user's account-wide default — never {@code DEFAULT}. No transaction: the column is
     * eagerly mapped, so this reads the entity the caller already holds rather than the database.
     */
    public NotificationLevel accountDefault(User user) {
        var stored = user.getNotifyDefault();
        return stored == null ? NotificationLevel.ACCOUNT_FALLBACK : stored;
    }

    /**
     * Set the account-wide default. Re-reads the managed row first (same shape as
     * {@code UserService.updateTheme}) so the write lands whether the caller handed us a managed
     * or a detached {@link User}.
     *
     * @throws IllegalArgumentException for {@code null} or {@link NotificationLevel#DEFAULT}.
     */
    @Transactional
    public NotificationLevel setAccountDefault(User user, NotificationLevel level) {
        var managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User missing: " + user.getId()));
        managed.chooseNotifyDefault(level);
        return managed.getNotifyDefault();
    }

    /**
     * This channel's <b>raw</b> level for the user — {@code DEFAULT} when they are following the
     * account default. Raw on purpose: the picker has a "Default" option, and returning the
     * resolved level would make it impossible to tell an inherited MENTIONS from a channel the
     * user deliberately pinned to MENTIONS.
     */
    @Transactional(readOnly = true)
    public NotificationLevel levelFor(Channel channel, User user) {
        return requireMembership(channel, user).getNotifyLevel();
    }

    /** The level actually in force for this channel, with {@code DEFAULT} resolved. */
    @Transactional(readOnly = true)
    public NotificationLevel effectiveLevelFor(Channel channel, User user) {
        return requireMembership(channel, user).effectiveNotifyLevel(accountDefault(user));
    }

    /**
     * Set this channel's level for the user. {@link NotificationLevel#DEFAULT} clears the override
     * and puts the channel back to following the account default.
     */
    @Transactional
    public NotificationLevel setLevelFor(Channel channel, User user, NotificationLevel level) {
        var membership = requireMembership(channel, user);
        membership.chooseNotifyLevel(level);
        return membership.getNotifyLevel();
    }

    // ------------------------------------------------------------------ conversations

    /**
     * This conversation's <b>raw</b> level for the user — {@code DEFAULT} when they are following
     * the account default. Raw for the reason the channel one is: the picker has a "Default" option
     * and needs to know whether MENTIONS was inherited or chosen.
     *
     * <p>The same account default sits underneath both. That is the point of routing this through
     * here rather than building a second control: "mute this group DM" and "mute this channel" are
     * the same request, and a user who changes their account default expects everything they have
     * not overridden to move — channels and conversations alike.
     */
    @Transactional(readOnly = true)
    public NotificationLevel levelFor(Conversation conversation, User user) {
        return requireMembership(conversation, user).getNotifyLevel();
    }

    /** The level actually in force for this conversation, with {@code DEFAULT} resolved. */
    @Transactional(readOnly = true)
    public NotificationLevel effectiveLevelFor(Conversation conversation, User user) {
        return requireMembership(conversation, user).effectiveNotifyLevel(accountDefault(user));
    }

    /**
     * The effective level for every member of a conversation, keyed by user id.
     *
     * <p>One query rather than one per recipient, because the caller is the alert fan-out and it
     * runs on every message sent in the conversation. Each member's own account default is what
     * their {@code DEFAULT} resolves against — the level in force is a fact about a person, and
     * resolving everyone against the sender's default would be a different feature and a wrong one.
     */
    @Transactional(readOnly = true)
    public Map<Long, NotificationLevel> effectiveLevelsFor(Conversation conversation) {
        var rows = conversationMemberRepository.findAllByConversationOrderByJoinedAtAsc(conversation);
        var out = new HashMap<Long, NotificationLevel>(rows.size());
        for (var m : rows) {
            out.put(m.getUser().getId(), m.effectiveNotifyLevel(accountDefault(m.getUser())));
        }
        return out;
    }

    /**
     * Set this conversation's level for the user. {@link NotificationLevel#DEFAULT} clears the
     * override and puts the conversation back to following the account default.
     */
    @Transactional
    public NotificationLevel setLevelFor(Conversation conversation, User user, NotificationLevel level) {
        var membership = requireMembership(conversation, user);
        membership.chooseNotifyLevel(level);
        return membership.getNotifyLevel();
    }

    private ChannelMember requireMembership(Channel channel, User user) {
        return memberRepository.findByChannelAndUser(channel, user)
                .orElseThrow(() -> new AccessDeniedException(
                        "Join the channel to see or change its notification setting."));
    }

    /**
     * Membership is required, and there is no public tier to relax it to: a conversation is private
     * to its participants, so a non-member has neither a preference to read nor standing to set one.
     */
    private ConversationMember requireMembership(Conversation conversation, User user) {
        return conversationMemberRepository.findByConversationAndUser(conversation, user)
                .orElseThrow(() -> new AccessDeniedException(
                        "Not a participant in this conversation."));
    }
}
