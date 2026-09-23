package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.BrainSourceWeight;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only admin views for the learning loop: the review queue / audit trail
 * (SourceWeightEventDto) and the current per-source weights (SourceWeightDto).
 * Plain projections — no secrets, no internal entity leakage.
 */
public final class LearningDtos {

    private LearningDtos() {}

    /** One row of the weight-change audit trail / review queue. */
    public record SourceWeightEventDto(
            UUID id, UUID brainId, UUID documentId,
            Double oldWeight, Double newWeight, Double proposedWeight,
            int evidenceCount, String status, String reason,
            String actor, OffsetDateTime createdAt) {

        public static SourceWeightEventDto from(BrainSourceWeightEvent e) {
            return new SourceWeightEventDto(
                    e.getId(), e.getBrainId(), e.getDocumentId(),
                    e.getOldWeight(), e.getNewWeight(), e.getProposedWeight(),
                    e.getEvidenceCount(), e.getStatus(), e.getReason(),
                    e.getActor(), e.getCreatedAt());
        }
    }

    /** A current learned per-(brain,document) retrieval-score multiplier. */
    public record SourceWeightDto(
            UUID brainId, UUID documentId, double weight,
            int feedbackCount, OffsetDateTime updatedAt, String updatedBy) {

        public static SourceWeightDto from(BrainSourceWeight w) {
            return new SourceWeightDto(
                    w.getBrainId(), w.getDocumentId(), w.getWeight(),
                    w.getFeedbackCount(), w.getUpdatedAt(), w.getUpdatedBy());
        }
    }
}
