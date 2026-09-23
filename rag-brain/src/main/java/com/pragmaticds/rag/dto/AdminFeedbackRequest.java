package com.pragmaticds.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminFeedbackRequest(
        @NotBlank @Size(max = 8) String rating,
        @Size(max = 2000) String reason
) {
}
