package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainSourceWeight;
import com.pragmaticds.rag.domain.BrainSourceWeightId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrainSourceWeightRepository
        extends JpaRepository<BrainSourceWeight, BrainSourceWeightId> {

    List<BrainSourceWeight> findByBrainId(UUID brainId);

    Optional<BrainSourceWeight> findByBrainIdAndDocumentId(UUID brainId, UUID documentId);

    @Modifying
    @Query("delete from BrainSourceWeight w where w.brainId = :brainId")
    void deleteByBrainId(@Param("brainId") UUID brainId);
}
