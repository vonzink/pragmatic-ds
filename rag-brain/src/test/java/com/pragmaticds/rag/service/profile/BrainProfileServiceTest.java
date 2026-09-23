package com.pragmaticds.rag.service.profile;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.dto.BrainProfileRequest;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrainProfileServiceTest {

    private BrainRepository brains;
    private BrainProfileRepository profiles;
    private BrainProfileService service;

    @BeforeEach
    void setUp() {
        brains = mock(BrainRepository.class);
        profiles = mock(BrainProfileRepository.class);
        service = new BrainProfileService(brains, profiles);

        UUID brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, "test-brain", "Test Brain");
        BrainProfile profile = new BrainProfile();
        profile.setBrainId(brainId);
        profile.setMode(BrainMode.PUBLIC_SITE);

        when(brains.findById(any(UUID.class))).thenReturn(Optional.of(brain));
        when(profiles.findByBrainId(any(UUID.class))).thenReturn(Optional.of(profile));
        when(profiles.save(any(BrainProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void updateRejectsNullAllowedDomainEntries() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", Arrays.asList("example.com", null));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(brainId, req));

        assertEquals("allowedDomains must not contain null entries", ex.getMessage());
    }

    @Test
    void updateRejectsInvalidModeWithClearMessage() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("NOT_A_MODE", List.of("example.com"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(brainId, req));

        assertEquals(
                "mode must be one of PUBLIC_SITE, PRIVATE_SITE, SECURE_DEPLOYMENT",
                ex.getMessage());
    }

    @Test
    void getOrCreateDefaultsToNotPublished() {
        UUID brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, "test-brain", "Test Brain");
        when(brains.findById(brainId)).thenReturn(Optional.of(brain));
        when(profiles.findByBrainId(brainId)).thenReturn(Optional.empty());

        BrainProfile profile = service.getOrCreate(brainId);

        assertEquals(false, profile.isPublicEnabled());
        assertEquals(List.of(), profile.getAllowedDomains());
    }

    @Test
    void updateRejectsPublicEnabledWithoutAllowedDomains() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", List.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(brainId, req));

        assertEquals("allowedDomains is required when public access is enabled", ex.getMessage());
    }

    @Test
    void updateNormalizesAllowedDomainOriginsToHosts() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE",
                List.of("https://Example.com/path", "example.com", "https://www.example.com"));

        BrainProfile profile = service.update(brainId, req);

        assertEquals(List.of("example.com", "www.example.com"), profile.getAllowedDomains());
    }

    // Mirrors the confidenceTarget round-trip: PUTting a per-brain
    // retrievalConfidenceThreshold persists it onto the entity so a later read
    // reflects the override rather than silently falling back to the global default.
    @Test
    void updatePersistsRetrievalConfidenceThreshold() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", List.of("example.com"), 0.4);

        BrainProfile profile = service.update(brainId, req);

        assertEquals(0.4, profile.getRetrievalConfidenceThreshold());
    }

    // Null must round-trip too: unsetting the override reverts retrieval to the
    // global default rather than being coerced to some non-null sentinel.
    @Test
    void updatePersistsNullRetrievalConfidenceThresholdAsGlobalFallback() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", List.of("example.com"), null);

        BrainProfile profile = service.update(brainId, req);

        assertEquals(null, profile.getRetrievalConfidenceThreshold());
    }

    // The answer-confidence floor is the second per-brain gate: it applies to the
    // model's self-reported confidence after the answer comes back, where the
    // retrieval threshold applies to the top chunk score before the model runs.
    @Test
    void updatePersistsAnswerConfidenceFloor() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", List.of("example.com"), null, 0.5);

        BrainProfile profile = service.update(brainId, req);

        assertEquals(0.5, profile.getAnswerConfidenceFloor());
    }

    @Test
    void updatePersistsNullAnswerConfidenceFloorAsNoGate() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", List.of("example.com"), null, null);

        BrainProfile profile = service.update(brainId, req);

        assertEquals(null, profile.getAnswerConfidenceFloor());
    }

    private BrainProfileRequest request(String mode, List<String> allowedDomains) {
        return request(mode, allowedDomains, null);
    }

    private BrainProfileRequest request(String mode, List<String> allowedDomains, Double retrievalConfidenceThreshold) {
        return request(mode, allowedDomains, retrievalConfidenceThreshold, null);
    }

    private BrainProfileRequest request(String mode, List<String> allowedDomains,
                                        Double retrievalConfidenceThreshold, Double answerConfidenceFloor) {
        return new BrainProfileRequest(
                mode,
                "Purpose",
                "Audience",
                "Personality",
                "professional",
                "intermediate",
                "balanced",
                0.9,
                retrievalConfidenceThreshold,
                answerConfidenceFloor,
                "Ask one focused clarifying question.",
                "Escalate low-confidence requests.",
                "required_when_sources_used",
                "Recommend relevant pages.",
                "Source-grounded.",
                true,
                allowedDomains);
    }
}
