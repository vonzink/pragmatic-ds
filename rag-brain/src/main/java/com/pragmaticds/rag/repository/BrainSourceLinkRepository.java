package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainSourceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainSourceLinkRepository extends JpaRepository<BrainSourceLink, UUID> {

    List<BrainSourceLink> findAllByBrainIdOrderByCreatedAtDescIdDesc(UUID brainId);

    List<BrainSourceLink> findByBrainIdAndActiveTrueOrderByCreatedAtDescIdDesc(UUID brainId);

    long countByBrainId(UUID brainId);

    long countByBrainIdAndActiveTrue(UUID brainId);
}
