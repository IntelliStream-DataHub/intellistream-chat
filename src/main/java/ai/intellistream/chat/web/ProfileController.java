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

import ai.intellistream.chat.domain.DateStyle;
import ai.intellistream.chat.domain.HourCycle;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.i18n.TimeFormats;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

@Controller
public class ProfileController {

    private final CurrentUser currentUser;
    private final UserService userService;
    private final UserRepository users;
    /** Resolves the viewer's zone, locale and clock conventions — see {@link TimeFormats}. */
    private final TimeFormats timeFormats;

    public ProfileController(CurrentUser currentUser,
                             UserService userService,
                             UserRepository users,
                             TimeFormats timeFormats) {
        this.currentUser = currentUser;
        this.userService = userService;
        this.users = users;
        this.timeFormats = timeFormats;
    }

    @GetMapping("/profile")
    public String profile(Principal principal, Locale locale, Model model) {
        var me = currentUser.resolve(principal);
        model.addAttribute("me", me);
        model.addAttribute("themes", UserService.ALLOWED_THEMES);
        // Every zone the JVM's tzdb knows, sorted. Rendered as a plain <select> — six hundred
        // options is a few KB — and upgraded in the browser to a searchable combobox that shows
        // each zone's current UTC offset. The <select> is not replaced but hidden, and only after
        // the combobox is on the page, so a script that fails to load leaves a control that still
        // works rather than a text box wired to nothing.
        model.addAttribute("zones", ZoneId.getAvailableZoneIds().stream().sorted().toList());
        // What times actually resolve in right now, and where that answer came from — a guessed
        // zone is only fixable if the user can see it is a guess.
        var view = timeFormats.into(model, me, locale);
        model.addAttribute("effectiveZone", view.zoneId());
        model.addAttribute("zoneSource", view.sourceToken());
        model.addAttribute("zoneIsChosen", me.getZoneId() != null);
        model.addAttribute("hourCycles", HourCycle.values());
        model.addAttribute("dateStyles", DateStyle.values());
        // Live samples under each picker. A format is far easier to recognise than to read off an
        // enum name, and this is the one page where the answer to "what will this look like" should
        // not require going and looking at a message.
        model.addAttribute("sampleNow", Instant.now());
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

    /**
     * How this user reads a clock and a date. Separate from the zone because they are separate
     * questions with separate right answers: knowing somebody is in Oslo does not tell you they
     * want a 24-hour clock, and plenty of people browsing in English from anywhere do.
     *
     * <p>Unknown values coerce to {@code AUTO} in {@link HourCycle#parse} rather than 400-ing —
     * a stale {@code <select>} from before a deploy should land the user on "follow my locale",
     * which is where they were anyway.
     */
    @PostMapping("/profile/time-format")
    public String updateTimeFormat(@RequestParam(name = "hourCycle", required = false) String hourCycle,
                                   @RequestParam(name = "dateStyle", required = false) String dateStyle,
                                   Principal principal) {
        var me = currentUser.resolve(principal);
        userService.updateTimeFormat(me, HourCycle.parse(hourCycle), DateStyle.parse(dateStyle));
        return "redirect:/profile";
    }

    /**
     * The browser's own answer to which zone it is in, posted by {@code time-format.js} on load.
     *
     * <p>Deliberately fire-and-forget from the client's point of view: it returns 204 whether or
     * not anything changed, and the page has already re-rendered its own timestamps by the time
     * this lands. What the write buys is the <em>next</em> page load being right server-side, and
     * {@code /remind me at 14:00} meaning 14:00 where the person actually is.
     *
     * <p>An unknown or malformed zone is accepted and ignored ({@link User#noteDetectedZone}), not
     * rejected. This is a hint from an environment we do not control; a browser with an
     * out-of-date tzdb should not produce an error in anybody's log.
     */
    @PostMapping("/profile/timezone/detected")
    @ResponseBody
    public ResponseEntity<Void> detectedTimezone(@RequestParam(name = "zone", required = false) String zone,
                                                 Principal principal) {
        var me = currentUser.resolve(principal);
        userService.recordDetectedZone(me, zone);
        return ResponseEntity.noContent().build();
    }

    /** Wave away the "we could not work out your time zone" banner without picking one. */
    @PostMapping("/profile/timezone/prompt/dismiss")
    @ResponseBody
    public ResponseEntity<Void> dismissZonePrompt(Principal principal) {
        var me = currentUser.resolve(principal);
        userService.dismissZonePrompt(me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/profile/tutorial/dismiss")
    @ResponseBody
    public ResponseEntity<Void> dismissTutorial(Principal principal) {
        var me = currentUser.resolve(principal);
        userService.dismissTutorial(me);
        return ResponseEntity.noContent().build();
    }
}
