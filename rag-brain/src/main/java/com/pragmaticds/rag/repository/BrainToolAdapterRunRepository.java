package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainToolAdapterRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainToolAdapterRunRepository extends JpaRepository<BrainToolAdapterRun, UUID> {
    List<BrainToolAdapterRun> findTop25ByBrainIdOrderByCreatedAtDesc(UUID brainId);

    List<BrainToolAdapterRun> findTop25ByBrainIdAndToolNameOrderByCreatedAtDesc(UUID brainId, String toolName);
}
