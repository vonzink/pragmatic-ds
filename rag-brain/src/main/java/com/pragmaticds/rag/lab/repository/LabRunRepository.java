package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Brain-scoped Lab run access.
 *
 * <p>Every read a request can reach is keyed by {@code brainId}, so a leaked run UUID cannot cross
 * a brain boundary. History is newest first, matching {@code idx_lab_run_history}.
 */
public interface LabRunRepository extends JpaRepository<LabRun, UUID> {

    Optional<LabRun> findByIdAndBrainId(UUID id, UUID brainId);

    Optional<LabRun> findByBrainIdAndInstanceSlugAndIdempotencyKey(
            UUID brainId, String instanceSlug, String idempotencyKey);

    List<LabRun> findByBrainIdAndInstanceSlugOrderByCreatedAtDesc(
            UUID brainId, String instanceSlug);

    /** The pessimistic run lock shared by discussion writes and authorized purge. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LabRun r where r.id = :id and r.brainId = :brainId")
    Optional<LabRun> lockByIdAndBrainId(@Param("id") UUID id, @Param("brainId") UUID brainId);

    /**
     * Expired leases, oldest first, for the bounded recovery job. Callers pass
     * {@link LabRun.Status#PROCESSING}; recovery marks the results {@code INTERRUPTED} and never
     * starts an analyzer execution. Matches the partial index {@code idx_lab_run_expired_lease}.
     */
    List<LabRun> findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
            LabRun.Status status, OffsetDateTime now);

    /**
     * The oldest runs past a retention cutoff, capped so one scheduled sweep can never load an
     * unbounded page. Not brain-scoped on purpose: the age sweep is an operator-owned job over the
     * whole prototype table, unlike every request-reachable read above.
     */
    List<LabRun> findFirst200ByCreatedAtBeforeOrderByCreatedAtAsc(OffsetDateTime cutoff);

    /** Whether an analyzer manifest row is still held by a Lab run (the retention hold). */
    boolean existsByAnalysisRunId(UUID analysisRunId);

    /** How many members a group has. One is the prototype's shape and the backfill's. */
    long countByRunGroupId(UUID runGroupId);

    /** Queue-depth gauge: bounded by the configured provider catalog, read on scrape. */
    long countByStatusAndRequestedProvider(LabRun.Status status, String requestedProvider);

    /** A group's members in the order the submission listed them. */
    List<LabRun> findByRunGroupIdOrderByMemberIndexAsc(UUID runGroupId);
}
