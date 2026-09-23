package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Brain-scoped access to run groups. Every finder names the brain; none can span brains. */
public interface LabRunGroupRepository extends JpaRepository<LabRunGroup, UUID> {

    Optional<LabRunGroup> findByBrainIdAndIdempotencyKey(UUID brainId, String idempotencyKey);

    Optional<LabRunGroup> findByIdAndBrainId(UUID id, UUID brainId);

    List<LabRunGroup> findByBrainIdOrderByCreatedAtDesc(UUID brainId);

    List<LabRunGroup> findByBrainIdAndStatusOrderByCreatedAtDesc(
            UUID brainId, LabRunGroup.Status status);

    /**
     * Retention sweep page: terminal groups whose end predates the cutoff, oldest first. A
     * non-terminal group has a null terminal_at, which no comparison matches — active groups
     * are structurally unsweepable.
     */
    List<LabRunGroup> findByTerminalAtBeforeOrderByTerminalAtAsc(OffsetDateTime cutoff);
}
