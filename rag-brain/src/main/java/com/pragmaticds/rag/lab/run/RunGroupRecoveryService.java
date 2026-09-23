package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.model.InstanceUsageService;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.service.LabRunTransactionService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Reclaims members whose lease expired, without ever replaying them.
 *
 * <p><b>An expired lease is a no-replay boundary, not a retry signal.</b> Nobody knows whether the
 * provider call happened before the node died, so the only honest record is INTERRUPTED. Re-running
 * would risk billing and recording the same work twice; marking it failed would claim knowledge
 * this process does not have. A retry is a new submission with a new key, made deliberately.
 *
 * <p>Recovery also gives back what the member reserved and rolls its group up, so a crashed node
 * does not leave a brain's budget quietly encumbered until someone notices.
 *
 * <p><b>Settling is not dispatching, so this bean is gated on the parent switch alone.</b> It
 * claims nothing, re-queues nothing and calls no provider; it only records the end of work that
 * has already stopped. Gating it on {@code execution.enabled} as well meant that turning dispatch
 * off — the documented rollback — removed the only thing that could settle whatever was
 * {@code PROCESSING} at that moment. That member stayed {@code PROCESSING} indefinitely, its
 * {@code lab_spend_reservation} row stayed {@code RESERVED} against the brain's daily budget where
 * preflight counts it, and neither the retention sweep nor the stale-reservation sweep would touch
 * it, because both require a terminal run. A switch that stops new work must not strand work
 * already in flight.
 */
@Service
// The PARENT key only. Dispatch is a capability within the generalized surface, so the dispatcher
// and the poller take both keys; settling is a property of the surface itself and takes one.
// Widening this cannot recreate the startup failure the two-key gate was introduced for: every
// collaborator below is gated on ragbrain.instances.enabled or broader — LabRunRepository is an
// unconditional repository, RunGroupStatusService and InstanceUsageService are parent-gated, and
// LabRunTransactionService is conditional on ragbrain.lab.enabled OR ragbrain.instances.enabled.
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class RunGroupRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RunGroupRecoveryService.class);

    /** One sweep's ceiling, so a large backlog cannot monopolise a poll cycle. */
    private static final int SWEEP_LIMIT = 50;

    private final LabRunRepository runs;
    private final LabRunTransactionService transactions;
    private final RunGroupStatusService status;
    private final InstanceUsageService usage;

    /**
     * Whether a dispatcher and poller exist to sweep on every tick.
     *
     * <p>Read as a value rather than taken from the bean's own condition, because this bean now
     * exists in both worlds and has to know which one it is in.
     */
    private final boolean executionEnabled;

    /**
     * Set the moment this bean is being destroyed, so a tick that arrives during shutdown does
     * not go looking for a connection pool that is closing.
     */
    private volatile boolean stopping;

    /**
     * The pre-sweep signature, retained so existing unit tests construct unchanged.
     *
     * <p>Dispatch off is the safe assumption for a directly constructed service: it has no poller
     * behind it, so believing dispatch were on would silence the very sweep this class runs.
     */
    public RunGroupRecoveryService(LabRunRepository runs,
                                   LabRunTransactionService transactions,
                                   RunGroupStatusService status,
                                   InstanceUsageService usage) {
        this(runs, transactions, status, usage, false);
    }

    /**
     * The wired signature.
     *
     * <p>Explicitly annotated, and it matters: with more than one declared constructor and no
     * annotation Spring finds no autowire candidate and falls back to another one, which here
     * would hand the bean a hardcoded flag instead of the deployment's own.
     */
    @Autowired
    public RunGroupRecoveryService(LabRunRepository runs,
                                   LabRunTransactionService transactions,
                                   RunGroupStatusService status,
                                   InstanceUsageService usage,
                                   @Value("${ragbrain.instances.execution.enabled:false}")
                                   boolean executionEnabled) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.status = Objects.requireNonNull(status, "status");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.executionEnabled = executionEnabled;
    }

    /**
     * The periodic sweep, which runs whenever the generalized surface is on.
     *
     * <p><b>It stands down while dispatch is on.</b> {@link RunGroupPoller} already sweeps on every
     * tick, immediately before it decides whether any concurrency slots are free, and that ordering
     * is why recovery runs there at all. A second sweep would be safe — {@code markRunInterrupted}
     * requires the member to still be {@code PROCESSING}, so it would reclaim nothing the poller
     * had already reclaimed — but it would also be pointless, and standing down keeps an
     * execution-enabled deployment behaving exactly as it did before this method existed.
     *
     * <p><b>It also stands down for shutdown, and swallows what shutdown throws.</b> A scheduled
     * tick can arrive while the context is closing, and the connection pool is torn down under it:
     * the sweep then waits out the pool's acquisition timeout and throws. Left uncaught that
     * reaches the scheduler's default error handler, which logs at ERROR — on every ordinary
     * shutdown, for a sweep that had nothing to settle. So the flag below short-circuits a tick
     * that starts after destruction has begun, and a failure that happens anyway is logged as a
     * class name rather than propagated. A genuine failure is still visible at WARN and the next
     * fixed delay tries again.
     */
    @Scheduled(fixedDelayString = "${ragbrain.instances.recovery-sweep-ms:60000}",
            initialDelayString = "${ragbrain.instances.recovery-sweep-ms:60000}")
    public void sweep() {
        if (executionEnabled || stopping) {
            return;
        }
        int reclaimed;
        try {
            reclaimed = recoverExpired();
        } catch (RuntimeException failure) {
            // Class name only: a persistence failure's message can quote the offending row.
            if (stopping) {
                log.debug("Instance recovery sweep stopped with the context ({})",
                        failure.getClass().getSimpleName());
            } else {
                log.warn("Instance recovery sweep failed ({})",
                        failure.getClass().getSimpleName());
            }
            return;
        }
        if (reclaimed > 0) {
            log.info("Instance recovery reclaimed {} member(s) with dispatch switched off",
                    reclaimed);
        }
    }

    /** Stops sweeping before the datasource this sweep depends on is closed underneath it. */
    @PreDestroy
    void stopSweeping() {
        stopping = true;
    }

    /** Sweeps expired leases. Returns how many members it reclaimed. */
    public int recoverExpired() {
        // Reuses the finder the Lab prototype's recovery already uses, and the partial index
        // behind it; the sweep is bounded here rather than in the query so both callers can pick
        // their own ceiling.
        List<LabRun> expired = runs
                .findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
                        LabRun.Status.PROCESSING, OffsetDateTime.now())
                .stream().limit(SWEEP_LIMIT).toList();
        int reclaimed = 0;
        for (LabRun run : expired) {
            // markRunInterrupted requires the run to still be PROCESSING, so a member that
            // finished between the query and here is left exactly as it finished.
            if (transactions.markRunInterrupted(run.getId(), "INSTANCE_RUN_LEASE_EXPIRED")) {
                status.releaseReservation(run.getId(), run.getBrainId());
                // Nobody knows whether the provider ran, so the only honest usage is
                // UNAVAILABLE — and a terminal run must never sit PENDING forever.
                usage.report(run.getId(), run.getBrainId(),
                        new ModelEstimate.ProviderUsage(null, null, null));
                status.rollUp(run.getRunGroupId());
                reclaimed++;
                log.warn("Instance run {} was reclaimed after its lease expired", run.getId());
            }
        }
        return reclaimed;
    }
}
