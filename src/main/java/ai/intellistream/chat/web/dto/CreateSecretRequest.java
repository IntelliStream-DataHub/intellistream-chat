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

package ai.intellistream.chat.web.dto;

import ai.intellistream.chat.domain.SecretAudience;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A secret, already encrypted by the browser. The size bounds here are only a ceiling on what is
 * bound at all; {@code SecretCodec} checks the payload against {@code ichat.secrets.max-plaintext-bytes}.
 *
 * @param payload         base64url of {@code 0x01 ‖ IV ‖ ciphertext ‖ tag}
 * @param verifier        base64url of the 32-byte verifier derived from the link's key
 * @param label           optional plain-text note, shown only to the creator
 * @param lifetimeSeconds one of the offered lifetimes
 * @param audience        who may open it; null means {@link SecretAudience#SIGNED_IN}
 */
public record CreateSecretRequest(
        @NotBlank @Size(max = 1_000_000) String payload,
        @NotBlank @Size(max = 64) String verifier,
        @Size(max = 400) String label,
        long lifetimeSeconds,
        SecretAudience audience) {
}
