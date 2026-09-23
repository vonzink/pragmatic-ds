package com.pragmaticds.docengine.orchestration.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessingStageRepository extends JpaRepository<ProcessingStage, UUID> {

    /**
     * All attempt rows for a job in creation order — pipeline order, with retries adjacent.
     * Derived (criteria) queries are tenant-filtered by {@code @TenantId}, so this never crosses
     * an org even without an explicit orgId parameter.
     */
    List<ProcessingStage> findByJobIdOrderByCreatedAtAsc(UUID jobId);

    /**
     * Re-extraction re-kick: delete EXTRACTING and every downstream result-producing position, so
     * {@code StageRunner.isDone} cannot reuse stale finalization after fields are regenerated.
     * RENDERING..SPLITTING keep their SUCCEEDED rows and stay skipped. Scoped by {@code jobId} (a
     * globally unique PK), so this touches exactly one job's rows.
     *
     * <p>BOUNDARY_EXTRACTION is DELIBERATELY absent from the list even though it sits between
     * SPLITTING and EXTRACTING: deleting its row would make a regroup re-kick re-RUN it, and its
     * re-split deletes and recreates the very documents the reviewer just reshaped. Its finished
     * row is what keeps resume skipping it (and the stage service refuses human-shaped packages
     * besides — defence in depth, not redundancy).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            delete from ProcessingStage s
             where s.jobId = :jobId
               and s.stage in (com.pragmaticds.docengine.orchestration.ProcessingStatus.EXTRACTING,
                               com.pragmaticds.docengine.orchestration.ProcessingStatus.AI_EXTRACTION,
                               com.pragmaticds.docengine.orchestration.ProcessingStatus.FINALIZING,
                               com.pragmaticds.docengine.orchestration.ProcessingStatus.VALIDATING_DATA,
                               com.pragmaticds.docengine.orchestration.ProcessingStatus.AI_REVIEW)
            """)
    int deleteFromExtractingOnward(@Param("jobId") UUID jobId);

    /**
     * Retention purge: a package's stage rows, deleted BEFORE their jobs (plain FK to
     * processing_job). Explicit org guard — bulk JPQL does not travel through {@code @TenantId}
     * filtering. Returns the deleted count for the purge audit.
     */
    @Modifying
    @Query(
            """
            delete from ProcessingStage s
            where s.orgId = :orgId
              and s.jobId in (select j.id from ProcessingJob j where j.packageId = :packageId)
            """)
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
