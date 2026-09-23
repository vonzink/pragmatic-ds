package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.Conversation;
import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.dto.PublicFeedbackRequest;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.ConversationRepository;
import com.pragmaticds.rag.repository.RagTraceRepository;
import com.pragmaticds.rag.service.publicapi.PublicAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

/**
 * Public 👍/👎 capture. Enforces the SAME auth as public ask (public brain
 * token + Origin via {@link PublicAccessService}), then confirms the caller's
 * session owns the trace: the trace must belong to this brain and the trace's
 * conversation must carry the caller's X-Session-Id. Any ownership mismatch is
 * a 404 (never reveal that a trace exists). On success it delegates to
 * {@link FeedbackService#record} as an END_USER rating.
 */
@Service
public class PublicFeedbackService {

    private final BrainRepository brains;
    private final PublicAccessService access;
    private final RagTraceRepository traces;
    private final ConversationRepository conversations;
    private final FeedbackService feedback;

    public PublicFeedbackService(BrainRepository brains,
                                 PublicAccessService access,
                                 RagTraceRepository traces,
                                 ConversationRepository conversations,
                                 FeedbackService feedback) {
        this.brains = brains;
        this.access = access;
        this.traces = traces;
        this.conversations = conversations;
        this.feedback = feedback;
    }

    public void submit(String slug, String token, String origin,
                       String sessionId, PublicFeedbackRequest req) {
        Brain brain = brains.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + slug));
        // Same token + Origin gate as public ask. Throws 401 / IllegalArgumentException.
        access.validate(brain.getId(), token, origin);

        RagTrace trace = traces.findById(req.traceId())
                .orElseThrow(PublicFeedbackService::notFound);
        // Trace must belong to this brain surface.
        if (!Objects.equals(trace.getBrainId(), brain.getId())) {
            throw notFound();
        }
        // Session must own the trace's conversation.
        UUID conversationId = trace.getConversationId();
        if (conversationId == null) {
            throw notFound();
        }
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null
                || !Objects.equals(conversation.getUserSessionId(), sessionId)) {
            throw notFound();
        }

        feedback.record(req.traceId(), req.rating(), "END_USER", req.reason(), sessionId, null);
    }

    private static ResponseStatusException notFound() {
        // 404 for every ownership/existence failure — do not reveal the trace exists.
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace not found");
    }
}
