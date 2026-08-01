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

package ai.intellistream.chat.web;

import ai.intellistream.chat.calls.CallProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts "can this deployment make calls" in front of every template.
 *
 * <p>An advice rather than a model attribute on the conversation route, because two different
 * pages need it for two different reasons: the DM page decides whether to draw the call buttons,
 * and every page with a socket decides whether to include the panel that receives an incoming one.
 * Threading it through each controller would mean each controller remembering to.
 *
 * <p>These carry no credentials — only whether the feature is on. The TURN credential is minted per
 * call at {@code /api/calls/ice}, because it expires and a page does not.
 */
@ControllerAdvice(basePackages = "ai.intellistream.chat.web")
public class CallModelAdvice {

    private final CallProperties properties;

    public CallModelAdvice(CallProperties properties) {
        this.properties = properties;
    }

    /** False whenever calls cannot actually be placed — including "no TURN server configured". */
    @ModelAttribute("callsAvailable")
    public boolean callsAvailable() {
        return properties.isConfigured();
    }

    /** Whether to offer the camera button alongside the handset. */
    @ModelAttribute("callsVideo")
    public boolean callsVideo() {
        return properties.isConfigured() && properties.isVideo();
    }
}
