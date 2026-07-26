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

package ai.intellistream.chat.service;

import ai.intellistream.chat.service.SearchService.ResultPage;
import ai.intellistream.chat.service.SearchService.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic behind the pager and the "showing 21–40 of 240" line.
 *
 * <p>Off-by-one errors in pagination are the classic silently-wrong feature: a Next link on the
 * last page, or a first-result number one out, looks fine on page one and is only noticed by
 * whoever pages to the end. All of it is pure, so all of it is pinned here.
 */
class SearchResultPageTest {

    /** A page of {@code n} results; their content is irrelevant to the arithmetic. */
    private static ResultPage page(int page, int pageSize, long total, int rows) {
        List<SearchHit> hits = Collections.nCopies(rows, null);
        return new ResultPage(hits, total, false, page, pageSize);
    }

    @Test
    void aSinglePageOfResultsOffersNoPagerAtAll() {
        var only = page(0, 20, 8, 8);
        assertThat(only.hasPrevious()).isFalse();
        assertThat(only.hasNext()).isFalse();
    }

    @Test
    void nextExistsUntilTheLastPageAndNotOnIt() {
        assertThat(page(0, 20, 41, 20).hasNext()).isTrue();
        assertThat(page(1, 20, 41, 20).hasNext()).isTrue();
        assertThat(page(2, 20, 41, 1).hasNext()).isFalse();
    }

    @Test
    void anExactlyFullLastPageDoesNotOfferAnEmptyOneAfterIt() {
        // The boundary that produces a Next link to nothing: 40 results, 20 per page.
        assertThat(page(1, 20, 40, 20).hasNext()).isFalse();
        assertThat(page(0, 20, 40, 20).hasNext()).isTrue();
    }

    @Test
    void previousExistsOnEveryPageButTheFirst() {
        assertThat(page(0, 20, 100, 20).hasPrevious()).isFalse();
        assertThat(page(1, 20, 100, 20).hasPrevious()).isTrue();
    }

    @Test
    void pagingStopsAtTheWindowEvenWhenMoreResultsExist() {
        // Lucene collects offset+size documents to serve a page, so an unbounded offset is a way to
        // ask the server for arbitrary work. The pager has to stop where the window does.
        int last = ai.intellistream.chat.search.MessageIndexService.MAX_WINDOW / 20 - 1;
        assertThat(page(last - 1, 20, 100_000, 20).hasNext()).isTrue();
        assertThat(page(last, 20, 100_000, 20).hasNext()).isFalse();
    }

    @Test
    void runningOutOfWindowIsDistinguishedFromRunningOutOfResults() {
        // The two look identical to a user — the Next link is gone — and mean opposite things.
        // Only one of them is worth telling them to narrow their search.
        assertThat(page(1, 20, 40, 20).windowExhausted()).isFalse();
        int last = ai.intellistream.chat.search.MessageIndexService.MAX_WINDOW / 20 - 1;
        assertThat(page(last, 20, 100_000, 20).windowExhausted()).isTrue();
    }

    @Test
    void theShowingRangeCountsFromOne() {
        var second = page(1, 20, 240, 20);
        assertThat(second.firstResultNumber()).isEqualTo(21);
        assertThat(second.lastResultNumber()).isEqualTo(40);
    }

    @Test
    void aShortFinalPageReportsItsOwnLength() {
        var last = page(2, 20, 41, 1);
        assertThat(last.firstResultNumber()).isEqualTo(41);
        assertThat(last.lastResultNumber()).isEqualTo(41);
    }

    @Test
    void anEmptyPageReportsNoRangeRatherThanZeroToMinusOne() {
        var empty = ResultPage.empty(0, 20);
        assertThat(empty.firstResultNumber()).isZero();
        assertThat(empty.lastResultNumber()).isZero();
        assertThat(empty.hasNext()).isFalse();
        assertThat(empty.hasPrevious()).isFalse();
    }
}
