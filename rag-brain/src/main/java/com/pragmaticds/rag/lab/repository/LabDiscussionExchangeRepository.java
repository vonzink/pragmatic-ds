package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Run-pinned discussion turns, in transcript order. */
public interface LabDiscussionExchangeRepository
        extends JpaRepository<LabDiscussionExchange, UUID> {

    Optional<LabDiscussionExchange> findByRunIdAndIdempotencyKey(UUID runId, String idempotencyKey);

    List<LabDiscussionExchange> findByRunIdOrderBySequenceNumberAsc(UUID runId);

    /** The next transcript position, claimed under the per-run lock. */
    Optional<LabDiscussionExchange> findFirstByRunIdOrderBySequenceNumberDesc(UUID runId);

    /** Expired exchange leases for the bounded recovery job; never replayed against the model. */
    List<LabDiscussionExchange> findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
            LabRun.Status status, OffsetDateTime now);

    /** FK-safe purge step: exchanges are removed after their messages and before their run. */
    long deleteByRunId(UUID runId);
}
