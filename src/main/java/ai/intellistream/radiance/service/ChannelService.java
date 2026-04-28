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

import ai.intellistream.radiance.domain.Channel;
import ai.intellistream.radiance.domain.ChannelMember;
import ai.intellistream.radiance.domain.ChannelRole;
import ai.intellistream.radiance.domain.ChannelType;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.ChannelMemberRepository;
import ai.intellistream.radiance.repository.ChannelRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository memberRepository;

    public ChannelService(ChannelRepository channelRepository,
                          ChannelMemberRepository memberRepository) {
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
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
                .orElseThrow(() -> new ai.intellistream.radiance.security.ResourceNotFoundException("Channel not found: " + id));
    }

    @Transactional(readOnly = true)
    public Channel requireBySlug(String slug) {
        return channelRepository.findBySlug(slug)
                .orElseThrow(() -> new ai.intellistream.radiance.security.ResourceNotFoundException("Channel not found: " + slug));
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
        return memberRepository.findByChannelAndUser(channel, user)
                .orElseGet(() -> memberRepository.save(new ChannelMember(channel, user, ChannelRole.MEMBER)));
    }

    @Transactional
    public ChannelMember invite(Channel channel, User invitee, User actor) {
        // Slack / Mattermost default: any channel member can invite. Channel admins keep
        // exclusive control over promote/demote and eventual destructive actions.
        // Writes require actual membership — using requireMember here would let any
        // authenticated user force-join others into PUBLIC channels.
        requireWriteAccess(channel, actor);
        return memberRepository.findByChannelAndUser(channel, invitee)
                .orElseGet(() -> memberRepository.save(new ChannelMember(channel, invitee, ChannelRole.MEMBER)));
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
        long otherAdmins = memberRepository.findAllByChannelOrderByJoinedAtAsc(channel).stream()
                .filter(m -> m.getRole() == ChannelRole.ADMIN)
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
        channelRepository.delete(channel);
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
