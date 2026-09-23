package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabSpendReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Reservations exist to be summed: live exposure is what a budget check adds to today's spend. */
public interface LabSpendReservationRepository extends JpaRepository<LabSpendReservation, UUID> {

    Optional<LabSpendReservation> findByRunIdAndBrainId(UUID runId, UUID brainId);

    /**
     * Total currently held for a brain. Returns zero rather than null when nothing is reserved, so
     * a caller cannot accidentally treat "no reservations" as "no budget information".
     */
    @Query("SELECT COALESCE(SUM(r.reservedMaxUsd), 0) FROM LabSpendReservation r "
            + "WHERE r.brainId = :brainId AND r.status = :status")
    BigDecimal reservedTotalFor(@Param("brainId") UUID brainId,
                                @Param("status") LabSpendReservation.Status status);

    /** The live exposure for a brain: what is held but not yet consumed or released. */
    default BigDecimal reservedTotalFor(UUID brainId) {
        return reservedTotalFor(brainId, LabSpendReservation.Status.RESERVED);
    }

    /** Retention only: a terminal group's reservations are settled history, deleted before the run. */
    long deleteByRunId(UUID runId);
}
