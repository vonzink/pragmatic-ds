package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.AdminFeedbackRequest;
import com.pragmaticds.rag.service.learning.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin rating on a specific trace (the review queue's UP/DOWN + reason).
 * Under /api/ai/admin/** so the AdminApiKeyFilter gates it. Records an
 * ADMIN-source feedback row; admin ratings carry session_id=NULL and always
 * write (admin may re-rate). Actor comes from X-Admin-Actor, default "admin".
 */
@RestController
@RequestMapping("/api/ai/admin/traces")
public class AdminTraceFeedbackController {

    private final FeedbackService service;

    public AdminTraceFeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping("/{id}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable("id") UUID traceId,
                                         @RequestHeader(value = "X-Admin-Actor", required = false) String actor,
                                         @Valid @RequestBody AdminFeedbackRequest request) {
        String resolvedActor = (actor == null || actor.isBlank()) ? "admin" : actor.strip();
        service.record(traceId, request.rating(), "ADMIN", request.reason(), null, resolvedActor);
        return ResponseEntity.noContent().build();
    }
}
