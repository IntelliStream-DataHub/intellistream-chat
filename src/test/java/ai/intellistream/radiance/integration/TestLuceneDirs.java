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

package ai.intellistream.radiance.integration;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Registers a unique-per-context Lucene directory under {@code chat.search.lucene-dir}.
 * Each integration test class needs its own dir so concurrent / cached Spring contexts
 * don't fight over the IndexWriter lock at {@code ./data/lucene}.
 */
final class TestLuceneDirs {

    private TestLuceneDirs() {}

    static void register(DynamicPropertyRegistry registry) {
        registry.add("radiance.search.lucene-dir", () -> {
            try {
                return Files.createTempDirectory("chat-lucene-it-").toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
