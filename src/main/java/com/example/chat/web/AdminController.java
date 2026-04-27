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

package com.example.chat.web;

import com.example.chat.repository.ChannelMemberRepository;
import com.example.chat.repository.ChannelRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.CurrentUser;
import com.example.chat.service.AppSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-only console: branding form (title + logo upload) plus read-only listings of
 * channels and users with last-active timestamps. Gated on {@code ROLE_ADMIN}, which is
 * granted exclusively by the Keycloak {@code chat-admin} realm role.
 */
@Controller
public class AdminController {

    static final long MAX_LOGO_BYTES = 256 * 1024;
    /**
     * Bitmap formats only. SVG was previously allowed but a malicious admin (or anyone with
     * a stolen admin session) could upload an SVG containing {@code <script>} tags; opening
     * {@code /branding/logo} directly would then run those scripts in the application's
     * origin. Modern browsers don't execute scripts in SVGs embedded via {@code <img>}, but
     * "open image in new tab" is enough to escape that protection. The bundled default logo
     * stays an SVG (it's static, trusted, and not user-supplied).
     */
    static final Set<String> ALLOWED_LOGO_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");

    private final AppSettingsService settings;
    private final ChannelRepository channels;
    private final UserRepository users;
    private final ChannelMemberRepository members;
    private final MessageRepository messages;
    private final CurrentUser currentUser;
    private final Path brandingDir;

    public AdminController(AppSettingsService settings,
                           ChannelRepository channels,
                           UserRepository users,
                           ChannelMemberRepository members,
                           MessageRepository messages,
                           CurrentUser currentUser,
                           @Value("${chat.branding.dir}") String brandingDirPath) {
        this.settings = settings;
        this.channels = channels;
        this.users = users;
        this.members = members;
        this.messages = messages;
        this.currentUser = currentUser;
        this.brandingDir = Path.of(brandingDirPath);
    }

    @GetMapping("/admin")
    @Transactional(readOnly = true)
    public String index(Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        var s = settings.current();

        // Channel summary: id, name, type, memberCount, messageCount.
        var channelRows = channels.findAll().stream()
                .map(c -> {
                    var row = new HashMap<String, Object>();
                    row.put("id", c.getId());
                    row.put("name", c.getName());
                    row.put("type", c.getType().name());
                    row.put("memberCount", members.findAllByChannelOrderByJoinedAtAsc(c).size());
                    row.put("messageCount", messages.findByChannelAndParentIsNullOrderByCreatedAtDesc(
                            c, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).size());
                    row.put("createdAt", c.getCreatedAt());
                    return row;
                })
                .toList();

        var exposeEmails = s.isExposeUserEmails();
        var userRows = users.findAll().stream()
                .map(u -> {
                    var row = new HashMap<String, Object>();
                    row.put("id", u.getId());
                    row.put("username", u.getUsername());
                    row.put("displayName", u.getDisplayName());
                    // Mask emails server-side when the admin toggles privacy on, so a screenshot
                    // or DOM inspection of the rendered page never reveals the full address.
                    row.put("email", exposeEmails ? u.getEmail() : maskEmail(u.getEmail()));
                    row.put("createdAt", u.getCreatedAt());
                    row.put("lastActiveAt", u.getLastActiveAt());
                    return row;
                })
                .toList();

        model.addAttribute("me", me);
        model.addAttribute("settings", s);
        model.addAttribute("channelRows", channelRows);
        model.addAttribute("userRows", userRows);
        return "admin";
    }

    @PostMapping("/admin/title")
    public String updateTitle(@RequestParam("title") String title, RedirectAttributes ra) {
        try {
            settings.updateTitle(title);
            ra.addFlashAttribute("flash", "Title updated.");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/logo")
    public String uploadLogo(@RequestParam("logo") MultipartFile file, RedirectAttributes ra) throws IOException {
        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("error", "Pick a file to upload.");
            return "redirect:/admin";
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            ra.addFlashAttribute("error", "Logo too large (max " + (MAX_LOGO_BYTES / 1024) + " KB).");
            return "redirect:/admin";
        }
        var contentType = file.getContentType();
        if (contentType == null || !ALLOWED_LOGO_TYPES.contains(contentType.toLowerCase())) {
            ra.addFlashAttribute("error", "Unsupported file type. Use PNG, JPG, or WebP.");
            return "redirect:/admin";
        }
        Files.createDirectories(brandingDir);
        var ext = pickExtension(contentType);
        var filename = "logo-" + UUID.randomUUID() + "." + ext;
        var dest = brandingDir.resolve(filename);
        try (var in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        // Best-effort cleanup of the previous file (skip silently on miss).
        var previous = settings.current().getLogoPath();
        settings.setLogo(filename, contentType);
        if (previous != null) {
            try { Files.deleteIfExists(brandingDir.resolve(previous)); } catch (IOException ignored) { }
        }
        ra.addFlashAttribute("flash", "Logo updated.");
        return "redirect:/admin";
    }

    @PostMapping("/admin/logo/clear")
    public String clearLogo(RedirectAttributes ra) {
        var previous = settings.current().getLogoPath();
        settings.clearLogo();
        if (previous != null) {
            try { Files.deleteIfExists(brandingDir.resolve(previous)); } catch (IOException ignored) { }
        }
        ra.addFlashAttribute("flash", "Logo reset to default.");
        return "redirect:/admin";
    }

    /**
     * Toggle whether the user table on this page renders raw email addresses or masks them.
     * Default is on (raw); flip off for compliance / privacy-conscious deployments. The
     * checkbox in the form is unchecked → no value submitted → off; checked → "true".
     */
    @PostMapping("/admin/email-visibility")
    public String setEmailVisibility(@RequestParam(value = "expose", required = false) String expose,
                                     RedirectAttributes ra) {
        boolean enable = "true".equalsIgnoreCase(expose) || "on".equalsIgnoreCase(expose);
        settings.setExposeUserEmails(enable);
        ra.addFlashAttribute("flash", enable
                ? "User emails are now visible on the admin page."
                : "User emails are now masked on the admin page.");
        return "redirect:/admin";
    }

    /**
     * Mask an email like {@code alice@example.com} as {@code al…@example.com}. Single-letter
     * local parts become {@code a…@example.com}; emails without an "@" return as just "—" so
     * we never accidentally render an unstructured raw value.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "—";
        var at = email.indexOf('@');
        if (at <= 0) return "—";
        var local = email.substring(0, at);
        var domain = email.substring(at);
        var keep = Math.min(2, local.length());
        return local.substring(0, keep) + "…" + domain;
    }

    private static String pickExtension(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

}
