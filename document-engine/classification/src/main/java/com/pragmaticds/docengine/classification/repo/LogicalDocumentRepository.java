package com.pragmaticds.docengine.classification.repo;

import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogicalDocumentRepository extends JpaRepository<LogicalDocument, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<LogicalDocument> findByIdAndOrgId(UUID id, UUID orgId);

    List<LogicalDocument> findByPackageIdOrderByOrdinal(UUID packageId);

    /**
     * SPLITTING retry idempotency: clear a package's documents before re-splitting. Explicit org
     * guard — bulk JPQL does not travel through {@code @TenantId} filtering.
     */
    @Modifying
    @Query("delete from LogicalDocument d where d.packageId = :packageId and d.orgId = :orgId")
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
