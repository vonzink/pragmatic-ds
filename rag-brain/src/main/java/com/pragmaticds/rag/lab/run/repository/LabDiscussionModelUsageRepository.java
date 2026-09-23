package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabDiscussionModelUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One usage row per discussion exchange. */
public interface LabDiscussionModelUsageRepository
        extends JpaRepository<LabDiscussionModelUsage, UUID> {

    Optional<LabDiscussionModelUsage> findByExchangeIdAndBrainId(UUID exchangeId, UUID brainId);

    List<LabDiscussionModelUsage> findByExchangeIdInAndBrainId(
            List<UUID> exchangeIds, UUID brainId);
}
