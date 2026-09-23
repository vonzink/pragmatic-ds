package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainSourceWeight;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import com.pragmaticds.rag.domain.WeightEventStatus;
import com.pragmaticds.rag.dto.LearningDtos;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.learning.SourceWeightService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminLearningControllerTest {

    private final SourceWeightService weights = mock(SourceWeightService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final AdminLearningController controller =
            new AdminLearningController(weights, brainResolver);

    private static Brain brain() {
        Brain b = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        b.setActive(true);
        return b;
    }

    private static BrainSourceWeightEvent event(WeightEventStatus status) {
        return new BrainSourceWeightEvent(
                TestBrains.DEFAULT_ID,
                UUID.randomUUID(),
                1.0,
                null,
                1.12,
                6,
                status.name(),
                "learning job proposal",
                "learning-job");
    }

    private static BrainSourceWeight weight() {
        BrainSourceWeight w = new BrainSourceWeight(
                TestBrains.DEFAULT_ID, UUID.randomUUID(), 1.15, 9, "learning-job");
        return w;
    }

    @Test
    void pendingResolvesBrainAndMapsEvents() {
        BrainSourceWeightEvent e = event(WeightEventStatus.PENDING);
        when(brainResolver.resolve("generic")).thenReturn(brain());
        when(weights.pending(TestBrains.DEFAULT_ID)).thenReturn(List.of(e));

        List<LearningDtos.SourceWeightEventDto> out = controller.pending("generic");

        assertEquals(1, out.size());
        assertEquals(e.getDocumentId(), out.get(0).documentId());
        assertEquals("PENDING", out.get(0).status());
        verify(weights).pending(TestBrains.DEFAULT_ID);
    }

    @Test
    void weightsResolvesBrainAndMaps() {
        BrainSourceWeight w = weight();
        when(brainResolver.resolve(null)).thenReturn(brain());
        when(weights.weights(TestBrains.DEFAULT_ID)).thenReturn(List.of(w));

        List<LearningDtos.SourceWeightDto> out = controller.weights(null);

        assertEquals(1, out.size());
        assertEquals(w.getDocumentId(), out.get(0).documentId());
        assertEquals(1.15, out.get(0).weight());
        verify(weights).weights(TestBrains.DEFAULT_ID);
    }

    @Test
    void resetResolvesBrainAndDelegatesWithAdminActor() {
        when(brainResolver.resolve("generic")).thenReturn(brain());

        Map<String, Object> body = controller.reset("generic");

        assertEquals(true, body.get("reset"));
        assertEquals(TestBrains.DEFAULT_ID, body.get("brainId"));
        verify(weights).reset(TestBrains.DEFAULT_ID, "admin-api");
    }

    @Test
    void approveResolvesBrainAndDelegatesWithAdminActor() {
        UUID eventId = UUID.randomUUID();
        when(brainResolver.resolve("generic")).thenReturn(brain());

        Map<String, Object> body = controller.approve(eventId, "generic");

        assertEquals(true, body.get("approved"));
        assertEquals(eventId, body.get("eventId"));
        verify(weights).approve(eventId, TestBrains.DEFAULT_ID, "admin-api");
    }

    @Test
    void rejectResolvesBrainAndDelegatesWithAdminActor() {
        UUID eventId = UUID.randomUUID();
        when(brainResolver.resolve("generic")).thenReturn(brain());

        Map<String, Object> body = controller.reject(eventId, "generic");

        assertEquals(true, body.get("rejected"));
        assertEquals(eventId, body.get("eventId"));
        verify(weights).reject(eventId, TestBrains.DEFAULT_ID, "admin-api");
    }

    // --- R8: approve/reject must be brain-scoped, not just by raw eventId ----------

    @Test
    void approveRejectsWhenEventBelongsToADifferentBrainThanTheResolvedOne() {
        UUID eventId = UUID.randomUUID();
        UUID otherBrainId = UUID.randomUUID();
        when(brainResolver.resolve("generic")).thenReturn(brain());
        doThrow(new IllegalArgumentException(
                        "Weight event " + eventId + " does not belong to brain " + TestBrains.DEFAULT_ID))
                .when(weights).approve(eventId, TestBrains.DEFAULT_ID, "admin-api");

        assertThrows(IllegalArgumentException.class, () -> controller.approve(eventId, "generic"));

        verify(weights).approve(eventId, TestBrains.DEFAULT_ID, "admin-api");
        verify(weights, never()).approve(eventId, otherBrainId, "admin-api");
    }

    @Test
    void rejectRejectsWhenEventBelongsToADifferentBrainThanTheResolvedOne() {
        UUID eventId = UUID.randomUUID();
        when(brainResolver.resolve("generic")).thenReturn(brain());
        doThrow(new IllegalArgumentException(
                        "Weight event " + eventId + " does not belong to brain " + TestBrains.DEFAULT_ID))
                .when(weights).reject(eventId, TestBrains.DEFAULT_ID, "admin-api");

        assertThrows(IllegalArgumentException.class, () -> controller.reject(eventId, "generic"));

        verify(weights).reject(eventId, TestBrains.DEFAULT_ID, "admin-api");
    }
}
