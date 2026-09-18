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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status and open routes take requests from anyone, so their body is read by hand under a hard
 * cap instead of being handed to the JSON converter, which would read all of it first.
 */
class SecretShareRestControllerTest {

    private static MockHttpServletRequest body(String json) {
        var request = new MockHttpServletRequest("POST", "/api/secrets/x/open");
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void theExactShapeIsAccepted() {
        var key = "Ab-_".repeat(10) + "xyz";
        assertThat(SecretShareRestController.readVerifier(body("{\"verifier\":\"" + key + "\"}"))).isEqualTo(key);
        assertThat(SecretShareRestController.readVerifier(body(" { \"verifier\" : \"" + key + "\" } \n"))).isEqualTo(key);
    }

    @Test
    void anythingLargerThanTheCapIsRefusedWithoutReadingItAll() {
        var huge = "{\"verifier\":\"" + "A".repeat(43) + "\",\"pad\":\"" + "x".repeat(5_000_000) + "\"}";
        assertThat(SecretShareRestController.readVerifier(body(huge))).isNull();
    }

    @Test
    void anyOtherShapeIsRefused() {
        var key = "A".repeat(43);
        assertThat(SecretShareRestController.readVerifier(body(""))).isNull();
        assertThat(SecretShareRestController.readVerifier(body("{}"))).isNull();
        assertThat(SecretShareRestController.readVerifier(body("{\"verifier\":\"" + key + "\",\"x\":1}"))).isNull();
        assertThat(SecretShareRestController.readVerifier(body("{\"verifier\":\"" + key + "A\"}"))).isNull();
        assertThat(SecretShareRestController.readVerifier(body("{\"verifier\":\"" + key.substring(1) + "+\"}"))).isNull();
        assertThat(SecretShareRestController.readVerifier(body("verifier=" + key))).isNull();
    }
}
