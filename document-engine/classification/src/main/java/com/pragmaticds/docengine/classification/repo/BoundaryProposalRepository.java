package com.pragmaticds.docengine.classification.repo;

import com.pragmaticds.docengine.classification.domain.BoundaryProposal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only ledger access (V26). Derived reads travel through {@code @TenantId} filtering; the
 * bulk delete is the retention purge's and carries its own explicit org guard, since bulk JPQL
 * does not travel through {@code @TenantId}.
 */
public interface BoundaryProposalRepository extends JpaRepository<BoundaryProposal, UUID> {

    /** The rows a replayed split reads: this package's proposals with the given verdict. */
    List<BoundaryProposal> findByPackageIdAndVerdict(UUID packageId, String verdict);

    List<BoundaryProposal> findByPackageIdOrderByCreatedAtAsc(UUID packageId);

    boolean existsByPackageIdAndJobId(UUID packageId, UUID jobId);

    /** Retention purge only. */
    @Modifying
    @Query(
            "delete from BoundaryProposal p where p.packageId = :packageId and p.orgId = :orgId")
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
