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

package ai.intellistream.chat.search;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code @handle} tokens a message body refers to, lowercased. Feeds the indexed
 * {@code mentions} field, which is what makes {@code @bob} in a search query mean "messages that
 * mention Bob" rather than "messages Bob wrote".
 *
 * <h2>Why this doesn't call MentionService</h2>
 * {@code MentionService} answers a different question — <em>who do we notify?</em> — and its
 * answer is narrower on purpose: it parses the Markdown to skip code spans, resolves each handle
 * against {@code users}, and drops anyone who can't read a private channel. All three are correct
 * for a notification and wrong here:
 *
 * <ul>
 *   <li><b>Cost.</b> That path parses the whole body as CommonMark and hits the database once per
 *       handle. Indexing is on the message write path; a search field is not worth a second
 *       Markdown parse and an N-query resolve per message.</li>
 *   <li><b>Recall.</b> A handle inside a code span still <em>reads</em> as a mention to someone
 *       searching for where they were named, and a handle belonging to a since-deleted account is
 *       still what the message says. Indexing the literal token is the honest answer to "find
 *       messages that say @bob"; the cost of the difference is a search hit, not a notification.</li>
 *   <li><b>Access.</b> The private-channel filter in {@code MentionService} exists so a mention
 *       row can't leak a channel a user cannot read. It is not needed here because the search ACL
 *       is enforced separately and unconditionally, inside the Lucene query — see
 *       {@link MessageIndexService#searchAccessible}. Indexing the token for a non-member reveals
 *       nothing: they cannot reach the document.</li>
 * </ul>
 *
 * <p>The pattern is deliberately the same shape as {@code MentionService.MENTION}: 2–100 chars,
 * first and last character alphanumeric/underscore so trailing sentence punctuation isn't
 * captured, anchored on start-of-string or whitespace/paren/bracket so {@code foo@bar.com} is not
 * a mention of {@code bar.com}. If that rule ever changes there, change it here too — the two
 * disagreeing means a message notifies someone it can't be found by.
 */
final class MentionTokens {

    private static final Pattern HANDLE = Pattern.compile(
            "(?:^|(?<=[\\s(\\[]))@([A-Za-z0-9_][A-Za-z0-9_.-]{0,98}[A-Za-z0-9_])");

    private MentionTokens() {}

    /** Lowercased handles referred to by {@code body}, in order of first appearance. */
    static Set<String> in(String body) {
        if (body == null || body.indexOf('@') < 0) return Set.of();
        var out = new LinkedHashSet<String>();
        var m = HANDLE.matcher(body);
        while (m.find()) {
            out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
