package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabRunPayload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * The encrypted terminal payload of one run.
 *
 * <p>There is no "find all payloads" read: a list view must never decrypt, so the only lookup is
 * for one authorized run's detail view.
 */
public interface LabRunPayloadRepository extends JpaRepository<LabRunPayload, UUID> {

    Optional<LabRunPayload> findByRunIdAndPayloadType(
            UUID runId, LabRunPayload.PayloadType payloadType);

    /** FK-safe purge step: the encrypted payload is removed before its run. */
    long deleteByRunId(UUID runId);
}
