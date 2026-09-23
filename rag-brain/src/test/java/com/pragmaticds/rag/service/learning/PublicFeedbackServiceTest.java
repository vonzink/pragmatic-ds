package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.Conversation;
import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.dto.PublicFeedbackRequest;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.ConversationRepository;
import com.pragmaticds.rag.repository.RagTraceRepository;
import com.pragmaticds.rag.service.publicapi.PublicAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicFeedbackServiceTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final PublicAccessService access = mock(PublicAccessService.class);
    private final RagTraceRepository traces = mock(RagTraceRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final FeedbackService feedback = mock(FeedbackService.class);
    private final PublicFeedbackService service =
            new PublicFeedbackService(brains, access, traces, conversations, feedback);

    private Brain brain(UUID id) {
        Brain b = mock(Brain.class);
        when(b.getId()).thenReturn(id);
        return b;
    }

    private RagTrace trace(UUID traceId, UUID brainId, UUID conversationId) {
        RagTrace t = mock(RagTrace.class);
        when(t.getId()).thenReturn(traceId);
        when(t.getBrainId()).thenReturn(brainId);
        when(t.getConversationId()).thenReturn(conversationId);
        return t;
    }

    private Conversation conversation(UUID id, UUID brainId, String sessionId) {
        Conversation c = mock(Conversation.class);
        when(c.getId()).thenReturn(id);
        when(c.getBrainId()).thenReturn(brainId);
        when(c.getUserSessionId()).thenReturn(sessionId);
        return c;
    }

    @Test
    void ownedTraceRecordsFeedback() {
        UUID brainId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        Brain brain = brain(brainId);
        RagTrace trace = trace(traceId, brainId, convId);
        Conversation conversation = conversation(convId, brainId, "sess-1");
        when(brains.findBySlug("acme")).thenReturn(Optional.of(brain));
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));
        when(conversations.findById(convId)).thenReturn(Optional.of(conversation));

        service.submit("acme", "tok", "https://acme.test", "sess-1",
                new PublicFeedbackRequest(traceId, "UP", "great"));

        verify(access).validate(eq(brainId), eq("tok"), eq("https://acme.test"));
        verify(feedback).record(eq(traceId), eq("UP"), eq("END_USER"), eq("great"), eq("sess-1"), eq(null));
    }

    @Test
    void unknownSlugThrows() {
        when(brains.findBySlug("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.submit("nope", "tok",
                "https://acme.test", "sess-1", new PublicFeedbackRequest(UUID.randomUUID(), "UP", null)));
        verify(feedback, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceForDifferentBrainIsNotFound() {
        UUID brainId = UUID.randomUUID();
        UUID otherBrain = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        Brain brain = brain(brainId);
        RagTrace trace = trace(traceId, otherBrain, convId);
        when(brains.findBySlug("acme")).thenReturn(Optional.of(brain));
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submit("acme", "tok", "https://acme.test", "sess-1",
                        new PublicFeedbackRequest(traceId, "UP", null)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(feedback, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sessionNotOwningTraceIsNotFound() {
        UUID brainId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        Brain brain = brain(brainId);
        RagTrace trace = trace(traceId, brainId, convId);
        Conversation conversation = conversation(convId, brainId, "OTHER-sess");
        when(brains.findBySlug("acme")).thenReturn(Optional.of(brain));
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));
        when(conversations.findById(convId)).thenReturn(Optional.of(conversation));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submit("acme", "tok", "https://acme.test", "sess-1",
                        new PublicFeedbackRequest(traceId, "UP", null)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(feedback, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceWithoutConversationIsNotFound() {
        UUID brainId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        Brain brain = brain(brainId);
        RagTrace trace = trace(traceId, brainId, null);
        when(brains.findBySlug("acme")).thenReturn(Optional.of(brain));
        when(traces.findById(traceId)).thenReturn(Optional.of(trace));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submit("acme", "tok", "https://acme.test", "sess-1",
                        new PublicFeedbackRequest(traceId, "UP", null)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(feedback, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void accessValidationRunsBeforeOwnership() {
        UUID brainId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        Brain brain = brain(brainId);
        when(brains.findBySlug("acme")).thenReturn(Optional.of(brain));
        when(access.validate(eq(brainId), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Public token rejected"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submit("acme", "bad", "https://acme.test", "sess-1",
                        new PublicFeedbackRequest(traceId, "UP", null)));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(traces, never()).findById(any());
        verify(feedback, never()).record(any(), any(), any(), any(), any(), any());
    }
}
