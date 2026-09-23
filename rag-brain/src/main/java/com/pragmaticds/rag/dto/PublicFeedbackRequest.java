package com.pragmaticds.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PublicFeedbackRequest(
        @NotNull UUID traceId,
        @NotBlank @Size(max = 8) String rating,
        @Size(max = 2000) String reason
) {
}
