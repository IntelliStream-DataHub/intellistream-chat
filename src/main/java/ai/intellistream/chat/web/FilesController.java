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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

/**
 * The file manager page. Renders the shell only — the table itself is filled by
 * {@code files.js} from {@code /api/files}, so searching and paging never reload the page.
 *
 * <p>No id is accepted here and none is needed: the page always shows the signed-in account's own
 * uploads, and the account comes from the session via {@link CurrentUser}. There is deliberately no
 * "whose files" parameter to forget to check.
 */
@Controller
public class FilesController {

    private final CurrentUser currentUser;
    private final StorageQuotaService quotas;

    public FilesController(CurrentUser currentUser, StorageQuotaService quotas) {
        this.currentUser = currentUser;
        this.quotas = quotas;
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
}
