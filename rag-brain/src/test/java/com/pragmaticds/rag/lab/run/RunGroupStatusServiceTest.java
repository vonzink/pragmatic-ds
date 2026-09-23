package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.model.InstanceUsageService;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.LabSpendReservation;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.run.repository.LabSpendReservationRepository;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a group's outcome is composed from its members, and what cancelling can actually reach.
 *
 * <p>The load-bearing rule is that one member never decides for its siblings. PARTIAL is a real
 * result: a comparison where one model errored still tells you about the others, and collapsing
 * that to FAILED would throw away the members that worked.
 */
class RunGroupStatusServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GROUP = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private LabRunGroupRepository groups;
    private LabRunRepository runs;
    private LabSpendReservationRepository reservations;
    private InstanceUsageService usage;
    private RunGroupStatusService service;

    @BeforeEach
    void setUp() {
        groups = mock(LabRunGroupRepository.class);
        runs = mock(LabRunRepository.class);
        reservations = mock(LabSpendReservationRepository.class);
        usage = mock(InstanceUsageService.class);
        when(groups.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(runs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(reservations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RunGroupStatusService(groups, runs, reservations, usage,
                mock(InstanceControlMetrics.class), transactions);
    }

    @Test
    void everyMemberSucceedingIsTheOnlyWayToSucceed() {
        assertEquals(Optional.of(LabRunGroup.Status.SUCCEEDED),
                rollUp(LabRunGroup.Status.PROCESSING,
                        LabRun.Status.SUCCEEDED, LabRun.Status.SUCCEEDED));
    }

    @Test
    void aMixedOutcomeIsPartialBecauseTheMembersThatWorkedStillTellYouSomething() {
        assertEquals(Optional.of(LabRunGroup.Status.PARTIAL),
                rollUp(LabRunGroup.Status.PROCESSING,
                        LabRun.Status.SUCCEEDED, LabRun.Status.FAILED));

        // A cancelled sibling alongside a success is still partial, not cancelled.
        assertEquals(Optional.of(LabRunGroup.Status.PARTIAL),
                rollUp(LabRunGroup.Status.PROCESSING,
                        LabRun.Status.SUCCEEDED, LabRun.Status.CANCELLED));
    }

    @Test
    void nothingSucceedingIsFailedAndEverythingCancelledIsCancelled() {
        assertEquals(Optional.of(LabRunGroup.Status.FAILED),
                rollUp(LabRunGroup.Status.PROCESSING,
                        LabRun.Status.FAILED, LabRun.Status.INTERRUPTED));
        assertEquals(Optional.of(LabRunGroup.Status.CANCELLED),
                rollUp(LabRunGroup.Status.PROCESSING,
                        LabRun.Status.CANCELLED, LabRun.Status.CANCELLED));
    }

    @Test
    void aGroupStaysLiveWhileAnyMemberIsStillRunning() {
        assertEquals(Optional.empty(),
                rollUp(LabRunGroup.Status.PROCESSING,
                        LabRun.Status.SUCCEEDED, LabRun.Status.PROCESSING));
        verify(groups, never()).saveAndFlush(any());
    }

    @Test
    void theFirstMemberToStartMovesTheGroupOutOfQueued() {
        assertEquals(Optional.of(LabRunGroup.Status.PROCESSING),
                rollUp(LabRunGroup.Status.QUEUED,
                        LabRun.Status.PROCESSING, LabRun.Status.QUEUED));
    }

    @Test
    void aGroupThatHasAlreadyFinishedIsLeftAloneRatherThanRewritten() {
        assertEquals(Optional.empty(),
                rollUp(LabRunGroup.Status.PARTIAL,
                        LabRun.Status.SUCCEEDED, LabRun.Status.SUCCEEDED));
        verify(groups, never()).saveAndFlush(any());
    }

    @Test
    void cancellingReachesQueuedMembersAndReportsTheOnesItCannot() {
        LabRun queued = run(LabRun.Status.QUEUED);
        LabRun running = run(LabRun.Status.PROCESSING);
        when(groups.findByIdAndBrainId(GROUP, BRAIN))
                .thenReturn(Optional.of(group(LabRunGroup.Status.PROCESSING)));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(queued, running));
        when(reservations.findByRunIdAndBrainId(any(), any()))
                .thenAnswer(call -> Optional.of(reservation()));

        RunGroupStatusService.CancellationOutcome outcome = service.cancel(BRAIN, GROUP);

        // A PROCESSING member has a provider call in flight that nothing here can recall, so it
        // is reported rather than relabelled as cancelled.
        assertEquals(1, outcome.cancelledMembers());
        assertEquals(1, outcome.stillProcessingMembers());
        assertEquals(LabRun.Status.CANCELLED, queued.getStatus());
        assertNotNull(queued.getTerminalAt());
        assertEquals(LabRun.Status.PROCESSING, running.getStatus());

        // The cancelled member never called a provider, so its usage settles honestly
        // UNAVAILABLE — never PENDING forever on a terminal run. The member still running keeps
        // its measurement open; its own terminal transition will settle it.
        verify(usage).report(queued.getId(), BRAIN,
                new ModelEstimate.ProviderUsage(null, null, null));
        verify(usage, never()).report(eq(running.getId()), any(), any());
    }

    @Test
    void cancellingTheLastLiveMemberConcludesTheGroupItself() {
        // The dispatcher and recovery sweep — the only other roll-up callers — live behind the
        // execution flag. If cancellation did not conclude the group in the same transaction, a
        // deployment that never dispatches would hold a fully-cancelled group at QUEUED forever:
        // unpollable as finished, unsweepable by retention.
        LabRunGroup group = group(LabRunGroup.Status.QUEUED);
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(run(LabRun.Status.QUEUED)));
        when(reservations.findByRunIdAndBrainId(any(), any()))
                .thenAnswer(call -> Optional.of(reservation()));

        service.cancel(BRAIN, GROUP);

        assertEquals(LabRunGroup.Status.CANCELLED, group.getStatus());
        assertNotNull(group.getTerminalAt());
        verify(groups).saveAndFlush(group);
    }

    @Test
    void cancellingAroundAProcessingMemberLeavesTheGroupOpen() {
        LabRunGroup group = group(LabRunGroup.Status.PROCESSING);
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(run(LabRun.Status.QUEUED), run(LabRun.Status.PROCESSING)));
        when(reservations.findByRunIdAndBrainId(any(), any()))
                .thenAnswer(call -> Optional.of(reservation()));

        service.cancel(BRAIN, GROUP);

        // The provider call in flight decides the group's end, not the cancellation.
        assertEquals(LabRunGroup.Status.PROCESSING, group.getStatus());
        assertNull(group.getTerminalAt());
    }

    @Test
    void cancellingAQueuedMemberGivesItsReservationBackImmediately() {
        LabSpendReservation held = reservation();
        when(groups.findByIdAndBrainId(GROUP, BRAIN))
                .thenReturn(Optional.of(group(LabRunGroup.Status.QUEUED)));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(run(LabRun.Status.QUEUED)));
        when(reservations.findByRunIdAndBrainId(any(), any())).thenReturn(Optional.of(held));

        service.cancel(BRAIN, GROUP);

        // Money held for work that will never happen should not wait for a sweeper.
        assertEquals(LabSpendReservation.Status.RELEASED, held.getStatus());
    }

    @Test
    void aSuccessfulMemberConsumesWhatItHeldAndAFailedOneReturnsIt() {
        LabSpendReservation consumed = reservation();
        when(reservations.findByRunIdAndBrainId(any(), any())).thenReturn(Optional.of(consumed));
        service.consumeReservation(UUID.randomUUID(), BRAIN);
        assertEquals(LabSpendReservation.Status.CONSUMED, consumed.getStatus());

        LabSpendReservation released = reservation();
        when(reservations.findByRunIdAndBrainId(any(), any())).thenReturn(Optional.of(released));
        service.releaseReservation(UUID.randomUUID(), BRAIN);
        assertEquals(LabSpendReservation.Status.RELEASED, released.getStatus());
    }

    @Test
    void aReservationThatHasAlreadySettledIsNotSettledAgain() {
        LabSpendReservation settled = reservation();
        settled.settle(LabSpendReservation.Status.CONSUMED);
        when(reservations.findByRunIdAndBrainId(any(), any())).thenReturn(Optional.of(settled));

        service.releaseReservation(UUID.randomUUID(), BRAIN);

        // One transition only; the database guard would refuse a second, and losing that race
        // must not fail the caller's own transaction.
        assertEquals(LabSpendReservation.Status.CONSUMED, settled.getStatus());
        verify(reservations, never()).saveAndFlush(any());
    }

    // ================================================================ fixtures

    private Optional<LabRunGroup.Status> rollUp(
            LabRunGroup.Status groupStatus, LabRun.Status... memberStatuses) {
        when(groups.findById(GROUP)).thenReturn(Optional.of(group(groupStatus)));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP)).thenReturn(
                java.util.Arrays.stream(memberStatuses).map(RunGroupStatusServiceTest::run)
                        .toList());
        return service.rollUp(GROUP);
    }

    private static LabRunGroup group(LabRunGroup.Status status) {
        LabRunGroup group = new LabRunGroup();
        ReflectionTestUtils.setField(group, "id", GROUP);
        group.setBrainId(BRAIN);
        group.setStatus(status);
        return group;
    }

    private static LabRun run(LabRun.Status status) {
        LabRun run = new LabRun();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setBrainId(BRAIN);
        run.setInstanceSlug("income");
        run.setStatus(status);
        run.setRunGroupId(GROUP);
        if (status == LabRun.Status.PROCESSING) {
            run.setLeaseExpiresAt(OffsetDateTime.now().plusMinutes(5));
        }
        return run;
    }

    private static LabSpendReservation reservation() {
        return new LabSpendReservation(UUID.randomUUID(), BRAIN, new BigDecimal("0.40"));
    }
}
