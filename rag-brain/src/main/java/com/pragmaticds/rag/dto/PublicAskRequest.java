package com.pragmaticds.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record PublicAskRequest(
        UUID conversationId,
        @NotBlank @Size(max = 255) String sessionId,
        @NotBlank @Size(max = 2000) String message,
        @Size(max = 200) String pageRoute,
        @Size(max = 20) String surface,
        Map<String, Object> facts
) {
    public PublicAskRequest(String sessionId, String message, String pageRoute,
                            String surface, Map<String, Object> facts) {
        this(null, sessionId, message, pageRoute, surface, facts);
    }
}
