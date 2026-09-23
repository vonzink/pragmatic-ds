package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.PublicAskRequest;
import com.pragmaticds.rag.exception.GlobalExceptionHandler;
import com.pragmaticds.rag.service.publicapi.PublicAskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicAskControllerTest {

    private final PublicAskService service = mock(PublicAskService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new PublicAskController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void missingPublicTokenReturnsUnauthorizedFromPublicAccessValidation() throws Exception {
        when(service.ask(eq("generic"), isNull(), eq("https://example.com"), any(PublicAskRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Public token is required"));

        mvc.perform(post("/api/ai/public/generic/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://example.com")
                        .content("""
                                {
                                  "sessionId": "s1",
                                  "message": "What is PMI?",
                                  "pageRoute": "/",
                                  "surface": "PUBLIC",
                                  "facts": {}
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Public token is required"));

        verify(service).ask(eq("generic"), isNull(), eq("https://example.com"), any(PublicAskRequest.class));
    }
}
