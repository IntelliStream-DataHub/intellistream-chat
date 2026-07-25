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

package ai.intellistream.chat.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Serialize boxed {@link Long} as a JSON string while leaving primitive {@code long} alone.
 *
 * <p>JavaScript's {@code Number} loses precision past 2<sup>53</sup>, and Jackson otherwise emits
 * every Java {@code Long} as a JSON number — which the browser silently rounds when an id ever
 * exceeds that bound. We sidestep the future-bug class entirely by emitting entity ids as
 * strings on the wire.
 *
 * <p>The split is intentional and follows the convention enforced across the DTO layer:
 * boxed {@link Long} fields are always entity ids ({@code id}, {@code channelId}, {@code parentId},
 * {@code messageId}, {@code conversationId}, etc.), while primitive {@code long} fields are
 * always counts / versions ({@code replyCount}, {@code unreadCount}, {@code voteCount},
 * {@code avatarVersion}, {@code sizeBytes}) — those stay as JSON numbers so client-side
 * arithmetic on them still works.
 *
 * <p>Inbound JSON: Jackson's default {@code Long} deserializer accepts both JSON strings and
 * JSON numbers, so request bodies that still send a numeric id keep working — only outbound
 * serialization changes.
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer longIdsAsStrings() {
        var module = new SimpleModule("LongIdsAsStrings");
        // Registering against Long.class only catches the boxed type; primitive `long` fields
        // continue to use Jackson's default number serializer.
        module.addSerializer(Long.class, ToStringSerializer.instance);
        return builder -> builder.addModule(module);
    }
}
