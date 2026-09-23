package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.domain.FeedbackRating;
import com.pragmaticds.rag.domain.FeedbackSource;
import com.pragmaticds.rag.domain.RagAnswerFeedback;
import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.repository.RagAnswerFeedbackRepository;
import com.pragmaticds.rag.repository.RagTraceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Captures thumbs-up/down feedback on an answer. Rating and source are
 * validated against the canonical enums; the brain is resolved from the trace
 * (never trusted from the caller) so a row can only be attributed to the brain
 * that actually produced the answer. END_USER capture is idempotent per
 * (trace, session); ADMIN capture always writes (admin may re-rate).
 * Capture runs regardless of the per-brain learning switch.
 *
 * <p>Deliberately NOT method-{@code @Transactional}: the existsBy pre-check and the
 * DB-level {@code UNIQUE(trace_id, session_id)} together make an END_USER double-submit
 * idempotent, and catching the unique-violation to turn the race into the same no-op
 * only works when the save is not inside a rollback-only outer transaction.
 */
@Service
public class FeedbackService {

    private final RagTraceRepository traces;
    private final RagAnswerFeedbackRepository feedback;

    public FeedbackService(RagTraceRepository traces, RagAnswerFeedbackRepository feedback) {
        this.traces = traces;
        this.feedback = feedback;
    }

    public void record(UUID traceId, String rating, String source,
                        String reason, String sessionId, String createdBy) {
        if (traceId == null) {
            throw new IllegalArgumentException("traceId is required");
        }
        FeedbackRating.parse(rating);
        FeedbackSource src = FeedbackSource.parse(source);

        RagTrace trace = traces.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown trace: " + traceId));

        if (src == FeedbackSource.END_USER
                && feedback.existsByTraceIdAndSessionId(traceId, sessionId)) {
            return; // already rated by this session — idempotent no-op
        }

        RagAnswerFeedback row = new RagAnswerFeedback(
                traceId,
                trace.getBrainId(),
                rating,
                src.name(),
                reason,
                sessionId,
                createdBy);
        try {
            feedback.save(row);
        } catch (DataIntegrityViolationException raceOnUnique) {
            // A concurrent same-(trace, session) submit slipped past the existsBy
            // check; the UNIQUE(trace_id, session_id) constraint is the real backstop.
            // For END_USER this is the same idempotent no-op; anything else re-raises.
            if (src == FeedbackSource.END_USER) {
                return;
            }
            throw raceOnUnique;
        }
    }
}
