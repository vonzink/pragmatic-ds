package com.pragmaticds.docengine.classification.repo;

import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPageId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogicalDocumentPageRepository
        extends JpaRepository<LogicalDocumentPage, LogicalDocumentPageId> {

    List<LogicalDocumentPage> findByLogicalDocumentIdOrderByOrdinal(UUID logicalDocumentId);

    List<LogicalDocumentPage> findByLogicalDocumentIdIn(Collection<UUID> logicalDocumentIds);

    /**
     * SPLITTING retry idempotency: links go before their documents. Explicit org guard — bulk
     * JPQL does not travel through {@code @TenantId} filtering.
     */
    @Modifying
    @Query(
            """
            delete from LogicalDocumentPage lp
            where lp.orgId = :orgId and lp.logicalDocumentId in
                (select d.id from LogicalDocument d where d.packageId = :packageId)
            """)
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);

    /** The document a page currently belongs to, if any (a page is in at most one). */
    Optional<LogicalDocumentPage> findByPageIdAndOrgId(UUID pageId, UUID orgId);

    /** All membership rows for a package's pages — the current grouping to diff against. */
    @Query("""
        select p from LogicalDocumentPage p
        where p.orgId = :orgId and p.logicalDocumentId in
          (select d.id from LogicalDocument d where d.packageId = :packageId and d.orgId = :orgId)
        """)
    List<LogicalDocumentPage> findByPackageIdAndOrgId(
            @Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
