package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** Admin review queue: answers flagged for human follow-up. */
    Page<AuditLog> findByHumanEscalationRequiredTrueOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Dashboard search (no substring filter): one brain, newest first, optionally
     * escalated-only. Used when q is null/blank. Split from the q-variant to avoid
     * Postgres null-typing issues with LOWER() on an untyped NULL parameter.
     *
     * <p>Always brain-scoped: before this the Overview's "recent answers" and the
     * Audit screen showed every brain's traffic no matter which brain was selected.
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.brainId = :brainId
              AND (:escalatedOnly = false OR a.humanEscalationRequired = true)
            ORDER BY a.createdAt DESC
            """)
    Page<AuditLog> search(@Param("brainId") UUID brainId,
                          @Param("escalatedOnly") boolean escalatedOnly,
                          Pageable pageable);

    /**
     * Dashboard search with substring filter: one brain, newest first, optionally
     * escalated-only, question-substring (case-insensitive). Used when q is non-blank.
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.brainId = :brainId
              AND (:escalatedOnly = false OR a.humanEscalationRequired = true)
              AND LOWER(a.userQuestion) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY a.createdAt DESC
            """)
    Page<AuditLog> search(@Param("brainId") UUID brainId,
                          @Param("escalatedOnly") boolean escalatedOnly,
                          @Param("q") String q,
                          Pageable pageable);
}
