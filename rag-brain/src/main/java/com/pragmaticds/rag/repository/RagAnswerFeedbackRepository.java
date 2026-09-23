package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.RagAnswerFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface RagAnswerFeedbackRepository extends JpaRepository<RagAnswerFeedback, UUID> {

    boolean existsByTraceIdAndSessionId(UUID traceId, String sessionId);

    List<RagAnswerFeedback> findByBrainIdAndCreatedAtAfter(UUID brainId, OffsetDateTime after);

    /**
     * Rows this brain has not yet fed into a learning-job pass, newest lookback
     * first. The {@code processed_at IS NULL} predicate is the idempotency cursor:
     * once the job marks a row processed it is never re-tallied (V27), so a vote
     * burst moves a weight once and a rejected proposal is not re-emitted.
     */
    List<RagAnswerFeedback> findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(
            UUID brainId, OffsetDateTime after);
}
