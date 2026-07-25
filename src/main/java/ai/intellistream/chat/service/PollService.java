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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.Poll;
import ai.intellistream.chat.domain.PollOption;
import ai.intellistream.chat.domain.PollVote;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.PollRepository;
import ai.intellistream.chat.repository.PollVoteRepository;
import ai.intellistream.chat.web.dto.PollDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the {@code polls} / {@code poll_options} / {@code poll_votes} side of the chat. The
 * existing reaction infrastructure deliberately stays untouched — adding a 👍 to a poll
 * message is a normal reaction, not a vote.
 */
@Service
public class PollService {

    /** Hard limits — symmetric with PollCommand's parsing ceilings. */
    public static final int MAX_OPTIONS = 10;
    public static final int MAX_QUESTION_LENGTH = 500;
    public static final int MAX_LABEL_LENGTH = 200;

    private final PollRepository pollRepo;
    private final PollVoteRepository voteRepo;

    public PollService(PollRepository pollRepo, PollVoteRepository voteRepo) {
        this.pollRepo = pollRepo;
        this.voteRepo = voteRepo;
    }

    /**
     * Persist a new poll attached to {@code message}. {@code labels} must have ≥2 entries
     * and ≤ {@link #MAX_OPTIONS}; trims and validates length.
     */
    @Transactional
    public Poll create(Message message, String question, List<String> labels) {
        var trimmedQuestion = question == null ? "" : question.trim();
        if (trimmedQuestion.isEmpty()) {
            throw new IllegalArgumentException("Poll question is required");
        }
        if (trimmedQuestion.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Poll question too long (max " + MAX_QUESTION_LENGTH + " chars)");
        }
        if (labels == null || labels.size() < 2) {
            throw new IllegalArgumentException("Poll needs at least 2 options");
        }
        if (labels.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("Poll can have at most " + MAX_OPTIONS + " options");
        }
        var poll = new Poll(message, trimmedQuestion);
        for (int i = 0; i < labels.size(); i++) {
            var label = labels.get(i) == null ? "" : labels.get(i).trim();
            if (label.isEmpty()) throw new IllegalArgumentException("Poll option labels can't be empty");
            if (label.length() > MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException(
                        "Poll option too long (max " + MAX_LABEL_LENGTH + " chars)");
            }
            poll.addOption(i, label);
        }
        return pollRepo.save(poll);
    }

    /**
     * Cast or change the voter's pick. A second call with a different option moves the vote;
     * a call with the same option is a no-op (idempotent on retries).
     */
    @Transactional
    public PollDto castVote(Long pollId, Long optionId, User voter) {
        var poll = pollRepo.findByIdWithOptions(pollId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Poll not found: " + pollId));
        var option = poll.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Option not in this poll"));

        var existing = voteRepo.findByPollAndVoter(poll, voter);
        if (existing.isPresent() && existing.get().getOption().getId().equals(option.getId())) {
            // Same vote — no DB write needed.
            return toDto(poll, voter);
        }
        existing.ifPresent(voteRepo::delete);
        // Flush the delete BEFORE the insert so we don't trip uk_poll_votes_voter on the same
        // (poll, voter) row twice within one Hibernate flush cycle.
        voteRepo.flush();
        // Insert-or-ignore (N1): a concurrent first-vote by the same voter is absorbed — their pick
        // wins the uk_poll_votes_voter row and this caller's choice self-heals on the next click.
        // ON CONFLICT (vs the old saveAndFlush + catch) keeps the tx usable so toDto below can read.
        voteRepo.insertVoteIgnore(poll.getId(), option.getId(), voter.getId());
        return toDto(poll, voter);
    }

    /** Withdraw the voter's pick, if any. */
    @Transactional
    public PollDto removeVote(Long pollId, User voter) {
        var poll = pollRepo.findByIdWithOptions(pollId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Poll not found: " + pollId));
        voteRepo.deleteByPollAndVoter(poll, voter);
        return toDto(poll, voter);
    }

    /** Single-message lookup (for the channel hosting a vote round-trip). */
    @Transactional(readOnly = true)
    public PollDto pollFor(Message message, User viewer) {
        return pollRepo.findByMessageIdWithOptions(message.getId())
                .map(p -> toDto(p, viewer))
                .orElse(null);
    }

    /**
     * Batch lookup keyed by host-message id, used when serializing a page of channel
     * messages. Two queries total: one for tallies, one for the viewer's votes.
     */
    @Transactional(readOnly = true)
    public Map<Long, PollDto> pollsForMessages(Collection<Message> messages, User viewer) {
        if (messages.isEmpty()) return Map.of();
        var messageIds = messages.stream().map(Message::getId).toList();
        var polls = pollRepo.findByMessageIdsWithOptions(messageIds);
        if (polls.isEmpty()) return Map.of();
        var pollIds = polls.stream().map(Poll::getId).toList();

        var tally = new HashMap<Long, Map<Long, Integer>>();
        for (var row : voteRepo.tallyByPollIds(pollIds)) {
            var pollId = (Long) row[0];
            var optionId = (Long) row[1];
            var count = ((Number) row[2]).intValue();
            tally.computeIfAbsent(pollId, k -> new HashMap<>()).put(optionId, count);
        }
        var myVotes = new HashMap<Long, Long>();
        for (var row : voteRepo.myVotesByPollIds(viewer, pollIds)) {
            myVotes.put((Long) row[0], (Long) row[1]);
        }

        var out = new HashMap<Long, PollDto>(polls.size());
        for (var p : polls) {
            out.put(p.getMessage().getId(),
                    buildDto(p, tally.getOrDefault(p.getId(), Map.of()), myVotes.get(p.getId())));
        }
        return out;
    }

    /* ------------------------------------------------------------------ */

    private PollDto toDto(Poll poll, User viewer) {
        var tallyRows = voteRepo.tallyByPollIds(List.of(poll.getId()));
        var optionCounts = new HashMap<Long, Integer>();
        for (var row : tallyRows) {
            optionCounts.put((Long) row[1], ((Number) row[2]).intValue());
        }
        var mine = voteRepo.findByPollAndVoter(poll, viewer)
                .map(v -> v.getOption().getId())
                .orElse(null);
        return buildDto(poll, optionCounts, mine);
    }

    private static PollDto buildDto(Poll poll, Map<Long, Integer> optionCounts, Long myVoteOptionId) {
        var options = new ArrayList<PollDto.PollOptionDto>(poll.getOptions().size());
        int total = 0;
        for (PollOption o : poll.getOptions()) {
            var count = optionCounts.getOrDefault(o.getId(), 0);
            total += count;
            options.add(new PollDto.PollOptionDto(o.getId(), o.getPosition(), o.getLabel(), count));
        }
        return new PollDto(poll.getId(), poll.getQuestion(), options, myVoteOptionId, total);
    }
}
