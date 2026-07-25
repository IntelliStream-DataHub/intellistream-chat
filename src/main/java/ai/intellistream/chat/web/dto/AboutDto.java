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

import java.time.Instant;
import java.util.List;

/**
 * Payload behind the About dialog.
 *
 * <p><b>Two tiers, deliberately.</b> Every authenticated user sees the product name, the version
 * and the build time — enough to answer "what am I running?" and to quote in a bug report. Only
 * an admin sees {@link #server} and {@link #components}, because an exact inventory of runtime
 * and library versions is a shopping list for anyone matching a compromised account against
 * published CVEs. It is a small disclosure either way, since this is open source and the versions
 * are in {@code build.gradle.kts}, but a deployment can be behind on patches and the running
 * numbers are the ones that matter to an attacker.
 *
 * <p>Both fields are {@code null} rather than empty for a non-admin, so the client can tell
 * "you're not allowed to see this" apart from "there was nothing to report".
 *
 * <p>Licence fields are shown to everyone. Telling a user what terms the software they are using
 * is under is the point of the notice, not a privilege, and Apache-2.0 asks that the licence
 * travel with the work.
 */
public record AboutDto(
        String name,
        String version,
        Instant buildTime,
        String license,
        String licenseUrl,
        String copyright,
        ServerInfo server,
        List<Component> components) {

    /** Host and runtime facts. Admin-only. */
    public record ServerInfo(
            String javaVersion,
            String javaVendor,
            String jvm,
            String os,
            String arch,
            int availableProcessors,
            long maxHeapMb,
            long uptimeSeconds,
            String timeZone) {}

    /** One row of the component table. {@code version} is null when a jar ships no manifest entry. */
    public record Component(String name, String version) {}
}
