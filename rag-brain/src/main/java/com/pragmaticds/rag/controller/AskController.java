package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.BrainResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Legacy admin/debug ask endpoint. Public website callers must use
 * /api/ai/public/{slug}/ask so they receive the narrowed public response.
 */
@RestController
@RequestMapping("/api/ai/${brain.slug:generic}")
public class AskController {

    private final AskService askService;
    private final BrainResolver brainResolver;
    private final String adminApiKey;

    public AskController(AskService askService, BrainResolver brainResolver, RagProperties properties) {
        this.askService = askService;
        this.brainResolver = brainResolver;
        this.adminApiKey = properties.admin().apiKey();
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request,
                                           @RequestParam(value = "brain", required = false) String brain,
                                           @RequestHeader(value = "X-Admin-Api-Key", required = false) String adminApiKey) {
        if (hasValidAdminApiKey(adminApiKey)) {
            UUID brainId = brainResolver.resolve(brain).getId();
            return ResponseEntity.ok(askService.ask(request, brainId, adminVisibility(request.surface())));
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Legacy /api/ai/{slug}/ask requires X-Admin-Api-Key; public callers must use /api/ai/public/{slug}/ask");
    }

    private boolean hasValidAdminApiKey(String providedApiKey) {
        if (providedApiKey == null || adminApiKey == null) {
            return false;
        }
        return MessageDigest.isEqual(
                providedApiKey.getBytes(StandardCharsets.UTF_8),
                adminApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private SourceVisibility adminVisibility(String requestedSurface) {
        if (requestedSurface == null || requestedSurface.isBlank()) {
            return SourceVisibility.INTERNAL;
        }
        try {
            return SourceVisibility.valueOf(requestedSurface.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return SourceVisibility.INTERNAL;
        }
    }
}
