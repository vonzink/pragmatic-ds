package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.model.InstanceUsageService;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.service.LabRunTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a crashed node leaves behind, and the one honest thing to do with it.
 *
 * <p>An expired lease is a no-replay boundary rather than a retry signal. Nobody knows whether the
 * provider call happened before the node died, so re-running risks billing and recording the same
 * work twice, and marking it failed claims knowledge this process does not have. INTERRUPTED is
 * the only record that is true. What recovery must still do is give the money back: a crashed node
 * should not leave a brain's budget quietly encumbered until someone notices.
 */
class RunGroupRecoveryServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GROUP = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private LabRunRepository runs;
    private LabRunTransactionService transactions;
    private RunGroupStatusService status;
    private InstanceUsageService usage;
    private RunGroupRecoveryService recovery;

    @BeforeEach
    void setUp() {
        runs = mock(LabRunRepository.class);
        transactions = mock(LabRunTransactionService.class);
        status = mock(RunGroupStatusService.class);
        usage = mock(InstanceUsageService.class);
        recovery = new RunGroupRecoveryService(runs, transactions, status, usage);
    }

    @Test
    void anExpiredMemberIsInterruptedAndItsReservationGivenBack() {
        UUID abandoned = expired(1);
        when(transactions.markRunInterrupted(any(), anyString())).thenReturn(true);

        assertEquals(1, recovery.recoverExpired());

        verify(transactions).markRunInterrupted(abandoned, "INSTANCE_RUN_LEASE_EXPIRED");
        verify(status).releaseReservation(abandoned, BRAIN);
        // Nobody knows whether the provider ran, so the usage row settles honestly UNAVAILABLE
        // rather than sitting PENDING forever on a terminal run.
        verify(usage).report(abandoned, BRAIN,
                new ModelEstimate.ProviderUsage(null, null, null));
        verify(status).rollUp(GROUP);
    }

    @Test
    void recoveryNeverReplaysTheMemberItReclaims() {
        expired(1);
        when(transactions.markRunInterrupted(any(), anyString())).thenReturn(true);

        recovery.recoverExpired();

        // Nothing re-queues the row and nothing re-runs it. A retry is a new submission with a new
        // key, made deliberately by someone who has decided the risk of a duplicate call is worth
        // taking — not something a sweeper decides on their behalf.
        verify(runs, never()).save(any());
        verify(runs, never()).saveAndFlush(any());

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(transactions).markRunInterrupted(any(), code.capture());
        assertEquals("INSTANCE_RUN_LEASE_EXPIRED", code.getValue());
    }

    @Test
    void aMemberThatFinishedBetweenTheQueryAndTheClaimIsLeftExactlyAsItFinished() {
        expired(1);
        // markRunInterrupted requires the run to still be PROCESSING; false means it is not.
        when(transactions.markRunInterrupted(any(), anyString())).thenReturn(false);

        assertEquals(0, recovery.recoverExpired());

        // Releasing here would hand back a reservation the finished run legitimately consumed,
        // and rolling up would restate a group that has already settled. The finished run's
        // usage was reported by whatever finished it, so nothing is settled here either.
        verify(status, never()).releaseReservation(any(), any());
        verify(usage, never()).report(any(), any(), any());
        verify(status, never()).rollUp(any());
    }

    @Test
    void onlyMembersWhoseLeaseHasActuallyPassedAreSwept() {
        expired(1);
        when(transactions.markRunInterrupted(any(), anyString())).thenReturn(true);
        OffsetDateTime before = OffsetDateTime.now();

        recovery.recoverExpired();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(runs).findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
                eq(LabRun.Status.PROCESSING), cutoff.capture());
        assertTrue(!cutoff.getValue().isBefore(before),
                "a live lease is not an abandoned one, so the cutoff is now");
    }

    @Test
    void aLargeBacklogIsSweptInBoundedBatchesRatherThanMonopolisingAPollCycle() {
        expired(200);
        when(transactions.markRunInterrupted(any(), anyString())).thenReturn(true);

        // A node that died holding many leases must not turn one poll cycle into a long
        // transaction storm; the rest are still expired on the next pass.
        assertEquals(50, recovery.recoverExpired());
        verify(transactions, times(50)).markRunInterrupted(any(), anyString());
    }

    @Test
    void anEmptySweepDoesNothingAtAll() {
        when(runs.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(any(), any()))
                .thenReturn(List.of());

        assertEquals(0, recovery.recoverExpired());

        verify(transactions, never()).markRunInterrupted(any(), anyString());
        verify(status, never()).rollUp(any());
    }

    /**
     * With dispatch switched off, the scheduled sweep is what settles what dispatch left behind.
     *
     * <p>This is the case the rollback runbook walks an operator into. Turning
     * {@code ragbrain.instances.execution.enabled} off removes the poller, so if the sweep went
     * with it a member that was {@code PROCESSING} at that moment would stay {@code PROCESSING}
     * forever: its spend reservation would stay {@code RESERVED} against the brain's daily budget,
     * and neither the retention sweep nor the stale-reservation sweep will touch a run that has
     * not reached a terminal state. Draining has to survive the switch that stops new work.
     */
    @Test
    void theScheduledSweepSettlesInFlightMembersWhileDispatchIsSwitchedOff() {
        UUID abandoned = expired(1);
        when(transactions.markRunInterrupted(any(), anyString())).thenReturn(true);

        sweeper(false).sweep();

        verify(transactions).markRunInterrupted(abandoned, "INSTANCE_RUN_LEASE_EXPIRED");
        verify(status).releaseReservation(abandoned, BRAIN);
        verify(usage).report(abandoned, BRAIN,
                new ModelEstimate.ProviderUsage(null, null, null));
        verify(status).rollUp(GROUP);
    }

    /**
     * With dispatch switched on, the poller owns recovery and the scheduled sweep stands down.
     *
     * <p>A second sweep would be harmless — {@code markRunInterrupted} requires the member to
     * still be {@code PROCESSING}, so it would reclaim nothing — but it would also be pointless.
     * The poller already sweeps every tick, immediately before it decides whether any concurrency
     * slots are free, and that ordering is the reason recovery runs there at all. Standing down
     * here keeps an execution-enabled deployment behaving exactly as it did before this sweep
     * existed.
     */
    @Test
    void theScheduledSweepStandsDownWhileThePollerIsAlreadyDrivingRecovery() {
        expired(1);
        when(transactions.markRunInterrupted(any(), anyString())).thenReturn(true);

        sweeper(true).sweep();

        verify(runs, never()).findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
                any(), any());
        verify(transactions, never()).markRunInterrupted(any(), anyString());
    }

    /**
     * A sweep that arrives after destruction has begun does nothing and logs nothing at ERROR.
     *
     * <p>A scheduled tick can start while the context is closing, and the connection pool is torn
     * down under it: the sweep waits out the pool's acquisition timeout and throws into the
     * scheduler's default error handler, which logs at ERROR on every ordinary shutdown for a
     * sweep that had nothing to settle.
     */
    @Test
    void aSweepThatArrivesDuringShutdownStandsDownRatherThanReachingForAClosingPool() {
        expired(1);
        RunGroupRecoveryService sweeping = sweeper(false);

        sweeping.stopSweeping();
        sweeping.sweep();

        verify(runs, never()).findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
                any(), any());
        verify(transactions, never()).markRunInterrupted(any(), anyString());
    }

    /** A failing sweep is absorbed, so the scheduler never sees it and the next pass still runs. */
    @Test
    void aFailingSweepIsAbsorbedRatherThanPropagatedIntoTheScheduler() {
        when(runs.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(any(), any()))
                .thenThrow(new IllegalStateException("pool is closing"));

        assertDoesNotThrow(() -> sweeper(false).sweep());
    }

    /** A service that believes dispatch is on or off, with everything else held constant. */
    private RunGroupRecoveryService sweeper(boolean executionEnabled) {
        return new RunGroupRecoveryService(runs, transactions, status, usage, executionEnabled);
    }

    /** Stages {@code count} expired members and returns the first one's id. */
    private UUID expired(int count) {
        List<LabRun> members = IntStream.range(0, count).mapToObj(index -> {
            LabRun run = new LabRun();
            run.setId(UUID.nameUUIDFromBytes(("expired-" + index).getBytes()));
            run.setBrainId(BRAIN);
            run.setInstanceSlug("income");
            run.setStatus(LabRun.Status.PROCESSING);
            run.setRunGroupId(GROUP);
            run.setMemberIndex(index);
            run.setLeaseExpiresAt(OffsetDateTime.now().minusMinutes(11));
            return run;
        }).toList();
        when(runs.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(any(), any()))
                .thenReturn(members);
        return members.get(0).getId();
    }
}
