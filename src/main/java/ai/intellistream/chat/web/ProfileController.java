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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.time.ZoneId;

@Controller
public class ProfileController {

    private final CurrentUser currentUser;
    private final UserService userService;
    private final UserRepository users;

    /** {@code ichat.default-zone}; blank means the server's own zone. See {@link User#effectiveZone}. */
    private final ZoneId defaultZone;

    public ProfileController(CurrentUser currentUser,
                             UserService userService,
                             UserRepository users,
                             @Value("${ichat.default-zone:}") String defaultZone) {
        this.currentUser = currentUser;
        this.userService = userService;
        this.users = users;
        this.defaultZone = User.zoneOrSystemDefault(defaultZone);
    }

    @GetMapping("/profile")
    public String profile(Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        model.addAttribute("me", me);
        model.addAttribute("themes", UserService.ALLOWED_THEMES);
        // Every zone the JVM's tzdb knows, sorted. Rendered as a plain <select>: ~600 options is a
        // few KB, and a searchable widget would be the first piece of JS on this page that the user
        // cannot fall back from if it fails to load.
        model.addAttribute("zones", ZoneId.getAvailableZoneIds().stream().sorted().toList());
        // What times actually resolve in right now, and where that answer came from — a guessed
        // zone is only fixable if the user can see it is a guess.
        model.addAttribute("effectiveZone", me.effectiveZone(defaultZone).getId());
        model.addAttribute("zoneIsChosen", me.getZoneId() != null);
        model.addAttribute("accountZone", me.getOidcZoneId());
        return "profile";
    }

    @PostMapping("/profile/theme")
    public String updateTheme(@RequestParam("theme") String theme, Principal principal) {
        var me = currentUser.resolve(principal);
        userService.updateTheme(me, theme);
        return "redirect:/profile";
    }

    /**
     * Set (or clear) the user's timezone. An empty value means "follow my account", which is a
     * choice in its own right rather than a missing one: it lets someone undo a wrong pick and go
     * back to tracking whatever their identity provider reports.
     *
     * <p>Validation lives in {@link User#chooseZone} — an unknown name is an
     * {@code IllegalArgumentException}, which {@code ApiExceptionHandler} turns into a 400 for the
     * fetch this form is submitted with.
     */
    @PostMapping("/profile/timezone")
    public String updateTimezone(@RequestParam(name = "zone", required = false) String zone,
                                 Principal principal) {
        var me = currentUser.resolve(principal);
        me.chooseZone(zone);
        users.save(me);
        return "redirect:/profile";
    }

    @PostMapping("/profile/tutorial/dismiss")
    @ResponseBody
    public ResponseEntity<Void> dismissTutorial(Principal principal) {
        var me = currentUser.resolve(principal);
        userService.dismissTutorial(me);
        return ResponseEntity.noContent().build();
    }
}
