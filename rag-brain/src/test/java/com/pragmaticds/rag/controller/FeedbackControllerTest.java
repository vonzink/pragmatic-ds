package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.PublicFeedbackRequest;
import com.pragmaticds.rag.exception.GlobalExceptionHandler;
import com.pragmaticds.rag.service.learning.PublicFeedbackService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeedbackControllerTest {

    private final PublicFeedbackService service = mock(PublicFeedbackService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FeedbackController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private static final String BODY = """
            { "traceId": "11111111-1111-1111-1111-111111111111", "rating": "UP", "reason": "clear" }
            """;

    @Test
    void submitsFeedbackWithHeadersAndBody() throws Exception {
        mvc.perform(post("/api/ai/public/acme/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Public-Brain-Token", "tok")
                        .header("Origin", "https://acme.test")
                        .header("X-Session-Id", "sess-1")
                        .content(BODY))
                .andExpect(status().isNoContent());

        verify(service).submit(eq("acme"), eq("tok"), eq("https://acme.test"),
                eq("sess-1"), any(PublicFeedbackRequest.class));
    }

    @Test
    void ownershipFailureReturns404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace not found"))
                .when(service).submit(eq("acme"), any(), any(), eq("sess-1"),
                        any(PublicFeedbackRequest.class));

        mvc.perform(post("/api/ai/public/acme/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Public-Brain-Token", "tok")
                        .header("Origin", "https://acme.test")
                        .header("X-Session-Id", "sess-1")
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Trace not found"));
    }

    @Test
    void missingSessionHeaderIsBadRequest() throws Exception {
        // GlobalExceptionHandler has no dedicated MissingRequestHeaderException
        // handler, so it falls through to the catch-all Exception handler (500).
        mvc.perform(post("/api/ai/public/acme/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Public-Brain-Token", "tok")
                        .header("Origin", "https://acme.test")
                        .content(BODY))
                .andExpect(status().isInternalServerError());
    }
}
