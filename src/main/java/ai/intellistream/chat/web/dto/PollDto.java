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

package ai.intellistream.chat.web.dto;

import java.util.List;


/**
 * Wire shape for a poll attached to a {@link MessageDto}. {@code myVoteOptionId} is null
 * when the viewing user hasn't voted; {@code totalVoters} is the sum of {@code voteCount}
 * across options (a single voter is counted once even if poll allows changing votes,
 * since the voter→option mapping is unique).
 */
public record PollDto(
        Long id,
        String question,
        List<PollOptionDto> options,
        Long myVoteOptionId,
        int totalVoters
) {
    public record PollOptionDto(Long id, int position, String label, int voteCount) {}
}
