package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One usage row per run, always reached with its brain so a finder cannot span brains. */
public interface LabModelUsageRepository extends JpaRepository<LabModelUsage, UUID> {

    Optional<LabModelUsage> findByRunIdAndBrainId(UUID runId, UUID brainId);

    List<LabModelUsage> findByRunIdInAndBrainId(List<UUID> runIds, UUID brainId);

    /** Retention only: usage leaves go before their run. The V39 guard refuses UPDATE, not DELETE. */
    long deleteByRunId(UUID runId);
}
