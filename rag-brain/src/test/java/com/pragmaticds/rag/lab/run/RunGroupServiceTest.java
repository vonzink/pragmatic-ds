package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand.MemberPreflight;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunGroupPreflight;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunMemberCommand;
import com.pragmaticds.rag.lab.run.RunGroupService.RunGroupException;
import com.pragmaticds.rag.lab.run.domain.LabConnectorRunGroupContext;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.LabSpendReservation;
import com.pragmaticds.rag.lab.run.repository.LabConnectorRunGroupContextRepository;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.run.repository.LabSpendReservationRepository;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Creating a group: what it writes, what it replays, and what it refuses.
 *
 * <p>The two properties worth pinning down are that a preflight is advice rather than permission —
 * so resolution runs again inside the transaction — and that the caller's idempotency key lives on
 * the group while members carry an internal one, which is what lets a comparison hold two runs of
 * the same instance.
 */
class RunGroupServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GROUP = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CONNECTOR =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID RELEASE_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RELEASE_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID REGISTRATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SNAPSHOT = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID PRICING = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String KEY = "submission-key-1";

    private RunGroupPreflightService preflight;
    private LabRunGroupRepository groups;
    private LabRunRepository runs;
    private LabModelUsageRepository usage;
    private LabSpendReservationRepository reservations;
    private LabConnectorRunGroupContextRepository connectorContexts;
    private JdbcTemplate jdbc;
    private RunGroupService service;

    @BeforeEach
    void setUp() {
        preflight = mock(RunGroupPreflightService.class);
        groups = mock(LabRunGroupRepository.class);
        runs = mock(LabRunRepository.class);
        usage = mock(LabModelUsageRepository.class);
        reservations = mock(LabSpendReservationRepository.class);
        connectorContexts = mock(LabConnectorRunGroupContextRepository.class);
        jdbc = mock(JdbcTemplate.class);

        when(groups.findByBrainIdAndIdempotencyKey(any(), anyString()))
                .thenReturn(Optional.empty());
        when(groups.saveAndFlush(any())).thenAnswer(call -> {
            LabRunGroup group = call.getArgument(0);
            ReflectionTestUtils.setField(group, "id", GROUP);
            return group;
        });
        when(runs.saveAndFlush(any())).thenAnswer(call -> {
            LabRun run = call.getArgument(0);
            ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
            return run;
        });
        when(preflight.preflight(any())).thenAnswer(call -> accepted(comparison()));

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RunGroupService(preflight, groups, runs, usage, reservations,
                connectorContexts, jdbc, mock(InstanceControlMetrics.class), transactions);
    }

    @Test
    void aConnectorOriginWritesItsOwnershipRowInTheSameCreation() {
        RunGroupService.CreatedRunGroup created = service.create(comparison(), KEY,
                new RunOrigin.Connector(CONNECTOR, "tenant-a", "req-77", "e".repeat(64)));

        assertTrue(created.created());
        // The context is what later authorizes polling, so it must exist exactly when the group
        // does — same creation, same transaction, never as a follow-up write.
        ArgumentCaptor<LabConnectorRunGroupContext> context =
                ArgumentCaptor.forClass(LabConnectorRunGroupContext.class);
        verify(connectorContexts).saveAndFlush(context.capture());
        assertEquals(created.groupId(), context.getValue().getRunGroupId());
        assertEquals(CONNECTOR, context.getValue().getConnectorClientId());
        assertEquals(BRAIN, context.getValue().getBrainId());
        assertEquals("tenant-a", context.getValue().getTenantId());
        assertEquals("req-77", context.getValue().getExternalRequestId());
        assertEquals("e".repeat(64), context.getValue().getExternalRequestSha256());
    }

    @Test
    void anAdminOriginWritesNoOwnershipRow() {
        service.create(comparison(), KEY);

        // Admin groups are authorized by the admin key; a context row would claim a connector
        // owns a group no connector created.
        verify(connectorContexts, never()).saveAndFlush(any());
        verify(connectorContexts, never()).save(any());
    }

    @Test
    void aBlockedConnectorSubmissionWritesNothingIncludingItsContext() {
        when(preflight.preflight(any())).thenReturn(blocked());

        assertThrows(RunGroupService.RunGroupException.class,
                () -> service.create(comparison(), KEY,
                        new RunOrigin.Connector(CONNECTOR, "tenant-a", null, "e".repeat(64))));

        verify(connectorContexts, never()).saveAndFlush(any());
    }

    @Test
    void aComparisonHoldsTwoRunsOfOneInstanceBecauseTheCallersKeyLivesOnTheGroup() {
        RunGroupService.CreatedRunGroup created = service.create(comparison(), KEY);

        assertTrue(created.created());
        assertEquals(2, created.memberRunIds().size());

        ArgumentCaptor<LabRun> written = ArgumentCaptor.forClass(LabRun.class);
        verify(runs, times(2)).saveAndFlush(written.capture());
        // V34's run-key uniqueness is per instance, so two members of one instance need internal
        // identities. The caller's key is on the group and appears on no run.
        assertEquals(List.of("group:" + GROUP + ":0", "group:" + GROUP + ":1"),
                written.getAllValues().stream().map(LabRun::getIdempotencyKey).toList());
        assertFalse(written.getAllValues().stream()
                .anyMatch(run -> KEY.equals(run.getIdempotencyKey())));

        ArgumentCaptor<LabRunGroup> group = ArgumentCaptor.forClass(LabRunGroup.class);
        verify(groups).saveAndFlush(group.capture());
        assertEquals(KEY, group.getValue().getIdempotencyKey());
    }

    @Test
    void aComparisonVariesOneThingAndHoldsTheParsedInputAndSnapshotEqual() {
        service.create(comparison(), KEY);

        ArgumentCaptor<LabRun> written = ArgumentCaptor.forClass(LabRun.class);
        verify(runs, times(2)).saveAndFlush(written.capture());
        List<LabRun> members = written.getAllValues();

        // The one thing that varies, in the order the caller listed it — results are read in
        // that order, so it is part of what was submitted rather than an implementation detail.
        assertEquals(List.of(RELEASE_A, RELEASE_B),
                members.stream().map(LabRun::getReleaseId).toList());

        // Everything the comparison is not about is held equal. A comparison whose inputs also
        // differ measures nothing in particular: two answers that disagree would not say whether
        // the release or the package was responsible.
        assertEquals(1, members.stream().map(LabRun::getRegistrationId).distinct().count());
        assertEquals(1, members.stream().map(LabRun::getCorpusSnapshotId).distinct().count());
        assertEquals(REGISTRATION, members.get(0).getRegistrationId());
        assertEquals(SNAPSHOT, members.get(0).getCorpusSnapshotId());

        // Each member is its own run with its own index and its own pinned pricing version, so
        // usage, cost, and provenance are recorded per member rather than merged into the group.
        assertEquals(List.of(0, 1), members.stream().map(LabRun::getMemberIndex).toList());
        assertEquals(2, members.stream().map(LabRun::getId).distinct().count());
        assertTrue(members.stream().allMatch(run -> run.getPricingVersionId() != null),
                "a member priced against no catalog version could not be re-priced afterwards");
    }

    @Test
    void membersAreQueuedRatherThanStartedBecauseSubmittingSchedulesWork() {
        service.create(comparison(), KEY);

        ArgumentCaptor<LabRun> written = ArgumentCaptor.forClass(LabRun.class);
        verify(runs, times(2)).saveAndFlush(written.capture());
        for (LabRun run : written.getAllValues()) {
            assertEquals(LabRun.Status.QUEUED, run.getStatus());
            // A queued member holds no lease; the dispatcher takes one when it claims.
            assertNull(run.getLeaseExpiresAt());
            assertEquals(GROUP, run.getRunGroupId());
            assertEquals(SNAPSHOT, run.getCorpusSnapshotId());
            assertEquals(PRICING, run.getPricingVersionId());
        }
    }

    @Test
    void everyMemberGetsAnImmutableEstimateAndAReservationForItsMaximum() {
        service.create(comparison(), KEY);

        ArgumentCaptor<LabModelUsage> estimates = ArgumentCaptor.forClass(LabModelUsage.class);
        verify(usage, times(2)).saveAndFlush(estimates.capture());
        assertEquals(new BigDecimal("0.40"), estimates.getValue().getExpectedCostUsdMax());

        ArgumentCaptor<LabSpendReservation> held =
                ArgumentCaptor.forClass(LabSpendReservation.class);
        verify(reservations, times(2)).saveAndFlush(held.capture());
        // The maximum, not the estimate: a run landing at its upper bound must not overshoot a
        // budget that already approved it.
        assertEquals(new BigDecimal("0.40"), held.getValue().getReservedMaxUsd());
    }

    @Test
    void aTrueReplayReturnsBeforeAnyResolutionSoARetryIsCheap() {
        String requestSha = RunGroupRequestCodec.requestSha256(comparison());
        when(groups.findByBrainIdAndIdempotencyKey(BRAIN, KEY))
                .thenReturn(Optional.of(existing(requestSha)));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP)).thenReturn(List.of());

        RunGroupService.CreatedRunGroup replayed = service.create(comparison(), KEY);

        assertFalse(replayed.created());
        assertEquals(GROUP, replayed.groupId());
        // Re-running the submission's engine reads to answer "you already sent this" would make a
        // retry as expensive as the original.
        verify(preflight, never()).preflight(any());
        verify(groups, never()).saveAndFlush(any());
    }

    @Test
    void theSameKeyForADifferentSubmissionStartsNothing() {
        when(groups.findByBrainIdAndIdempotencyKey(BRAIN, KEY))
                .thenReturn(Optional.of(existing("f".repeat(64))));

        RunGroupException reused = assertThrows(RunGroupException.class,
                () -> service.create(comparison(), KEY));

        assertEquals(RunGroupException.Code.IDEMPOTENCY_KEY_REUSED, reused.code());
        verify(groups, never()).saveAndFlush(any());
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void aBlockedSubmissionWritesNothingAndReportsEveryReasonAtOnce() {
        when(preflight.preflight(any())).thenReturn(blocked());

        RunGroupException refused = assertThrows(RunGroupException.class,
                () -> service.create(comparison(), KEY));

        assertEquals(RunGroupException.Code.RUN_GROUP_BLOCKED, refused.code());
        assertEquals(List.of("GROUP_EXCEEDS_DAILY_BUDGET", "INSTANCE_DISABLED"),
                refused.blockingCodes());
        verify(groups, never()).saveAndFlush(any());
        verify(reservations, never()).saveAndFlush(any());

        // No member row is where "a rejected budget costs nothing" actually becomes true. The
        // dispatcher can only ever claim a queued member, so a submission that writes none can
        // never reach a provider — the guarantee lives here rather than at the provider boundary.
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void resolutionRunsAgainUnderTheBudgetLockBecauseAPreflightIsAdviceNotPermission() {
        service.create(comparison(), KEY);

        // Once outside the transaction to reject early and cheaply, once inside it after the lock
        // so a constraint that started applying in between still stops the submission.
        verify(preflight, times(2)).preflight(any());
        verify(jdbc).queryForObject(eq("SELECT pg_advisory_xact_lock(?, ?)"), eq(Object.class),
                any(), any());
    }

    @Test
    void aSubmissionThatBecomesUnacceptableUnderTheLockIsStillRefused() {
        when(preflight.preflight(any()))
                .thenReturn(accepted(comparison()))
                .thenReturn(blocked());

        // The second read is the one that counts: budgets move while a caller talks to the engine.
        assertEquals(RunGroupException.Code.RUN_GROUP_BLOCKED,
                assertThrows(RunGroupException.class,
                        () -> service.create(comparison(), KEY)).code());
        verify(groups, never()).saveAndFlush(any());
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void anUnusableRequestIsRefusedBeforeAnythingIsRead() {
        for (String key : List.of("", "  ", "k".repeat(201))) {
            assertEquals(RunGroupException.Code.RUN_GROUP_REQUEST_INVALID,
                    assertThrows(RunGroupException.class,
                            () -> service.create(comparison(), key)).code());
        }
        assertEquals(RunGroupException.Code.RUN_GROUP_REQUEST_INVALID,
                assertThrows(RunGroupException.class, () -> service.create(comparison(), null))
                        .code());
        verify(groups, never()).findByBrainIdAndIdempotencyKey(any(), anyString());
    }

    // ================================================================ fixtures

    private static RunGroupCommand comparison() {
        return new RunGroupCommand(BRAIN, LabRunGroup.Mode.COMPARISON,
                LabRunGroup.ComparisonDimension.RELEASE,
                List.of(new RunMemberCommand("income", RELEASE_A, REGISTRATION, SNAPSHOT),
                        new RunMemberCommand("income", RELEASE_B, REGISTRATION, SNAPSHOT)));
    }

    private static RunGroupPreflight accepted(RunGroupCommand command) {
        return new RunGroupPreflight(RunGroupRequestCodec.requestSha256(command), "b".repeat(64),
                List.of(member(0, RELEASE_A), member(1, RELEASE_B)),
                new BigDecimal("0.80"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10.00"), true, List.of());
    }

    private static RunGroupPreflight blocked() {
        return new RunGroupPreflight("a".repeat(64), null,
                List.of(new MemberPreflight(0, "income", RELEASE_A, REGISTRATION, SNAPSHOT,
                        "anthropic", "claude-opus-5", PRICING, 0, 0, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, "ESTIMATED_RANGE",
                        List.of("INSTANCE_DISABLED"))),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00"),
                false, List.of("GROUP_EXCEEDS_DAILY_BUDGET"));
    }

    private static MemberPreflight member(int index, UUID releaseId) {
        return new MemberPreflight(index, "income", releaseId, REGISTRATION, SNAPSHOT,
                "anthropic", "claude-opus-5", PRICING, 1_000, 2_000, 0, 4_000,
                new BigDecimal("0.10"), new BigDecimal("0.40"), "ESTIMATED_RANGE", List.of());
    }

    private static LabRunGroup existing(String requestSha256) {
        LabRunGroup group = new LabRunGroup();
        ReflectionTestUtils.setField(group, "id", GROUP);
        group.setBrainId(BRAIN);
        group.setIdempotencyKey(KEY);
        group.setRequestSha256(requestSha256);
        return group;
    }
}
