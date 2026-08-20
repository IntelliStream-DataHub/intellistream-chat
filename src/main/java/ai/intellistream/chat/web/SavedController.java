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

import ai.intellistream.chat.i18n.TimeFormats;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.SavedMessageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.util.Locale;

/**
 * The saved-items page. Shell only — the list is filled by {@code saved.js} from
 * {@code /api/saved}, exactly as {@code /files} works, so paging never reloads the page.
 *
 * <p>No id is accepted and none is needed: the page always shows the signed-in account's own saves,
 * and the account comes from the session via {@link CurrentUser}. There is deliberately no "whose
 * saves" parameter to forget to check.
 */
@Controller
public class SavedController {

    private final CurrentUser currentUser;
    private final SavedMessageService saved;
    private final TimeFormats timeFormats;

    public SavedController(CurrentUser currentUser, SavedMessageService saved,
                           TimeFormats timeFormats) {
        this.currentUser = currentUser;
        this.saved = saved;
        this.timeFormats = timeFormats;
    }

    @GetMapping("/saved")
    public String saved(Principal principal, Locale locale, Model model) {
        var me = currentUser.resolve(principal);
        model.addAttribute("me", me);
        timeFormats.into(model, me, locale);
        // Server-rendered because it is true on arrival; the list below it is the part that moves.
        model.addAttribute("savedCount", saved.countFor(me));
        return "saved";
    }
}
