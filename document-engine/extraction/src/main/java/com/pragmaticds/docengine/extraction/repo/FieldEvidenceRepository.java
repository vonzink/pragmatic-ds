package com.pragmaticds.docengine.extraction.repo;

import com.pragmaticds.docengine.extraction.domain.FieldEvidence;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FieldEvidenceRepository extends JpaRepository<FieldEvidence, UUID> {

    /** Batch load for the API projections: grouped by field by the caller, VALUE before LABEL. */
    List<FieldEvidence> findByExtractedFieldIdInOrderByExtractedFieldIdAscRoleAscOrdinalAsc(
            Collection<UUID> extractedFieldIds);

    /**
     * EXTRACTING retry idempotency: evidence rows go before their fields (FK). Explicit org
     * guard — bulk JPQL does not travel through {@code @TenantId} filtering.
     */
    @Modifying
    @Query(
            """
            delete from FieldEvidence e
            where e.orgId = :orgId and e.extractedFieldId in
                (select f.id from ExtractedField f
                 where f.orgId = :orgId and f.logicalDocumentId in
                    (select d.id from LogicalDocument d where d.packageId = :packageId))
            """)
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
