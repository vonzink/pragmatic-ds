package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabRunReviewSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** The value-free read-model snapshot pinned by each run that consumed reviewed values. */
public interface LabRunReviewSnapshotRepository extends JpaRepository<LabRunReviewSnapshot, UUID> {

    Optional<LabRunReviewSnapshot> findByRunId(UUID runId);

    /** FK-safe purge step: snapshots are removed before their run. */
    long deleteByRunId(UUID runId);
}
