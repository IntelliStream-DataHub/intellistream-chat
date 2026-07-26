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

package ai.intellistream.chat.web;

import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelFileService;
import ai.intellistream.chat.service.ChannelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;

/**
 * The files shared in one channel. Read-only, by design: this is a way to find a file, not a second
 * place to remove one — see {@link ChannelFileService} for why the delete stays in the file manager.
 *
 * <p>Separate from {@link UserFileRestController} because that controller's whole contract is "the
 * signed-in account and nothing else, no owner parameter anywhere". This one necessarily takes an id
 * from the client, so it is a different endpoint with a different check, and folding the two into
 * one class would put the one path that must authorize next to the ones that structurally cannot
 * need to.
 */
@RestController
public class ChannelFileRestController {

    private final ChannelFileService channelFiles;
    private final ChannelService channels;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public ChannelFileRestController(ChannelFileService channelFiles,
                                     ChannelService channels,
                                     CurrentUser currentUser,
                                     RateLimiter rateLimiter) {
        this.channelFiles = channelFiles;
        this.channels = channels;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    /**
     * One page of {@code channelId}'s files. {@code q} filters on filename; {@code page} is
     * zero-based.
     *
     * <p>Authorization is {@code ChannelService.requireMember}, applied inside the service next to
     * the query it guards rather than here — a check in the controller is one an internal caller
     * can walk past. A PRIVATE channel the caller is not in answers 403, the same as its message
     * list does; it does not answer an empty page, which would confirm the channel exists.
     */
    @GetMapping("/api/channels/{channelId}/files")
    public ChannelFileService.ChannelFilePage list(
            @PathVariable Long channelId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            Principal principal) {
        var me = currentUser.resolve(principal);
        // Typing in the filter box issues a request per keystroke (debounced client-side); the
        // ceiling is well above that and only bites on a scripted hammer.
        if (!rateLimiter.tryAcquire(me.getUsername(), "channel-file-list", 120, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("channel-file-list rate exceeded");
        }
        return channelFiles.list(channels.requireById(channelId), me, query, page);
    }
}
