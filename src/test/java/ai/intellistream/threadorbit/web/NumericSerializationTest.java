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

package ai.intellistream.threadorbit.web;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N9 regression guard: numeric error/count payloads must serialize as JSON numbers, not strings.
 * The global {@code Long → ToStringSerializer} (for id precision) only catches the boxed type, so
 * these DTOs use primitive {@code long}. If someone reverts them to boxed {@code Long} / a bare
 * {@code Map<String,Long>}, the clients' {@code typeof === 'number'} guards break silently.
 */
class NumericSerializationTest {

    /** Same module the app registers in JacksonConfig. */
    private static JsonMapper mapperLikeProd() {
        var module = new SimpleModule("LongIdsAsStrings");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        return JsonMapper.builder().addModule(module).build();
    }

    record ConventionSample(Long id, long count) {}

    @Test
    void boxedLongIsAStringButPrimitiveLongIsANumber() {
        var json = mapperLikeProd().writeValueAsString(new ConventionSample(42L, 7L));
        assertThat(json).contains("\"id\":\"42\"");   // ids: string
        assertThat(json).contains("\"count\":7");     // counts: number
    }

    @Test
    void uploadTooLargeMaxBytesSerializesAsNumber() {
        var body = new ApiExceptionHandler.UploadTooLargeBody(
                "upload_too_large", "File too large (max 50 MiB)", 52_428_800L, "trace-1", "err");
        var json = mapperLikeProd().writeValueAsString(body);
        assertThat(json).contains("\"maxBytes\":52428800");
        assertThat(json).doesNotContain("\"maxBytes\":\"52428800\"");
    }

    @Test
    void unreadCountSerializesAsNumber() {
        var json = mapperLikeProd().writeValueAsString(new MentionRestController.UnreadCount(5L));
        assertThat(json).contains("\"unread\":5");
        assertThat(json).doesNotContain("\"unread\":\"5\"");
    }
}
