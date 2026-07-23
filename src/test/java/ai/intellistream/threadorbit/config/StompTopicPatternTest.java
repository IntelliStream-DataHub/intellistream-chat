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

package ai.intellistream.threadorbit.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two destination patterns that gate STOMP SUBSCRIBE authorization. A regression
 * here is silent and catastrophic — anything that fails to match is allowed through
 * unchecked by the interceptor (see {@link StompAuthorizationConfig#preSend}). The class is
 * written to pin both the shape of legitimate destinations (bigint ids, optional `/typing`
 * subtopic) and the fact that the pre-bigint UUID shape is no longer accepted as ids.
 */
class StompTopicPatternTest {

    @Test
    void channelTopicMatchesNumericIds() {
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/channels/1").matches()).isTrue();
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/channels/42").matches()).isTrue();
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/channels/9223372036854775807").matches()).isTrue();
    }

    @Test
    void channelTopicMatchesTypingSubtopic() {
        var m = StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/channels/7/typing");
        assertThat(m.matches()).isTrue();
        assertThat(m.group(1)).isEqualTo("7");
    }

    @Test
    void conversationTopicMatchesNumericIds() {
        assertThat(StompAuthorizationConfig.CONVERSATION_TOPIC.matcher("/topic/conversations/3").matches()).isTrue();
        assertThat(StompAuthorizationConfig.CONVERSATION_TOPIC.matcher("/topic/conversations/3/typing").matches()).isTrue();
    }

    @Test
    void rejectsNonNumericIds() {
        // Pre-bigint UUID format must not slip back in — the interceptor only authorises
        // destinations that match these patterns. Anything else falls into the "unknown
        // destination" pass-through, so accidental UUID-shape matches would bypass auth.
        var uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/channels/" + uuid).matches()).isFalse();
        assertThat(StompAuthorizationConfig.CONVERSATION_TOPIC.matcher("/topic/conversations/" + uuid).matches()).isFalse();
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/channels/abc").matches()).isFalse();
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/channels/").matches()).isFalse();
    }

    @Test
    void rejectsUnrelatedDestinations() {
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/topic/users/1").matches()).isFalse();
        assertThat(StompAuthorizationConfig.CONVERSATION_TOPIC.matcher("/topic/channels/1").matches()).isFalse();
        assertThat(StompAuthorizationConfig.CHANNEL_TOPIC.matcher("/app/channels/1/send").matches()).isFalse();
    }
}
