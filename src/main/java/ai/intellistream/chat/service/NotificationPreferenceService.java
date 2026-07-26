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
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the notification control: one account-wide default per user, plus a per-channel
 * override on each membership.
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
    private final UserRepository userRepository;

    public NotificationPreferenceService(ChannelMemberRepository memberRepository,
                                         UserRepository userRepository) {
        this.memberRepository = memberRepository;
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

    private ChannelMember requireMembership(Channel channel, User user) {
        return memberRepository.findByChannelAndUser(channel, user)
                .orElseThrow(() -> new AccessDeniedException(
                        "Join the channel to see or change its notification setting."));
    }
}
