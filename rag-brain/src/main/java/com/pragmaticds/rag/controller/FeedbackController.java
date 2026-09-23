package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.PublicFeedbackRequest;
import com.pragmaticds.rag.service.learning.PublicFeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public 👍/👎 capture for the website widget. Same surface and auth as
 * {@link PublicAskController} (public brain token + Origin) plus the
 * X-Session-Id ownership check. Path is under /api/ai/public/* so the admin
 * key filter does not gate it and the existing RateLimitFilter applies.
 */
@RestController
@RequestMapping("/api/ai/public/{slug}")
public class FeedbackController {

    private final PublicFeedbackService service;

    public FeedbackController(PublicFeedbackService service) {
        this.service = service;
    }

    @PostMapping("/feedback")
    public ResponseEntity<Void> feedback(@PathVariable String slug,
                                         @RequestHeader(value = "X-Public-Brain-Token", required = false) String token,
                                         @RequestHeader(value = "Origin", required = false) String origin,
                                         @RequestHeader("X-Session-Id") String sessionId,
                                         @Valid @RequestBody PublicFeedbackRequest request) {
        service.submit(slug, token, origin, sessionId, request);
        return ResponseEntity.noContent().build();
    }
}
