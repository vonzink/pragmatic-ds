package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.config.ClusterJobLock;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabRunDocumentRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.repository.LabRunReviewSnapshotRepository;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.repository.LabConnectorRunGroupContextRepository;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.run.repository.LabSpendReservationRepository;
import com.pragmaticds.rag.repository.AnalysisRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a group purge deletes, in what order, and what holds it.
 *
 * <p>The order assertions are the point: every child foreign key is {@code ON DELETE RESTRICT},
 * so a wrong order is not a style problem but a runtime failure — and the connector context row
 * going first is what the plan's deletion order promises. What a purge can never touch —
 * releases, snapshots, catalog versions, pointer events, registrations — is structural: the
 * service holds no repository for any of them, so there is nothing here to verify beyond the
 * dependency list itself.
 */
class InstanceRetentionServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GROUP = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    private LabRunGroupRepository groups;
    private LabRunRepository runs;
    private LabModelUsageRepository usage;
    private LabSpendReservationRepository reservations;
    private LabConnectorRunGroupContextRepository contexts;
    private LabRunPayloadRepository payloads;
    private LabRunDocumentRepository runDocuments;
    private LabRunReviewSnapshotRepository reviewSnapshots;
    private LabDiscussionExchangeRepository exchanges;
    private LabDiscussionMessageRepository messages;
    private AnalysisRunRepository analysisRuns;
    private LabAuditService audit;
    private ClusterJobLock clusterJobLock;
    private InstanceRetentionService service;

    @BeforeEach
    void setUp() {
        groups = mock(LabRunGroupRepository.class);
        runs = mock(LabRunRepository.class);
        usage = mock(LabModelUsageRepository.class);
        reservations = mock(LabSpendReservationRepository.class);
        contexts = mock(LabConnectorRunGroupContextRepository.class);
        payloads = mock(LabRunPayloadRepository.class);
        runDocuments = mock(LabRunDocumentRepository.class);
        reviewSnapshots = mock(LabRunReviewSnapshotRepository.class);
        exchanges = mock(LabDiscussionExchangeRepository.class);
        messages = mock(LabDiscussionMessageRepository.class);
        analysisRuns = mock(AnalysisRunRepository.class);
        audit = mock(LabAuditService.class);
        clusterJobLock = mock(ClusterJobLock.class);
        when(clusterJobLock.runIfLeader(anyLong(), anyString(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return true;
        });
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = service(transactions, 30);
    }

    private InstanceRetentionService service(PlatformTransactionManager transactions, int days) {
        return new InstanceRetentionService(groups, runs, usage, reservations, contexts,
                payloads, runDocuments, reviewSnapshots, exchanges, messages, analysisRuns, audit,
                clusterJobLock, transactions, Clock.fixed(NOW, ZoneOffset.UTC), days, 500);
    }

    // ============================================================ order and outcome

    @Test
    void purgesATerminalGroupInThePlannedLeafFirstOrder() {
        LabRun first = member(LabRun.Status.SUCCEEDED, null);
        UUID analysis = UUID.randomUUID();
        LabRun second = member(LabRun.Status.FAILED, analysis);
        terminalGroup(List.of(first, second));
        when(contexts.existsById(GROUP)).thenReturn(true);
        when(analysisRuns.existsById(analysis)).thenReturn(true);
        when(reservations.deleteByRunId(any())).thenReturn(1L);
        when(usage.deleteByRunId(any())).thenReturn(1L);
        when(messages.deleteByRunId(any())).thenReturn(2);
        when(exchanges.deleteByRunId(any())).thenReturn(1L);
        when(payloads.deleteByRunId(any())).thenReturn(1L);
        when(runDocuments.deleteByRunId(any())).thenReturn(1L);

        InstanceRetentionService.GroupPurgeOutcome outcome = service.purgeGroup(BRAIN, GROUP);

        assertTrue(outcome.deleted());
        assertEquals(2, outcome.runsDeleted());
        assertEquals(2, outcome.reservationsDeleted());
        assertEquals(2, outcome.usageDeleted());
        assertTrue(outcome.connectorContextDeleted());
        assertEquals(1, outcome.analysisRunsDeleted());

        InOrder order = inOrder(contexts, reservations, usage, messages, exchanges, payloads,
                runDocuments, runs, analysisRuns, groups);
        order.verify(contexts).deleteById(GROUP);
        order.verify(reservations).deleteByRunId(first.getId());
        order.verify(usage).deleteByRunId(first.getId());
        order.verify(messages).deleteByRunId(first.getId());
        order.verify(exchanges).deleteByRunId(first.getId());
        order.verify(payloads).deleteByRunId(first.getId());
        order.verify(runDocuments).deleteByRunId(first.getId());
        order.verify(runs).delete(first);
        order.verify(runs).delete(second);
        // The analyzer row only after its run: lab_run.analysis_run_id is RESTRICT.
        order.verify(analysisRuns).deleteById(analysis);
        order.verify(groups).delete(any(LabRunGroup.class));
    }

    @Test
    void aGroupThatIsAlreadyGoneIsAnIdempotentSuccess() {
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.empty());

        InstanceRetentionService.GroupPurgeOutcome outcome = service.purgeGroup(BRAIN, GROUP);

        assertFalse(outcome.deleted());
        verifyNoInteractions(contexts);
        verify(groups, never()).delete(any(LabRunGroup.class));
    }

    // ============================================================ holds

    @Test
    void refusesAGroupThatIsNotTerminal() {
        LabRunGroup group = group(LabRunGroup.Status.PROCESSING, null);
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group));

        assertEquals(InstanceRetentionService.RetentionException.Code.GROUP_STILL_ACTIVE,
                assertThrows(InstanceRetentionService.RetentionException.class,
                        () -> service.purgeGroup(BRAIN, GROUP)).code());
        verifyNoInteractions(contexts, reservations, usage, payloads, runDocuments);
    }

    @Test
    void refusesAGroupWhoseMemberIsStillQueuedOrProcessing() {
        // A terminal-looking group with a live member is an inconsistency, and deletion is the
        // wrong tool to resolve an inconsistency with.
        terminalGroup(List.of(member(LabRun.Status.PROCESSING, null)));

        assertThrows(InstanceRetentionService.RetentionException.class,
                () -> service.purgeGroup(BRAIN, GROUP));
        verifyNoInteractions(contexts);
    }

    @Test
    void refusesAGroupWithADiscussionExchangeInFlight() {
        LabRun member = member(LabRun.Status.SUCCEEDED, null);
        terminalGroup(List.of(member));
        LabDiscussionExchange inFlight = new LabDiscussionExchange();
        inFlight.setStatus(LabRun.Status.PROCESSING);
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(member.getId()))
                .thenReturn(List.of(inFlight));

        assertThrows(InstanceRetentionService.RetentionException.class,
                () -> service.purgeGroup(BRAIN, GROUP));
        verifyNoInteractions(contexts);
    }

    // ============================================================ the sweep

    @Test
    void theSweepSelectsByTerminalAgeAndSkipsAHeldGroupWithoutStopping() {
        LabRunGroup held = group(LabRunGroup.Status.FAILED,
                OffsetDateTime.ofInstant(NOW.minusSeconds(90 * 86_400L), ZoneOffset.UTC));
        ReflectionTestUtils.setField(held, "id", UUID.randomUUID());
        held.setBrainId(BRAIN);
        LabRunGroup purgeable = group(LabRunGroup.Status.SUCCEEDED,
                OffsetDateTime.ofInstant(NOW.minusSeconds(60 * 86_400L), ZoneOffset.UTC));
        ReflectionTestUtils.setField(purgeable, "id", GROUP);
        purgeable.setBrainId(BRAIN);
        when(groups.findByTerminalAtBeforeOrderByTerminalAtAsc(any()))
                .thenReturn(List.of(held, purgeable));
        // The held group still carries a processing member; the purgeable one is clean.
        when(groups.findByIdAndBrainId(held.getId(), BRAIN)).thenReturn(Optional.of(held));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(held.getId()))
                .thenReturn(List.of(member(LabRun.Status.PROCESSING, null)));
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(purgeable));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(member(LabRun.Status.SUCCEEDED, null)));

        service.sweepExpiredGroups();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(groups).findByTerminalAtBeforeOrderByTerminalAtAsc(cutoff.capture());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(30),
                cutoff.getValue());
        // The held group was skipped, not forced — and did not stop the sweep.
        verify(groups, never()).delete(held);
        verify(groups).delete(purgeable);
    }

    @Test
    void theSweepRunsUnderTheClusterLockAndIdlesOnAnUndecidedPolicy() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        InstanceRetentionService undecided = service(transactions, 0);

        undecided.sweepExpiredGroups();
        verifyNoInteractions(clusterJobLock);

        when(groups.findByTerminalAtBeforeOrderByTerminalAtAsc(any())).thenReturn(List.of());
        service.sweepExpiredGroups();
        verify(clusterJobLock).runIfLeader(anyLong(), eq("instance-retention"), any());
    }

    // ============================================================ the tombstone

    @Test
    void theTombstoneIsAGroupSubjectCarryingCountsAndBooleansOnly() {
        terminalGroup(List.of(member(LabRun.Status.SUCCEEDED, null)));

        service.purgeGroup(BRAIN, GROUP);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(BRAIN), eq(InstanceRetentionService.GROUP_PURGE),
                eq(LabAuditEvent.Status.SUCCEEDED), eq(LabAuditEvent.SubjectType.GROUP),
                eq(GROUP), eq(null), details.capture());
        for (Object value : details.getValue().values()) {
            assertTrue(value instanceof Number || value instanceof Boolean,
                    "tombstone details must be counts and booleans, got " + value.getClass());
        }
    }

    // ============================================================ fixtures

    private void terminalGroup(List<LabRun> members) {
        LabRunGroup group = group(LabRunGroup.Status.SUCCEEDED,
                OffsetDateTime.ofInstant(NOW.minusSeconds(86_400L * 60), ZoneOffset.UTC));
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP)).thenReturn(members);
    }

    private static LabRunGroup group(LabRunGroup.Status status, OffsetDateTime terminalAt) {
        LabRunGroup group = new LabRunGroup();
        ReflectionTestUtils.setField(group, "id", GROUP);
        group.setBrainId(BRAIN);
        group.setMode(LabRunGroup.Mode.INDEPENDENT);
        group.setIdempotencyKey("purge-key");
        group.setRequestSha256("f".repeat(64));
        group.setStatus(status);
        group.setTerminalAt(terminalAt);
        return group;
    }

    private static LabRun member(LabRun.Status status, UUID analysisRunId) {
        LabRun run = new LabRun();
        run.setId(UUID.randomUUID());
        run.setBrainId(BRAIN);
        run.setInstanceSlug("income");
        run.setStatus(status);
        run.setAnalysisRunId(analysisRunId);
        return run;
    }
}
