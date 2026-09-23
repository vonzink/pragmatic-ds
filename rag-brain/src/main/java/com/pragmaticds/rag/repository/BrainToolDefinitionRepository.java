package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainToolDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrainToolDefinitionRepository extends JpaRepository<BrainToolDefinition, UUID> {

    List<BrainToolDefinition> findAllByBrainIdOrderByCreatedAtDescIdDesc(UUID brainId);

    List<BrainToolDefinition> findByBrainIdAndActiveTrueOrderByCreatedAtDescIdDesc(UUID brainId);

    Optional<BrainToolDefinition> findByBrainIdAndNameAndActiveTrue(UUID brainId, String name);

    boolean existsByBrainIdAndName(UUID brainId, String name);

    boolean existsByBrainIdAndNameAndIdNot(UUID brainId, String name, UUID id);
}
