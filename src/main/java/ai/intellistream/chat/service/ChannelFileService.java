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
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.web.dto.ChannelFileDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The files shared in one channel: everybody's, not just yours.
 *
 * <h2>Why this is not {@code UserFileService}</h2>
 * {@link UserFileService} is built on the invariant that <em>ownership is the query</em> — every
 * statement it issues carries {@code message.author = :owner}, so no request shape can return
 * somebody else's row. This class is the opposite question and cannot borrow that invariant: it
 * takes a channel id from the client and returns files uploaded by other people. The safety
 * therefore has to come from somewhere else, and it comes from exactly one place — the same read
 * check the channel's messages go through.
 *
 * <h2>Authorization: the channel's read rule, and nothing narrower</h2>
 * {@link ChannelService#requireMember} is the <em>read</em> check: it short-circuits for PUBLIC
 * channels, so any signed-in user may browse a public channel's files exactly as they may already
 * read its messages and download its attachments one by one from the feed. For a PRIVATE channel it
 * requires real membership, so a non-member gets nothing — not an empty list, which would confirm
 * the channel exists and say something about how much is in it, but the same {@code AccessDenied}
 * the message list gives.
 *
 * <p>Reusing that method rather than writing a second rule is the point. A file browser with its own
 * notion of "may read this channel" is a second access-control implementation for the same
 * question, and the second one is the one that does not get updated.
 *
 * <h2>Tombstones are omitted, not shown</h2>
 * A file the uploader deleted has no bytes left, so a row for it here would be a download link to
 * nothing on a page whose entire purpose is downloading. The removal is not hidden — the message it
 * was posted with still renders the {@code attachment_removed} tombstone, with who removed it and
 * when — but that belongs where there is a conversation around it to make "this used to be here"
 * mean something. Files on messages a moderator removed are omitted for a stronger reason: those
 * downloads are refused outright ({@code AttachmentService.requireForDownload}), so listing them
 * would advertise content the workspace took down.
 *
 * <h2>No DM equivalent</h2>
 * Deliberately absent. The read rule for a conversation is different (membership, with no public
 * tier) and lives in {@code ConversationService}; a DM's file list should be built against that,
 * not by generalising this class into something that takes "a container id" and picks a rule.
 */
@Service
public class ChannelFileService {

    /** Files per page. Matches the file manager, so the two lists page at the same rhythm. */
    public static final int PAGE_SIZE = 50;

    private final AttachmentRepository attachments;
    private final ChannelService channels;

    public ChannelFileService(AttachmentRepository attachments, ChannelService channels) {
        this.attachments = attachments;
        this.channels = channels;
    }

    /**
     * One page of a channel's files.
     *
     * @param total    matching files before paging — the number the page prints, so it counts the
     *                 whole channel rather than the rows that happened to fit
     * @param hasMore  whether a further page exists
     */
    public record ChannelFilePage(List<ChannelFileDto> files, long total,
                                  int page, int pageSize, boolean hasMore) {}

    /**
     * One page of the files posted in {@code channel}, newest first, optionally narrowed to
     * filenames containing {@code query}.
     *
     * <p>Paging is a plain offset window and is not capped the way the file manager's is: that page
     * merges two tables in memory and pays {@code (N+1) * PAGE_SIZE} rows from each to serve page N,
     * whereas this is one indexed query with a real SQL {@code LIMIT/OFFSET}. There is no in-memory
     * cost to bound, so bounding it would only take a channel's older files out of reach.
     */
    @Transactional(readOnly = true)
    public ChannelFilePage list(Channel channel, User viewer, String query, int page) {
        channels.requireMember(channel, viewer);
        var pattern = UserFileService.likePattern(query);
        int safePage = Math.max(0, page);
        var rows = attachments.findLiveInChannel(channel, pattern,
                PageRequest.of(safePage, PAGE_SIZE));
        long total = attachments.countLiveInChannel(channel, pattern);
        return new ChannelFilePage(toDtos(channel, rows), total, safePage, PAGE_SIZE,
                (long) (safePage + 1) * PAGE_SIZE < total);
    }

    private static List<ChannelFileDto> toDtos(Channel channel, List<Attachment> rows) {
        var out = new ArrayList<ChannelFileDto>(rows.size());
        for (var a : rows) {
            var message = a.getMessage();
            var uploader = message.getAuthor();
            var parent = message.getParent();
            var anchorId = parent == null ? message.getId() : parent.getId();
            out.add(new ChannelFileDto(
                    a.getId(),
                    a.getFilename(),
                    a.getContentType(),
                    a.getSizeBytes(),
                    a.getCreatedAt(),
                    "/api/attachments/" + a.getId() + "/download",
                    "/channels/" + channel.getId() + "?m=" + anchorId + "#m=" + anchorId,
                    uploader.getUsername(),
                    uploader.getDisplayName(),
                    uploader.hasAvatar(),
                    uploader.avatarVersion()));
        }
        return List.copyOf(out);
    }
}
