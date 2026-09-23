package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayoutElementRepository extends JpaRepository<LayoutElement, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<LayoutElement> findByIdAndOrgId(UUID id, UUID orgId);

    List<LayoutElement> findByPageIdOrderByOrdinal(UUID pageId);

    /**
     * The L2 read surface's sweep: one page's whole tree in reading order, org-guarded explicitly
     * because the caller is a controller, not the in-process engine. Rides the V4
     * {@code (org_id, page_id, ordinal)} index as-is.
     */
    List<LayoutElement> findByOrgIdAndPageIdOrderByOrdinal(UUID orgId, UUID pageId);

    List<LayoutElement> findByPageIdInOrderByPageIdAscOrdinalAsc(Collection<UUID> pageIds);

    /**
     * PARSING retry idempotency: clear the package's elements before re-persisting. org guard is
     * explicit — bulk JPQL does not travel through {@code @TenantId} filtering the way entity
     * loads do.
     */
    @Modifying
    @Query(
            """
            delete from LayoutElement e
            where e.orgId = :orgId
              and e.pageId in (select p.id from Page p where p.packageId = :packageId)
            """)
    int deleteByPackageId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
