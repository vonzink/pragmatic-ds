package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.LearningDtos;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.learning.SourceWeightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin surface for the learning loop review queue and weights (spec §5).
 * Under /api/ai/admin so AdminApiKeyFilter (X-Admin-Api-Key) gates every route.
 * Brain-scoped routes take ?brain=<slug> resolved via BrainResolver (default
 * brain when omitted), matching AdminToolAdapterConfigController.
 */
@RestController
@RequestMapping("/api/ai/admin/learning")
public class AdminLearningController {

    private static final String ACTOR = "admin-api";

    private final SourceWeightService weights;
    private final BrainResolver brainResolver;

    public AdminLearningController(SourceWeightService weights, BrainResolver brainResolver) {
        this.weights = weights;
        this.brainResolver = brainResolver;
    }

    /** Review queue: PENDING weight-change proposals awaiting approve/reject. */
    @GetMapping("/pending")
    public List<LearningDtos.SourceWeightEventDto> pending(
            @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        return weights.pending(brainId).stream()
                .map(LearningDtos.SourceWeightEventDto::from)
                .toList();
    }

    /** Current learned per-source weights for the brain. */
    @GetMapping("/weights")
    public List<LearningDtos.SourceWeightDto> weights(
            @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        return weights.weights(brainId).stream()
                .map(LearningDtos.SourceWeightDto::from)
                .toList();
    }

    /** One-click "back to neutral": drop all learned weights (writes REVERTED events). */
    @PostMapping("/reset")
    public Map<String, Object> reset(
            @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        weights.reset(brainId, ACTOR);
        return Map.of("reset", true, "brainId", brainId);
    }

    /** Approve a PENDING proposal: applies proposed_weight, writes an APPROVED event. */
    @PostMapping("/approve/{eventId}")
    public Map<String, Object> approve(
            @PathVariable UUID eventId,
            @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        weights.approve(eventId, brainId, ACTOR);
        return Map.of("approved", true, "eventId", eventId);
    }

    /** Reject a PENDING proposal: discards it, writes a REJECTED event. */
    @PostMapping("/reject/{eventId}")
    public Map<String, Object> reject(
            @PathVariable UUID eventId,
            @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        weights.reject(eventId, brainId, ACTOR);
        return Map.of("rejected", true, "eventId", eventId);
    }
}
