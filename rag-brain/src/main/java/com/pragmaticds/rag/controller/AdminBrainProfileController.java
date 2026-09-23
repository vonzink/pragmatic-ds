package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.BrainProfileDto;
import com.pragmaticds.rag.dto.BrainProfileRequest;
import com.pragmaticds.rag.service.publicapi.PublicAccessService;
import com.pragmaticds.rag.service.profile.BrainProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai/admin/brains/{brainId}/profile")
public class AdminBrainProfileController {

    private final BrainProfileService service;
    private final PublicAccessService publicAccessService;

    public AdminBrainProfileController(BrainProfileService service, PublicAccessService publicAccessService) {
        this.service = service;
        this.publicAccessService = publicAccessService;
    }

    @GetMapping
    public BrainProfileDto get(@PathVariable UUID brainId) {
        return BrainProfileDto.from(service.getOrCreate(brainId));
    }

    @PutMapping
    public BrainProfileDto put(@PathVariable UUID brainId, @RequestBody BrainProfileRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        validate(req);
        return BrainProfileDto.from(service.update(brainId, req));
    }

    @PostMapping("/public-token")
    public PublicTokenDto rotatePublicToken(@PathVariable UUID brainId) {
        return new PublicTokenDto(publicAccessService.rotateToken(brainId));
    }

    private static void validate(BrainProfileRequest req) {
        if (req.confidenceTarget() < 0.0 || req.confidenceTarget() > 1.0) {
            throw new IllegalArgumentException("confidenceTarget must be between 0 and 1");
        }
        if (req.retrievalConfidenceThreshold() != null
                && (req.retrievalConfidenceThreshold() < 0.0 || req.retrievalConfidenceThreshold() > 1.0)) {
            throw new IllegalArgumentException("retrievalConfidenceThreshold must be between 0 and 1");
        }
        if (req.answerConfidenceFloor() != null
                && (req.answerConfidenceFloor() < 0.0 || req.answerConfidenceFloor() > 1.0)) {
            throw new IllegalArgumentException("answerConfidenceFloor must be between 0 and 1");
        }
    }

    public record PublicTokenDto(String token) {}
}
