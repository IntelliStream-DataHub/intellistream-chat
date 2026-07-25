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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes a {@code ${devToolsEnabled}} model attribute to every Thymeleaf template
 * so {@code channels.html} (and friends) can conditionally include the in-browser
 * smoke-test runner via {@code <script th:if="${devToolsEnabled}" ... >}.
 *
 * <p>Off by default so a misconfigured production deploy never accidentally ships
 * the test runner. The {@code dev} profile sets {@code ichat.dev-tools.enabled=true}
 * in {@code application-dev.properties}; operators can also flip the flag at runtime
 * via the env var without redeploying.
 */
@ControllerAdvice(basePackages = "ai.intellistream.chat.web")
public class DevToolsModelAdvice {

    private final boolean enabled;

    public DevToolsModelAdvice(@Value("${ichat.dev-tools.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @ModelAttribute("devToolsEnabled")
    public boolean devToolsEnabled() {
        return enabled;
    }
}
