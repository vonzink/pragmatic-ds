package com.pragmaticds.rag.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void preservesResponseStatusExceptionStatusAndMessage() {
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Legacy /api/ai/{slug}/ask requires X-Admin-Api-Key; public callers must use /api/ai/public/{slug}/ask");

        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(exception);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(exception.getReason(), response.getBody().get("error"));
    }
}
