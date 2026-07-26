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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attachment filenames as searchable text, tested against the raw index so the analysis and the
 * query shape are under test with no service layer in the way.
 *
 * <p>Two things here are easy to get wrong and expensive to notice late. The first is
 * <b>tokenisation</b>: {@link org.apache.lucene.analysis.standard.StandardAnalyzer} treats
 * {@code quarterly-report.pdf} as the two tokens {@code quarterly} and {@code report.pdf}, because
 * UAX#29 does not break a word at a full stop between letters — so an index that reused the body's
 * analyzer would answer "report" with nothing and look like it worked for anyone who happened to
 * test by pasting the whole filename. The second is that the filename clause must sit
 * <b>underneath</b> the query's boolean structure rather than beside it, or a {@code -term}
 * exclusion stops excluding.
 */
class FilenameIndexTest {

    private Path dir;
    private MessageIndexService index;

    @BeforeEach
    void open() throws IOException {
        dir = Files.createTempDirectory("ichat-filename-index-");
        // async=false: every write is visible and committed immediately.
        index = new MessageIndexService(dir.toString(), false);
    }

    @AfterEach
    void close() {
        index.close();
    }

    private static final long ROOM = 5L;

    private List<Long> find(String query) {
        return index.searchInChannel(ROOM, query, List.of(), 100);
    }

    @Test
    void theWholeFilenameFindsTheMessageThatCarriesIt() {
        index.index(1L, ROOM, "alice", "", List.of("quarterly-report.pdf"));

        assertThat(find("quarterly-report.pdf")).containsExactly(1L);
    }

    @Test
    void anyWordOfTheFilenameFindsItToo() {
        // The reason the filename field has its own analyzer. With the body's, "report" and "pdf"
        // both come back empty and only the pasted-in-full query works.
        index.index(1L, ROOM, "alice", "", List.of("quarterly-report.pdf"));

        assertThat(find("report")).containsExactly(1L);
        assertThat(find("quarterly")).containsExactly(1L);
        assertThat(find("pdf")).containsExactly(1L);
    }

    @Test
    void digitsInAFilenameSurviveTokenisation() {
        // Half of a workspace's filenames are dates and invoice numbers, which is why the tokeniser
        // splits on non-alphanumerics rather than using the letters-only one.
        index.index(1L, ROOM, "alice", "", List.of("2026-Q3_invoice.xlsx"));

        assertThat(find("invoice")).containsExactly(1L);
        assertThat(find("2026")).containsExactly(1L);
    }

    @Test
    void aMessageWithoutThatFileIsNotAMatch() {
        // The other half of every assertion above: without this, "found it" and "found everything"
        // are indistinguishable.
        index.index(1L, ROOM, "alice", "", List.of("quarterly-report.pdf"));
        index.index(2L, ROOM, "bob", "an ordinary message with no files", List.of());

        assertThat(find("quarterly")).containsExactly(1L);
    }

    @Test
    void bodyAndFilenameAreBothSearchedByTheSameQuery() {
        index.index(1L, ROOM, "alice", "nothing relevant here", List.of("mainsail-diagram.png"));
        index.index(2L, ROOM, "bob", "the mainsail is torn", List.of());

        assertThat(find("mainsail")).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void aMessageCarryingSeveralFilesIsOneHitFoundByAnyOfThem() {
        // The reason filenames are a multi-valued field on the message rather than documents of
        // their own: a document per attachment would return this message once per matching file,
        // with a hit count and a pagination window to match.
        index.index(1L, ROOM, "alice", "the release pack",
                List.of("changelog.md", "runbook.pdf", "screenshots.zip"));

        assertThat(find("changelog")).containsExactly(1L);
        assertThat(find("runbook")).containsExactly(1L);
        assertThat(find("screenshots")).containsExactly(1L);
    }

    @Test
    void reindexingWithoutTheFilenamesRemovesThem() {
        // Not a nicety — it is how a tombstone takes effect, and it is why every write path has to
        // pass the current set rather than an empty one it did not think about.
        index.index(1L, ROOM, "alice", "here you go", List.of("payroll-2026.csv"));
        assertThat(find("payroll")).containsExactly(1L);

        index.index(1L, ROOM, "alice", "here you go", List.of());

        assertThat(find("payroll")).isEmpty();
        assertThat(find("here you go")).containsExactly(1L); // the message itself is still there
    }

    @Test
    void anExclusionAppliesToFilenamesAsWellAsBodies() {
        // The multi-field expansion happens per term, under the boolean structure. Parsing the two
        // fields separately and OR-ing the results instead would let this message back in through
        // its filename clause, which never saw the negation.
        index.index(1L, ROOM, "alice", "shipping the release", List.of("draft-plan.pdf"));
        index.index(2L, ROOM, "bob", "shipping the release", List.of("final-plan.pdf"));

        assertThat(find("release -draft")).containsExactly(2L);
    }

    @Test
    void aHyphenatedQueryStillRequiresEveryPartOfIt() {
        // Lucene's MultiFieldQueryParser combines the per-term groups with SHOULD, so a single
        // input token that analyses into several terms — which every filename does — silently turns
        // "both of these" into "either of these". Here that would make "inscope-57" match the
        // message that only has the 57.
        index.index(1L, ROOM, "alice", "inscope-57 in the first room", List.of());
        index.index(2L, ROOM, "bob", "a totally different message", List.of("ledger-57.csv"));

        assertThat(find("inscope-57")).containsExactly(1L);
    }

    @Test
    void aConversationDocumentCarriesFilenamesTheSameWay() {
        index.indexConversationMessage(1L, 42L, "alice", "", List.of("holiday-photos.zip"));

        assertThat(index.searchInConversation(42L, "holiday", List.of(), 10)).containsExactly(1L);
    }

    // ---------- highlightFilename: how a hit says why it is a hit ----------

    @Test
    void aMatchedFilenameComesBackWholeWithTheMatchMarked() {
        var marked = index.highlightFilename("report", "quarterly-report.pdf");

        assertThat(marked).isEqualTo("quarterly-<mark>report</mark>.pdf");
    }

    @Test
    void aFilenameThatDidNotMatchComesBackNull() {
        // This is the signal the results page reads: null means "not the reason this row is here",
        // so a message's other attachments stay out of the way.
        assertThat(index.highlightFilename("report", "unrelated-photo.png")).isNull();
    }

    @Test
    void highlightFilenameEscapesHtml() {
        // Filenames are user input and the page renders this with utext / innerHTML.
        var marked = index.highlightFilename("report", "<script>report</script>.pdf");

        assertThat(marked).contains("<mark>report</mark>");
        assertThat(marked).contains("&lt;script&gt;");
        assertThat(marked).doesNotContain("<script>");
    }

    @Test
    void highlightFilenameIgnoresBlankInput() {
        assertThat(index.highlightFilename("report", null)).isNull();
        assertThat(index.highlightFilename("report", "  ")).isNull();
        assertThat(index.highlightFilename("", "quarterly-report.pdf")).isNull();
    }
}
