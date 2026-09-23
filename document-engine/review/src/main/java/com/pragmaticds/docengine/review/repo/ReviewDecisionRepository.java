package com.pragmaticds.docengine.review.repo;

import com.pragmaticds.docengine.review.domain.ReviewDecision;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only review decisions. Every read is a derived query that travels through {@code @TenantId}
 * filtering. The ONE bulk {@code @Modifying} path is the retention purge: {@code subject_id} carries
 * no foreign key (V8), so nothing cascades when a package is purged — the decisions must be deleted
 * explicitly by subject id, and that bulk JPQL bypasses {@code @TenantId}, so it takes an explicit
 * org guard (the house rule for bulk deletes).
 */
public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {

    /** The house rule: load by id AND org — never findById. */
    Optional<ReviewDecision> findByIdAndOrgId(UUID id, UUID orgId);

    /** The latest decision of a kind for one subject — e.g. the current CORRECT overlaying a field. */
    Optional<ReviewDecision> findFirstBySubjectTypeAndSubjectIdAndActionOrderByDecidedAtDesc(
            String subjectType, UUID subjectId, String action);

    /**
     * Latest-first decisions of a kind across many subjects — the overlay batches every current
     * field's CORRECT in one query, then keeps the first (newest) per subject.
     */
    List<ReviewDecision> findBySubjectTypeAndSubjectIdInAndActionOrderByDecidedAtDesc(
            String subjectType, Collection<UUID> subjectIds, String action);

    /**
     * Full decision history across a set of subjects (a document id plus its field ids), newest
     * first — the "original value -> corrected value -> user -> timestamp" strip, straight from
     * the truth with no projection table to drift.
     */
    List<ReviewDecision> findBySubjectIdInOrderByDecidedAtDesc(Collection<UUID> subjectIds);

    /**
     * Retention purge: delete every decision whose subject is one of a package's extracted_field /
     * logical_document / page ids. Explicit org guard — bulk JPQL does not travel through
     * {@code @TenantId} filtering. Returns the row count for the purge audit.
     */
    @Modifying
    @Query(
            "delete from ReviewDecision d where d.orgId = :orgId and d.subjectId in :subjectIds")
    int deleteBySubjectIdInAndOrgId(
            @Param("subjectIds") Collection<UUID> subjectIds, @Param("orgId") UUID orgId);
}
