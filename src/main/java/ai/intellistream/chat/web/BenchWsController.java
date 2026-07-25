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

import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Load-benchmark only ({@code @Profile("bench")} — never registered in production). Fans a message
 * out to a channel topic with NO persistence, Markdown rendering, or Lucene indexing, so a
 * benchmark can measure the pure WebSocket + simple-broker + fan-out capacity in isolation from
 * the per-message persistence pipeline (a DB insert + two CommonMark parses + jsoup + a per-message
 * IndexWriter.commit() fsync — ~50 ms/message locally, which otherwise dominates and hides the
 * broker's real ceiling). The normal path is {@code /app/channels/{id}/send}.
 */
@Controller
@Profile("bench")
public class BenchWsController {

    private final SimpMessagingTemplate broker;

    public BenchWsController(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    /** Broadcast {@code payload} to {@code /topic/channels/{roomId}} verbatim, wrapped so the load
     *  generator's {@code "bodyMarkdown":"…"} matcher works unchanged. No auth check, no DB. */
    @MessageMapping("/bench/{roomId}/echo")
    public void echo(@DestinationVariable Long roomId, @Payload String payload) {
        broker.convertAndSend("/topic/channels/" + roomId, new BenchBroadcast("bench", payload));
    }

    /** Typed payload (not a raw Map) so the convertAndSend(String, Object) overload is unambiguous. */
    public record BenchBroadcast(String type, String bodyMarkdown) {}
}
