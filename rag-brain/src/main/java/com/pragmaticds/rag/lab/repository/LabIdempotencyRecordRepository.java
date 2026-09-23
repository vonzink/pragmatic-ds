package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Immutable replay receipts, scoped by brain, operation, and caller-provided idempotency key. */
public interface LabIdempotencyRecordRepository extends JpaRepository<LabIdempotencyRecord, UUID> {

    Optional<LabIdempotencyRecord> findByBrainIdAndOperationAndIdempotencyKey(
            UUID brainId, String operation, String idempotencyKey);
}
