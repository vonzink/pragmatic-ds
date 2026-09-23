package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrainToolAdapterConfigRepository extends JpaRepository<BrainToolAdapterConfig, UUID> {
    Optional<BrainToolAdapterConfig> findByBrainIdAndToolName(UUID brainId, String toolName);
    Optional<BrainToolAdapterConfig> findByBrainIdAndToolNameAndEnabledTrue(UUID brainId, String toolName);
}
