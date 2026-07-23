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

package ai.intellistream.threadorbit.web;

import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;

@Controller
public class ProfileController {

    private final CurrentUser currentUser;
    private final UserService userService;

    public ProfileController(CurrentUser currentUser, UserService userService) {
        this.currentUser = currentUser;
        this.userService = userService;
    }

    @GetMapping("/profile")
    public String profile(Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        model.addAttribute("me", me);
        model.addAttribute("themes", UserService.ALLOWED_THEMES);
        return "profile";
    }

    @PostMapping("/profile/theme")
    public String updateTheme(@RequestParam("theme") String theme, Principal principal) {
        var me = currentUser.resolve(principal);
        userService.updateTheme(me, theme);
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
