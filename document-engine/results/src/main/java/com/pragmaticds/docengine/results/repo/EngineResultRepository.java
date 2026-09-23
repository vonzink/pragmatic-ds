package com.pragmaticds.docengine.results.repo;

import com.pragmaticds.docengine.results.domain.EngineResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-explicit reads for immutable package result history. */
public interface EngineResultRepository extends JpaRepository<EngineResult, UUID> {

    Optional<EngineResult> findByProcessingJobIdAndParseGenerationAndOrgId(
            UUID processingJobId, int parseGeneration, UUID orgId);

    List<EngineResult> findByPackageIdAndOrgIdOrderByRevisionAsc(UUID packageId, UUID orgId);

    Optional<EngineResult> findByPackageIdAndRevisionAndOrgId(
            UUID packageId, int revision, UUID orgId);

    @Query(
            "select max(r.revision) from EngineResult r"
                    + " where r.packageId = :packageId and r.orgId = :orgId")
    Optional<Integer> findMaximumRevisionByPackageIdAndOrgId(
            @Param("packageId") UUID packageId, @Param("orgId") UUID orgId);

    /**
     * The parse-once reuse probe's candidate scan: this org's descriptors over the exact
     * prospective source-set digest, newest first (V19's {@code engine_result_org_source_set_idx};
     * runs under the FORCE-RLS tenant SELECT policy as well as the explicit org bind).
     *
     * <p>Bounded by {@code limit} so one pathological duplicate count cannot make an upload
     * arbitrarily slow: the scan is per-upload work (each distinct candidate package costs a
     * REQUIRES_NEW transaction), and the semaphore bounds CONCURRENCY, not the depth of a single
     * scan. Newest-first means the bound only ever drops OLDER candidates, and reuse serves the
     * newest survivor, so the winner is never among the dropped rows in practice; the caller logs
     * whenever the bound is reached.
     */
    List<EngineResult> findByOrgIdAndSourceSetSha256OrderByCreatedAtDesc(
            UUID orgId, String sourceSetSha256, Limit limit);
}
