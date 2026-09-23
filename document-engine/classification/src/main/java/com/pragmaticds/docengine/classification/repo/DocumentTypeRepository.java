package com.pragmaticds.docengine.classification.repo;

import com.pragmaticds.docengine.classification.domain.DocumentType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code document_type} reads. No {@code @TenantId} on the entity (global rows have {@code org_id
 * NULL}), so the org guard is explicit in every query: an org sees its OWN rows plus the GLOBAL
 * built-ins — exactly the V6 RLS policy, enforced twice.
 */
public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    /** Active types visible to this org: own rows and global built-ins. */
    @Query(
            """
            select t from DocumentType t
            where t.active = true and (t.orgId = :orgId or t.orgId is null)
            """)
    List<DocumentType> findActiveVisibleTo(@Param("orgId") UUID orgId);
}
