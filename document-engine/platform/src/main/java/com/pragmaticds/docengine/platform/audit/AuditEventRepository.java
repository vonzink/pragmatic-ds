package com.pragmaticds.docengine.platform.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only audit events. Derived queries travel through {@code @TenantId} filtering, so no
 * bulk {@code @Modifying} query (and thus no explicit-org guard) exists — the table is never
 * updated or deleted from.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** The house rule: load by id AND org — never findById. */
    Optional<AuditEvent> findByIdAndOrgId(Long id, UUID orgId);

    /** One subject's audit history, most recent first (the {@code (org_id, subject_*)} index). */
    List<AuditEvent> findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            String subjectType, UUID subjectId);
}
