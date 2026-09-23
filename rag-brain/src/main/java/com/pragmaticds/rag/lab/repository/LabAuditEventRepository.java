package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only Lab audit trail.
 *
 * <p>Insert and brain-scoped read only. The table itself refuses {@code UPDATE} and {@code DELETE},
 * so the inherited mutating methods fail closed at the database rather than silently rewriting
 * history — a purge appends a value-free tombstone instead.
 */
public interface LabAuditEventRepository extends JpaRepository<LabAuditEvent, UUID> {

    List<LabAuditEvent> findByBrainIdOrderByCreatedAtDesc(UUID brainId);

    List<LabAuditEvent> findByBrainIdAndSubjectIdOrderByCreatedAtDesc(UUID brainId, UUID subjectId);
}
