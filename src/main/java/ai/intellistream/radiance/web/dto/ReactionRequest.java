package ai.intellistream.radiance.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReactionRequest(
        @NotBlank @Size(max = 64) String emoji
) {}
