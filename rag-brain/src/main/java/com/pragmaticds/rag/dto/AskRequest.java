package com.pragmaticds.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * Public website question request.
 */
public record AskRequest(
        UUID conversationId,           // optional: continue an existing conversation

        @NotBlank(message = "sessionId is required")
        @Size(max = 255)
        String sessionId,

        @NotBlank(message = "question is required")
        @Size(max = 2000, message = "question must be 2000 characters or fewer")
        String question,

        @Size(max = 50)
        String loanType,               // optional context, e.g. "conventional"

        @Size(max = 2)
        String state,                  // optional, e.g. "CO"

        @Size(max = 200)
        String pageRoute,              // optional page route for page-aware retrieval planning

        @Size(max = 20)
        String surface,                // optional audience: "PUBLIC" | "INTERNAL" | "BOTH"

        Map<String, String> facts      // optional user-provided context (e.g. from a clarification turn)
) {
    public AskRequest(UUID conversationId, String sessionId, String question,
                      String loanType, String state) {
        this(conversationId, sessionId, question, loanType, state, null, null, null);
    }

    public AskRequest(UUID conversationId, String sessionId, String question,
                      String loanType, String state, String pageRoute, String surface) {
        this(conversationId, sessionId, question, loanType, state, pageRoute, surface, null);
    }
}
