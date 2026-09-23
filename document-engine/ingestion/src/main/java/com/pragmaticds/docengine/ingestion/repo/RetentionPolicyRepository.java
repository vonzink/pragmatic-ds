package com.pragmaticds.docengine.ingestion.repo;

import com.pragmaticds.docengine.ingestion.domain.RetentionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicy, UUID> {

    /**
     * The org-wide default policy (the {@code document_category IS NULL} row the unique constraint
     * guarantees is singular). Explicit orgId alongside {@code @TenantId} — the house rule — so the
     * lookup is unambiguous even off-request.
     */
    Optional<RetentionPolicy> findByOrgIdAndDocumentCategoryIsNull(UUID orgId);
}
