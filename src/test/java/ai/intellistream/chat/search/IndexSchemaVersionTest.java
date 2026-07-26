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

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema stamp, which decides whether a deployment rebuilds its Lucene index or reconciles it
 * in place. Both answers are expensive to get wrong: a stamp that fails to persist means a full
 * reindex on every boot, and one that persists when it shouldn't means a field that exists only on
 * documents written since the upgrade — a search that quietly stops finding older messages.
 */
class IndexSchemaVersionTest {

    @Test
    void aFreshDirectoryReadsAsOutOfDate(@TempDir Path dir) {
        var index = new MessageIndexService(dir.toString(), false);
        try {
            assertThat(index.schemaOutdated()).isTrue();
        } finally {
            index.close();
        }
    }

    @Test
    void theStampSurvivesOrdinaryCommitsAndAReopen(@TempDir Path dir) {
        // The load-bearing assumption. Lucene sources a commit's user data from the writer's live
        // commit data, so an implementation that set the stamp once and then let normal indexing
        // commit over it would silently lose it — and every restart would rebuild the whole index
        // from Postgres, which on a large deployment is minutes of degraded search per boot.
        var first = new MessageIndexService(dir.toString(), false);
        try {
            first.markSchemaCurrent();
            // async=false, so each of these commits.
            first.index(1L, 10L, "alice", "a message written after the stamp", List.of());
            first.indexConversationMessage(2L, 20L, "bob", "and a conversation message", List.of());
            first.delete(1L);
        } finally {
            first.close();
        }

        var reopened = new MessageIndexService(dir.toString(), false);
        try {
            assertThat(reopened.schemaOutdated()).isFalse();
        } finally {
            reopened.close();
        }
    }

    @Test
    void anIndexStampedWithAnEarlierSchemaReadsAsOutOfDate(@TempDir Path dir) throws IOException {
        // What a deployment on the previous build looks like: a complete, healthy index whose
        // documents simply predate a field. Nothing about it is missing or stale to a reconcile
        // sweep — every id is present — so the stamp is the only thing that can tell the difference
        // between "in sync" and "in sync with the wrong shape". Written here with raw Lucene rather
        // than through this class, because this class can only ever stamp the current version.
        try (var directory = FSDirectory.open(dir);
             var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            var doc = new Document();
            doc.add(new StringField("id", "1", Field.Store.YES));
            doc.add(new TextField("body", "indexed by the build before this one", Field.Store.NO));
            writer.addDocument(doc);
            writer.setLiveCommitData(Map.of(MessageIndexService.COMMIT_KEY_SCHEMA,
                    Integer.toString(MessageIndexService.SCHEMA_VERSION - 1)).entrySet());
            writer.commit();
        }

        var reopened = new MessageIndexService(dir.toString(), false);
        try {
            assertThat(reopened.isEmpty()).isFalse();       // a reconcile would find nothing to do…
            assertThat(reopened.schemaOutdated()).isTrue(); // …so this is what forces the rebuild
        } finally {
            reopened.close();
        }
    }

    @Test
    void anIndexWrittenWithoutAStampReadsAsOutOfDate(@TempDir Path dir) {
        // What every existing deployment looks like: documents on disk, no stamp, because the
        // build that wrote them had no concept of one.
        var legacy = new MessageIndexService(dir.toString(), false);
        try {
            legacy.index(1L, 10L, "alice", "written by a build that predates the stamp", List.of());
        } finally {
            legacy.close();
        }

        var reopened = new MessageIndexService(dir.toString(), false);
        try {
            assertThat(reopened.isEmpty()).isFalse();     // reconcile would find nothing to do…
            assertThat(reopened.schemaOutdated()).isTrue(); // …so this is what forces the rebuild
        } finally {
            reopened.close();
        }
    }
}
