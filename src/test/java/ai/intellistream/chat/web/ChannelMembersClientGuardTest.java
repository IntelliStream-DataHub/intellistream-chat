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

package ai.intellistream.chat.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard for the "Channel members" panel in {@code chat.js}. The project deliberately
 * doesn't run JS in tests (no Node, no headless browser — see CLAUDE.md), so this is the
 * closest we can get to catching a class of regression where the panel block refers to a
 * {@code window.ChatKit} symbol before chat.js has destructured it locally.
 *
 * <p>The bug this guards against: chat.js destructures {@code buildAvatarEl} from
 * {@code window.ChatKit} ~660 lines BELOW the channel-members panel block. When the panel
 * naively calls {@code buildAvatarEl(...)} the page throws {@code ReferenceError} on first
 * paint, the catch swallows it, and the user sees an empty panel — exactly what was
 * reported. Resolution: the panel must use a locally-scoped reference (either fetched from
 * {@code window.ChatKit} at the top of the block, or a defensive fallback).
 *
 * <p>This is a fragile string-scan test by design — it pins a specific regression. If the
 * shape of chat.js changes substantially, update the assertions to match the new contract.
 */
class ChannelMembersClientGuardTest {

    private static String chatJs() throws Exception {
        // chat.js was modularized into static/js/chat/{index,shared,chrome}.js. The members
        // panel block we're guarding still lives in index.js (it hasn't been carved out yet).
        return Files.readString(Path.of("src/main/resources/static/js/chat/index.js"));
    }

    @Test
    void chatJsContainsChannelMembersPanelBlock() throws Exception {
        // Sanity: the block we're guarding actually exists. If chat.js gets refactored
        // and this section moves or is renamed, we want a clear failure here rather than
        // a silently-skipped subsequent assertion.
        assertThat(chatJs())
                .contains("channel-members-toggle")
                .contains("channel-members-panel")
                .contains("/api/channels/' + activeChannelId + '/members");
    }

    @Test
    void channelMembersPanelResolvesAvatarFactoryDefensively() throws Exception {
        // Extract just the channel-members block so other call sites of buildAvatarEl
        // (rendered messages, threads, etc., which run AFTER the main destructure) don't
        // pollute our scan.
        var src = chatJs();
        var startMarker = "// ---------- Channel members panel ----------";
        var endMarker = "// ---------- Invite (admin) ----------";
        int start = src.indexOf(startMarker);
        int end = src.indexOf(endMarker);
        assertThat(start).as("channel members block start marker").isGreaterThan(-1);
        assertThat(end).as("channel members block end marker").isGreaterThan(start);
        var block = src.substring(start, end);

        // The block must establish its own avatar factory (either from window.ChatKit at
        // its top, or a defensive fallback). It must NOT call a bare `buildAvatarEl(`
        // identifier — that would resolve against chat.js's top-level scope, where
        // buildAvatarEl is destructured ~660 lines later and is undefined here.
        assertThat(block)
                .as("members panel must source its avatar factory locally, not from a yet-undeclared top-level binding")
                .containsAnyOf("window.ChatKit", "ChatKit.buildAvatarEl");
        assertThat(block)
                .as("must not call buildAvatarEl( as a bare top-level identifier in this block")
                .doesNotContain(" buildAvatarEl(")
                .doesNotContain("=buildAvatarEl(");
    }

    @Test
    void channelMembersPanelEagerlyPopulatesCount() throws Exception {
        // The "👥 N" button should show the count even before the user opens the panel —
        // the block calls loadMembers() once on first paint. Without this the badge stays
        // as the placeholder "…" forever.
        var src = chatJs();
        var startMarker = "// ---------- Channel members panel ----------";
        var endMarker = "// ---------- Invite (admin) ----------";
        var block = src.substring(src.indexOf(startMarker), src.indexOf(endMarker));

        // loadMembers is defined inside the block; assert it's also called inside the
        // block (eager load) — not just bound to the toggle handler.
        assertThat(block).contains("const loadMembers");
        // Two occurrences expected: the definition + at least one direct invocation
        // (eager load and/or via setMembersOpen). One alone means the eager load
        // is missing.
        var occurrences = block.split("loadMembers", -1).length - 1;
        assertThat(occurrences)
                .as("loadMembers should be defined AND eagerly invoked")
                .isGreaterThanOrEqualTo(3);
    }
}
