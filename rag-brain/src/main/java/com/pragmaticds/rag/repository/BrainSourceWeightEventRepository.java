package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainSourceWeightEventRepository
        extends JpaRepository<BrainSourceWeightEvent, UUID> {

    List<BrainSourceWeightEvent> findByBrainIdAndStatusOrderByCreatedAtDesc(UUID brainId, String status);

    List<BrainSourceWeightEvent> findTop50ByBrainIdOrderByCreatedAtDesc(UUID brainId);
}
