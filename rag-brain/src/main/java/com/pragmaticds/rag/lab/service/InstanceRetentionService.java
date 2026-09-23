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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authorized idempotent deletion of one terminal run group, and the scheduled age sweep that
 * applies the same path.
 *
 * <p><b>The deletion order is the plan's, and the plan's order is the schema's.</b> Every child
 * foreign key is {@code ON DELETE RESTRICT}, so rows come out leaf-first:
 *
 * <pre>
 *   connector context → per member: spend reservation → model usage
 *                       → discussion messages → exchanges → encrypted payloads → run documents
 *                     → runs → linked analysis_runs → run group
 * </pre>
 *
 * <p><b>What a purge never touches</b>, structurally: this class holds no repository for
 * releases, snapshots, collections, catalog versions, pointer events, registrations, package
 * bindings, engine packages, or corpus documents — there is no code path by which purging run
 * history could reach any of them. Audit rows and idempotency records are refused by the
 * database itself ({@code V34}/{@code V35} triggers, pinned by {@code V41MigrationTest}); a
 * purge appends a value-free {@code GROUP} tombstone instead.
 *
 * <p><b>Holds.</b> A group that is not terminal — or that still carries a {@code QUEUED} or
 * {@code PROCESSING} member, or a {@code PROCESSING} discussion exchange — refuses with
 * {@code GROUP_STILL_ACTIVE}. The scheduled sweep only ever selects groups whose
 * {@code terminal_at} is older than the configured window, and skips a held group rather than
 * forcing it; the next sweep sees it again.
 *
 * <p><b>Idempotent re-entry.</b> Every step deletes by id and tolerates absence, so a purge that
 * died halfway resumes cleanly: already-deleted leaves count zero and the second call finishes
 * the rest. A purge of a group that is already gone is a success that removed nothing.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceRetentionService {

    private static final Logger log = LoggerFactory.getLogger(InstanceRetentionService.class);

    /** Distinct from the ops-retention lock key; both sweeps may share a database. */
    private static final long GROUP_RETENTION_LOCK_KEY = 4_701_010_003L;

    public static final String GROUP_PURGE = "GROUP_PURGE";

    private final LabRunGroupRepository groups;
    private final LabRunRepository runs;
    private final LabModelUsageRepository usage;
    private final LabSpendReservationRepository reservations;
    private final LabConnectorRunGroupContextRepository contexts;
    private final LabRunPayloadRepository payloads;
    private final LabRunDocumentRepository runDocuments;
    private final LabRunReviewSnapshotRepository reviewSnapshots;
    private final LabDiscussionExchangeRepository exchanges;
    private final LabDiscussionMessageRepository messages;
    private final AnalysisRunRepository analysisRuns;
    private final LabAuditService audit;
    private final ClusterJobLock clusterJobLock;
    private final TransactionTemplate isolated;
    private final Clock clock;
    private final int terminalRunDays;
    private final int batchSize;

    public InstanceRetentionService(LabRunGroupRepository groups,
                                    LabRunRepository runs,
                                    LabModelUsageRepository usage,
                                    LabSpendReservationRepository reservations,
                                    LabConnectorRunGroupContextRepository contexts,
                                    LabRunPayloadRepository payloads,
                                    LabRunDocumentRepository runDocuments,
                                    LabRunReviewSnapshotRepository reviewSnapshots,
                                    LabDiscussionExchangeRepository exchanges,
                                    LabDiscussionMessageRepository messages,
                                    AnalysisRunRepository analysisRuns,
                                    LabAuditService audit,
                                    ClusterJobLock clusterJobLock,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock,
                                    @Value("${ragbrain.instances.retention.terminal-run-days:0}")
                                    int terminalRunDays,
                                    @Value("${ragbrain.instances.retention.batch-size:500}")
                                    int batchSize) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.runDocuments = Objects.requireNonNull(runDocuments, "runDocuments");
        this.reviewSnapshots = Objects.requireNonNull(reviewSnapshots, "reviewSnapshots");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.analysisRuns = Objects.requireNonNull(analysisRuns, "analysisRuns");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clusterJobLock = Objects.requireNonNull(clusterJobLock, "clusterJobLock");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.isolated = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.terminalRunDays = terminalRunDays;
        this.batchSize = batchSize;
    }

    /** What one group purge removed. Counts and booleans only — never identifiers or bodies. */
    public record GroupPurgeOutcome(
            boolean deleted,
            int runsDeleted,
            int usageDeleted,
            int reservationsDeleted,
            int payloadsDeleted,
            int documentsDeleted,
            int exchangesDeleted,
            int messagesDeleted,
            boolean connectorContextDeleted,
            int analysisRunsDeleted) {

        public static GroupPurgeOutcome alreadyGone() {
            return new GroupPurgeOutcome(false, 0, 0, 0, 0, 0, 0, 0, false, 0);
        }
    }

    /** Refusals of a purge, as codes. The code is the entire disclosure. */
    public static final class RetentionException extends RuntimeException {
        public enum Code { GROUP_STILL_ACTIVE }

        private final Code code;

        public RetentionException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() { return code; }
    }

    /**
     * Deletes one authorized terminal group and everything its runs own, idempotently.
     *
     * @throws RetentionException {@code GROUP_STILL_ACTIVE} when the group is not terminal, a
     *     member is still queued or processing, or a discussion exchange is in flight
     */
    public GroupPurgeOutcome purgeGroup(UUID brainId, UUID groupId) {
        GroupPurgeOutcome outcome = isolated.execute(status -> deleteGroup(brainId, groupId));
        audit.record(brainId, GROUP_PURGE, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.GROUP, groupId, null,
                Map.of("deleted", outcome.deleted(),
                        "runs", outcome.runsDeleted(),
                        "usage", outcome.usageDeleted(),
                        "reservations", outcome.reservationsDeleted(),
                        "payloads", outcome.payloadsDeleted(),
                        "documents", outcome.documentsDeleted(),
                        "exchanges", outcome.exchangesDeleted(),
                        "messages", outcome.messagesDeleted(),
                        "connectorContext", outcome.connectorContextDeleted(),
                        "analysisRuns", outcome.analysisRunsDeleted()));
        return outcome;
    }

    /**
     * The scheduled terminal-age sweep, cluster-deduplicated. Applies exactly the same path as a
     * manual purge — same holds, same order, same tombstone — so there is one deletion behavior.
     */
    @Scheduled(cron = "${ragbrain.instances.retention.cron:0 45 3 * * *}")
    public void sweepExpiredGroups() {
        if (terminalRunDays <= 0) {
            return;   // Undecided policy: the startup validator refuses execution enablement in
                      // this state, and the sweep idles rather than inventing a window.
        }
        clusterJobLock.runIfLeader(GROUP_RETENTION_LOCK_KEY, "instance-retention", this::sweep);
    }

    void sweep() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(terminalRunDays);
        List<LabRunGroup> expired =
                groups.findByTerminalAtBeforeOrderByTerminalAtAsc(cutoff).stream()
                        .limit(batchSize)
                        .toList();
        int purged = 0;
        for (LabRunGroup group : expired) {
            try {
                if (purgeGroup(group.getBrainId(), group.getId()).deleted()) {
                    purged++;
                }
            } catch (RuntimeException held) {
                // A held group is skipped, not forced; the next sweep sees it again.
                log.info("Instance retention sweep skipped group {} ({})", group.getId(),
                        held.getClass().getSimpleName());
            }
        }
        if (purged > 0) {
            log.info("Instance retention sweep purged {} of {} expired groups", purged,
                    expired.size());
        }
    }

    // ---------------------------------------------------------------- the deletion itself

    private GroupPurgeOutcome deleteGroup(UUID brainId, UUID groupId) {
        LabRunGroup group = groups.findByIdAndBrainId(groupId, brainId).orElse(null);
        if (group == null) {
            return GroupPurgeOutcome.alreadyGone();
        }
        if (group.getTerminalAt() == null || !terminal(group.getStatus())) {
            throw new RetentionException(RetentionException.Code.GROUP_STILL_ACTIVE);
        }
        List<LabRun> members = runs.findByRunGroupIdOrderByMemberIndexAsc(groupId);
        boolean memberActive = members.stream().anyMatch(member ->
                member.getStatus() == LabRun.Status.QUEUED
                        || member.getStatus() == LabRun.Status.PROCESSING);
        if (memberActive) {
            throw new RetentionException(RetentionException.Code.GROUP_STILL_ACTIVE);
        }
        for (LabRun member : members) {
            boolean exchangeInFlight = exchanges
                    .findByRunIdOrderBySequenceNumberAsc(member.getId()).stream()
                    .anyMatch(exchange -> exchange.getStatus() == LabRun.Status.PROCESSING);
            if (exchangeInFlight) {
                throw new RetentionException(RetentionException.Code.GROUP_STILL_ACTIVE);
            }
        }

        // Plan order: the ownership context goes first, then each member's leaves, then the
        // runs, then the linked analyzer rows, then the group.
        boolean contextDeleted = contexts.existsById(groupId);
        if (contextDeleted) {
            contexts.deleteById(groupId);
        }
        int deletedReservations = 0;
        int deletedUsage = 0;
        int deletedMessages = 0;
        int deletedExchanges = 0;
        int deletedPayloads = 0;
        int deletedDocuments = 0;
        int deletedAnalysisRuns = 0;
        for (LabRun member : members) {
            UUID runId = member.getId();
            deletedReservations += (int) reservations.deleteByRunId(runId);
            deletedUsage += (int) usage.deleteByRunId(runId);
            deletedMessages += messages.deleteByRunId(runId);
            deletedExchanges += (int) exchanges.deleteByRunId(runId);
            deletedPayloads += (int) payloads.deleteByRunId(runId);
            reviewSnapshots.deleteByRunId(runId);
            deletedDocuments += (int) runDocuments.deleteByRunId(runId);
        }
        for (LabRun member : members) {
            UUID analysisRunId = member.getAnalysisRunId();
            runs.delete(member);
            runs.flush();
            if (analysisRunId != null && analysisRuns.existsById(analysisRunId)) {
                // Safe only after the run is gone: lab_run.analysis_run_id is RESTRICT, and
                // uq_lab_run_analysis_run guarantees no other run held this analyzer row.
                analysisRuns.deleteById(analysisRunId);
                deletedAnalysisRuns++;
            }
        }
        groups.delete(group);
        groups.flush();

        return new GroupPurgeOutcome(true, members.size(), deletedUsage, deletedReservations,
                deletedPayloads, deletedDocuments, deletedExchanges, deletedMessages,
                contextDeleted, deletedAnalysisRuns);
    }

    private static boolean terminal(LabRunGroup.Status status) {
        return status != LabRunGroup.Status.QUEUED && status != LabRunGroup.Status.PROCESSING;
    }
}
