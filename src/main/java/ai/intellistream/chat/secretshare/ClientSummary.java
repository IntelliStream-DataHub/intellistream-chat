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

package ai.intellistream.chat.secretshare;

import java.util.Locale;

/**
 * "Firefox on Windows" from a User-Agent header — what a secret's creator is told about an opener who
 * has no account.
 *
 * <p>Deliberately coarse, and deliberately not the header itself. The raw User-Agent is a
 * fingerprinting aid about a person outside the workspace, and it is attacker-controlled text; the
 * summary is one of a handful of fixed words for the browser and one for the system, so it carries
 * no more than the creator needs ("that was not the contractor's Mac") and nothing a client can
 * inject into a receipt. Order matters: Edge and Opera also say "Chrome", Chrome also says
 * "Safari", and iOS also says "Mac OS X".
 */
public final class ClientSummary {

    private ClientSummary() {}

    /** Only the start of a header is examined; real ones put everything that matters there. */
    private static final int SCAN_CHARS = 512;

    public static String of(String userAgent) {
        var ua = userAgent == null ? "" : userAgent;
        if (ua.length() > SCAN_CHARS) ua = ua.substring(0, SCAN_CHARS);
        ua = ua.toLowerCase(Locale.ROOT);
        return browser(ua) + " on " + system(ua);
    }

    private static String browser(String ua) {
        if (ua.contains("edg/") || ua.contains("edge/") || ua.contains("edga/") || ua.contains("edgios/")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("firefox/") || ua.contains("fxios/")) return "Firefox";
        if (ua.contains("chrome/") || ua.contains("crios/") || ua.contains("chromium/")) return "Chrome";
        if (ua.contains("safari/") && ua.contains("version/")) return "Safari";
        return "Unknown browser";
    }

    private static String system(String ua) {
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) return "iOS";
        if (ua.contains("android")) return "Android";
        if (ua.contains("windows")) return "Windows";
        if (ua.contains(" cros ")) return "ChromeOS";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("linux")) return "Linux";
        return "unknown system";
    }
}
