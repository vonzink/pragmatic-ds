package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.LayoutElementSpan;
import com.pragmaticds.docengine.parsing.domain.LayoutElementSpanId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayoutElementSpanRepository
        extends JpaRepository<LayoutElementSpan, LayoutElementSpanId> {

    List<LayoutElementSpan> findByLayoutElementIdOrderByOrdinal(UUID layoutElementId);

    /**
     * Batch variant for extraction's page projection (Phase 5): every cell's links in one query
     * instead of one per element, grouped by the caller. Ordered by element then link ordinal so
     * per-element span order is the wire's spanOrdinals order.
     */
    List<LayoutElementSpan> findByLayoutElementIdInOrderByLayoutElementIdAscOrdinalAsc(
            java.util.Collection<UUID> layoutElementIds);

    /**
     * PARSING retry idempotency: links go before their elements (FK). org guard explicit for the
     * same reason as every bulk JPQL delete in this package.
     */
    @Modifying
    @Query(
            """
            delete from LayoutElementSpan l
            where l.orgId = :orgId
              and l.layoutElementId in (
                  select e.id from LayoutElement e
                  where e.pageId in (select p.id from Page p where p.packageId = :packageId))
            """)
    int deleteByPackageId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
