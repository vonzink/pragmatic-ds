package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Crash-honest recovery for abandoned Lab work.
 *
 * <p><b>What this class refuses to claim.</b> A process that dies mid-run may have died before its
 * provider attempt, during it, or after the provider answered but before the terminal row was
 * written. Nothing readable from the database distinguishes those, so this job does not pretend to:
 * it never resumes, never retries, and never invokes an analyzer or a model. It marks the abandoned
 * row {@code INTERRUPTED} and stops.
 *
 * <p><b>What that buys.</b> A retry with the same idempotency key then returns the interrupted run
 * — not a new execution — so an HTTP client that keeps retrying can never silently bill a second
 * provider attempt. Starting over is an explicit operator act: acknowledge the interrupted run and
 * submit a NEW key. That is the entire "exactly once" story this prototype is willing to tell.
 *
 * <p>The scan is bounded by a partial index ({@code idx_lab_run_expired_lease}) and reads only rows
 * whose lease has already elapsed, so a live run in its ninth minute is never touched.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.lab", name = "enabled", havingValue = "true")
public class LabRunRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(LabRunRecoveryService.class);

    /** The value-free terminal code an abandoned run or exchange carries. */
    public static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    private final LabRunTransactionService transactions;
    private final LabAuditService audit;

    public LabRunRecoveryService(LabRunTransactionService transactions, LabAuditService audit) {
        this.transactions = transactions;
        this.audit = audit;
    }

    /** Marks every run whose processing lease has elapsed {@code INTERRUPTED}. */
    public int recoverExpiredRuns() {
        int recovered = 0;
        for (LabRun run : transactions.expiredRuns(OffsetDateTime.now())) {
            if (transactions.markRunInterrupted(run.getId(), LEASE_EXPIRED)) {
                recovered++;
                audit.record(run.getBrainId(), LabAuditService.RUN_RECOVERY,
                        LabAuditEvent.Status.FAILED, LabAuditEvent.SubjectType.RUN, run.getId(),
                        LEASE_EXPIRED, Map.of("attempt", run.getAttempt(), "replayed", false));
            }
        }
        return recovered;
    }

    /**
     * Marks every discussion exchange whose lease has elapsed {@code INTERRUPTED}.
     *
     * <p>Same rule, same reason: an interrupted exchange is not replayed, and an explicit new key
     * is required. Its two ordinal slots stay consumed, which is correct — the transcript records
     * that a turn was attempted at that position.
     */
    public int recoverExpiredExchanges() {
        int recovered = 0;
        for (LabDiscussionExchange exchange : transactions.expiredExchanges(OffsetDateTime.now())) {
            if (transactions.markExchangeInterrupted(exchange.getId(), LEASE_EXPIRED)) {
                recovered++;
            }
        }
        return recovered;
    }

    /** The bounded periodic sweep. Both scans are index-backed and read only elapsed leases. */
    @Scheduled(fixedDelayString = "${ragbrain.lab.recovery-sweep-ms:60000}",
            initialDelayString = "${ragbrain.lab.recovery-sweep-ms:60000}")
    public void sweep() {
        int runs = recoverExpiredRuns();
        int exchanges = recoverExpiredExchanges();
        if (runs > 0 || exchanges > 0) {
            log.info("Lab recovery marked {} run(s) and {} exchange(s) INTERRUPTED; none replayed",
                    runs, exchanges);
        }
    }
}
