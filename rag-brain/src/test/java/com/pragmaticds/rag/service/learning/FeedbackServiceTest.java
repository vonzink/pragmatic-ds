package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.domain.RagAnswerFeedback;
import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.repository.RagAnswerFeedbackRepository;
import com.pragmaticds.rag.repository.RagTraceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackServiceTest {

    private final RagTraceRepository traces = mock(RagTraceRepository.class);
    private final RagAnswerFeedbackRepository feedback = mock(RagAnswerFeedbackRepository.class);
    private final FeedbackService service = new FeedbackService(traces, feedback);

    private RagTrace traceWithBrain(UUID traceId, UUID brainId) {
        RagTrace t = mock(RagTrace.class);
        when(t.getId()).thenReturn(traceId);
        when(t.getBrainId()).thenReturn(brainId);
        return t;
    }

    @Test
    void recordEndUserPersistsWithBrainFromTrace() {
        UUID traceId = UUID.randomUUID();
        UUID brainId = UUID.randomUUID();
        RagTrace trace = traceWithBrain(traceId, brainId);
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));
        when(feedback.existsByTraceIdAndSessionId(traceId, "sess-1")).thenReturn(false);

        service.record(traceId, "UP", "END_USER", "clear answer", "sess-1", null);

        ArgumentCaptor<RagAnswerFeedback> captor = ArgumentCaptor.forClass(RagAnswerFeedback.class);
        verify(feedback).save(captor.capture());
        RagAnswerFeedback saved = captor.getValue();
        assertEquals(traceId, saved.getTraceId());
        assertEquals(brainId, saved.getBrainId());
        assertEquals("UP", saved.getRating());
        assertEquals("END_USER", saved.getSource());
        assertEquals("clear answer", saved.getReason());
        assertEquals("sess-1", saved.getSessionId());
    }

    @Test
    void recordEndUserIsIdempotentWhenAlreadyRated() {
        UUID traceId = UUID.randomUUID();
        UUID brainId = UUID.randomUUID();
        RagTrace trace = traceWithBrain(traceId, brainId);
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));
        when(feedback.existsByTraceIdAndSessionId(traceId, "sess-1")).thenReturn(true);

        service.record(traceId, "DOWN", "END_USER", null, "sess-1", null);

        verify(feedback, never()).save(any());
    }

    @Test
    void recordAdminAlwaysPersistsAndIgnoresIdempotencyCheck() {
        UUID traceId = UUID.randomUUID();
        UUID brainId = UUID.randomUUID();
        RagTrace trace = traceWithBrain(traceId, brainId);
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));

        service.record(traceId, "DOWN", "ADMIN", "wrong source cited", null, "admin@x");

        ArgumentCaptor<RagAnswerFeedback> captor = ArgumentCaptor.forClass(RagAnswerFeedback.class);
        verify(feedback).save(captor.capture());
        RagAnswerFeedback saved = captor.getValue();
        assertEquals("ADMIN", saved.getSource());
        assertEquals("admin@x", saved.getCreatedBy());
        assertEquals(brainId, saved.getBrainId());
        verify(feedback, never()).existsByTraceIdAndSessionId(any(), any());
    }

    @Test
    void endUserUniqueViolationRaceIsIdempotentNoOp() {
        // existsBy returns false (the check-then-insert race window), then the DB
        // UNIQUE(trace_id, session_id) rejects the concurrent duplicate insert.
        UUID traceId = UUID.randomUUID();
        RagTrace trace = traceWithBrain(traceId, UUID.randomUUID());
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));
        when(feedback.existsByTraceIdAndSessionId(traceId, "sess-1")).thenReturn(false);
        when(feedback.save(any())).thenThrow(new DataIntegrityViolationException("duplicate (trace_id, session_id)"));

        assertDoesNotThrow(() -> service.record(traceId, "UP", "END_USER", null, "sess-1", null));
    }

    @Test
    void adminUniqueViolationStillPropagates() {
        UUID traceId = UUID.randomUUID();
        RagTrace trace = traceWithBrain(traceId, UUID.randomUUID());
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));
        when(feedback.save(any())).thenThrow(new DataIntegrityViolationException("boom"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.record(traceId, "DOWN", "ADMIN", null, null, "admin@x"));
    }

    @Test
    void unknownTraceThrows() {
        UUID traceId = UUID.randomUUID();
        when(traces.findById(traceId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.record(traceId, "UP", "END_USER", null, "sess-1", null));
        verify(feedback, never()).save(any());
    }

    @Test
    void badRatingThrows() {
        UUID traceId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> service.record(traceId, "MEH", "END_USER", null, "sess-1", null));
        verify(feedback, never()).save(any());
    }

    @Test
    void badSourceThrows() {
        UUID traceId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> service.record(traceId, "UP", "SIDEWAYS", null, "sess-1", null));
        verify(feedback, never()).save(any());
    }
}
