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

import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.ChannelService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;

/**
 * The two file-listing pages. Both render a shell only — the table is filled by JavaScript from an
 * API, so filtering and paging never reload the page.
 *
 * <ul>
 *   <li>{@code /files} — the file manager: everything <em>this account</em> uploaded, anywhere.
 *       No id is accepted and none is needed; the account comes from the session via
 *       {@link CurrentUser}, so there is deliberately no "whose files" parameter to forget to
 *       check.</li>
 *   <li>{@code /channels/{id}/files} — everything <em>anybody</em> shared in one channel. This one
 *       necessarily takes an id, so it necessarily has a check, and the check is the channel's own
 *       read rule.</li>
 * </ul>
 *
 * <p>They live together because they are the same page shape and share a stylesheet section, and
 * because putting the one that authorizes next to the one that structurally cannot need to is the
 * cheapest way to keep noticing which is which.
 */
@Controller
public class FilesController {

    private final CurrentUser currentUser;
    private final StorageQuotaService quotas;
    private final ChannelService channels;

    public FilesController(CurrentUser currentUser, StorageQuotaService quotas,
                           ChannelService channels) {
        this.currentUser = currentUser;
        this.quotas = quotas;
        this.channels = channels;
    }

    @GetMapping("/files")
    public String files(Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        model.addAttribute("me", me);
        // The quota line is server-rendered because it is true on arrival and does not change while
        // the user searches; the list below it is the part that moves.
        model.addAttribute("usage", quotas.usageFor(me));
        return "files";
    }

    /**
     * A channel's files.
     *
     * <p>A PRIVATE channel the viewer is not in renders a "ask an admin for an invitation" panel
     * rather than a 403, which is exactly what {@code /channels/{id}} already does for the same
     * viewer and the same channel — the channel's <em>existence and name</em> are already visible
     * there, so refusing differently here would be a difference without a secret behind it. What
     * does not happen either way is the list: the template has no rows to render because the model
     * has no {@code canRead}, and the API the script would call refuses the same request with a 403.
     */
    @GetMapping("/channels/{channelId}/files")
    public String channelFiles(@PathVariable Long channelId, Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        var channel = channels.requireById(channelId);
        model.addAttribute("me", me);
        model.addAttribute("channel", channel);
        // Read access, evaluated the same way requireMember decides it: PUBLIC is readable by any
        // signed-in user, PRIVATE needs real membership. Asked as a question rather than caught as
        // an exception, because the answer is a branch in the template and not an error.
        model.addAttribute("canRead",
                channel.getType() == ai.intellistream.chat.domain.ChannelType.PUBLIC
                        || channels.isMember(channel, me));
        return "channel-files";
    }
}
