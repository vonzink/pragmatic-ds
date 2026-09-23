package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.Page;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PageRepository extends JpaRepository<Page, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<Page> findByIdAndOrgId(UUID id, UUID orgId);

    /** Batched: one query for a whole document's pages, never one per page. */
    List<Page> findByIdInAndOrgId(Collection<UUID> ids, UUID orgId);

    Optional<Page> findBySourceFileIdAndPageIndex(UUID sourceFileId, int pageIndex);

    List<Page> findBySourceFileIdOrderByPageIndex(UUID sourceFileId);

    /** Package-wide review ordering — the ordering the unique constraint guarantees. */
    List<Page> findByPackageIdOrderByPackagePageIndex(UUID packageId);

    /**
     * RENDERING retry idempotency: a failed attempt's partial rows commit with the FAILED stage
     * row (each attempt is one committed transaction), so the next attempt clears before
     * re-persisting. org guard is explicit — bulk JPQL does not travel through {@code @TenantId}
     * filtering the way entity loads do.
     */
    @Modifying
    @Query("delete from Page p where p.packageId = :packageId and p.orgId = :orgId")
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
