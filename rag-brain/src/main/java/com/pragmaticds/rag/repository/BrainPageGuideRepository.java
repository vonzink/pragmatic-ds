package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainPageGuide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainPageGuideRepository extends JpaRepository<BrainPageGuide, UUID> {

    List<BrainPageGuide> findAllByBrainIdOrderByCreatedAtDescIdDesc(UUID brainId);

    List<BrainPageGuide> findByBrainIdAndActiveTrueOrderByCreatedAtDescIdDesc(UUID brainId);

    long countByBrainId(UUID brainId);

    long countByBrainIdAndActiveTrue(UUID brainId);
}
