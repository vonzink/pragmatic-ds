package com.pragmaticds.rag.lab.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.TaskExecutor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One tick, and the three things it must survive.
 *
 * <p>The poll loop is the only thing that moves a queued member, so a tick that throws stops
 * dispatch for the whole node until the next fixed delay — and a tick that blocks stops it for as
 * long as a provider takes to answer. Both are failures of availability rather than of a run, and
 * neither should be reachable from a single member's bad day.
 */
class RunGroupPollerTest {

    private static final UUID RUN = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private RunGroupDispatcher dispatcher;
    private RunGroupRecoveryService recovery;

    @BeforeEach
    void setUp() {
        dispatcher = mock(RunGroupDispatcher.class);
        recovery = mock(RunGroupRecoveryService.class);
        when(dispatcher.claimNext()).thenReturn(Optional.empty());
    }

    @Test
    void whatCrashedIsReclaimedBeforeThisTickDecidesWhetherAnySlotsAreFree() {
        poller(mock(TaskExecutor.class)).tick();

        // Order is the whole point. A node that died holding slots still counts against every
        // concurrency limit until its leases are swept, so claiming first would decide against
        // capacity that no longer exists.
        InOrder order = inOrder(recovery, dispatcher);
        order.verify(recovery).recoverExpired();
        order.verify(dispatcher).claimNext();
    }

    @Test
    void aClaimedMemberIsHandedToTheExecutorRatherThanRunOnThePollThread() {
        when(dispatcher.claimNext()).thenReturn(Optional.of(RUN));

        // The executor here does nothing with what it is given, which is what makes the assertion
        // meaningful: tick() returned without executing, so the poll loop is free for the next
        // tick while the member is still running. Claiming is cheap and serialized across the
        // cluster; executing waits on the engine, retrieval, and a provider.
        poller(mock(TaskExecutor.class)).tick();

        verify(dispatcher, never()).executeClaimed(any());
    }

    @Test
    void theMemberTheExecutorRunsIsTheOneThatWasClaimed() {
        when(dispatcher.claimNext()).thenReturn(Optional.of(RUN));

        poller(Runnable::run).tick();

        verify(dispatcher).executeClaimed(RUN);
    }

    @Test
    void anEmptyClaimNeverTouchesTheExecutor() {
        TaskExecutor executor = mock(TaskExecutor.class);

        poller(executor).tick();

        verify(executor, never()).execute(any());
    }

    // ============================================================ surviving the tick

    @Test
    void aRecoverySweepThatFailsDoesNotStopThisTickFromDispatching() {
        when(recovery.recoverExpired()).thenThrow(new IllegalStateException("sweep query failed"));

        poller(mock(TaskExecutor.class)).tick();

        // Recovery is housekeeping; dispatch is the work. A backlog of expired leases is bad, and
        // a node that stops claiming because of it is worse.
        verify(dispatcher).claimNext();
    }

    @Test
    void aClaimThatFailsEndsTheTickWithoutEndingTheLoop() {
        when(dispatcher.claimNext()).thenThrow(new IllegalStateException("advisory lock timeout"));

        // Escaping here would stop this node dispatching until someone restarted it, because a
        // scheduled method that throws is simply retried at the next fixed delay with no signal
        // that anything is wrong beyond a log line.
        assertDoesNotThrow(() -> poller(mock(TaskExecutor.class)).tick());
    }

    @Test
    void aFailureEscapingExecutionIsContainedInsideTheExecutorTask() {
        when(dispatcher.claimNext()).thenReturn(Optional.of(RUN));
        org.mockito.Mockito.doThrow(new IllegalStateException("escaped its own handling"))
                .when(dispatcher).executeClaimed(RUN);

        // executeClaimed already moves the member to a terminal state, so anything escaping it is
        // a bug — but a bug in one member must not propagate into whatever thread the executor
        // gave it, which here is the poll thread itself.
        assertDoesNotThrow(() -> poller(Runnable::run).tick());
    }

    private RunGroupPoller poller(TaskExecutor executor) {
        return new RunGroupPoller(dispatcher, recovery, executor);
    }
}
