package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabRunDocumentRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.repository.LabRunReviewSnapshotRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authorized idempotent deletion of one Lab run, and the scheduled age sweep that applies the same
 * path.
 *
 * <p><b>The deletion order is dictated by the schema, not by preference.</b> Every child FK in V34
 * is {@code ON DELETE RESTRICT}, so rows come out leaf-first:
 *
 * <pre>
 *   lab_spend_reservation / lab_model_usage (V39 leaves, zero-count for legacy runs)
 *   → lab_discussion_message → lab_discussion_exchange → lab_run_payload
 *   → lab_run_document → lab_run → analysis_runs
 *   → the run's now-empty lab_run_group (with its connector context, if any)
 * </pre>
 *
 * <p>The linked analyzer row goes <em>last</em>, after {@code lab_run}, because {@code
 * lab_run.analysis_run_id} references it under RESTRICT — deleting it earlier is refused by the
 * database. {@code lab_audit_event} is deliberately absent from that list: V34's trigger refuses
 * DELETE on it, and a purge is supposed to leave a value-free tombstone behind, not erase the
 * record that the run existed. The consequence is stated plainly rather than worked around: a brain
 * holding Lab rows cannot itself be deleted while they exist.
 *
 * <p><b>An in-flight run is never deleted underneath its own execution.</b> Purge takes the same
 * pessimistic run lock a discussion write takes; a {@code PROCESSING} run, or a run with a live
 * {@code PROCESSING} exchange, conflicts instead of being torn out from under an external call.
 *
 * <p><b>The Document Engine package is never touched.</b> There is no engine client on this class.
 * Deleting a Lab run deletes RAG Brain's record of an analysis; the immutable engine result and its
 * package remain under the engine's own authorized retention lifecycle.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.lab", name = "enabled", havingValue = "true")
public class LabRetentionService {

    private static final Logger log = LoggerFactory.getLogger(LabRetentionService.class);

    /** One sweep's cap, matching the repository's bounded page. */
    private static final int SWEEP_LIMIT = 200;

    private final LabRunRepository runs;
    private final LabRunDocumentRepository runDocuments;
    private final LabRunReviewSnapshotRepository reviewSnapshots;
    private final LabRunPayloadRepository payloads;
    private final LabDiscussionExchangeRepository exchanges;
    private final LabDiscussionMessageRepository messages;
    private final AnalysisRunRepository analysisRuns;
    private final LabModelUsageRepository usage;
    private final LabSpendReservationRepository reservations;
    private final LabConnectorRunGroupContextRepository connectorContexts;
    private final LabRunGroupRepository groups;
    private final LabAuditService audit;
    private final TransactionTemplate isolated;
    private final int retentionDays;

    public LabRetentionService(LabRunRepository runs,
                               LabRunDocumentRepository runDocuments,
                               LabRunReviewSnapshotRepository reviewSnapshots,
                               LabRunPayloadRepository payloads,
                               LabDiscussionExchangeRepository exchanges,
                               LabDiscussionMessageRepository messages,
                               AnalysisRunRepository analysisRuns,
                               LabModelUsageRepository usage,
                               LabSpendReservationRepository reservations,
                               LabConnectorRunGroupContextRepository connectorContexts,
                               LabRunGroupRepository groups,
                               LabAuditService audit,
                               PlatformTransactionManager transactionManager,
                               @Value("${ragbrain.lab.retention-days:0}") int retentionDays) {
        this.runs = runs;
        this.runDocuments = runDocuments;
        this.reviewSnapshots = reviewSnapshots;
        this.payloads = payloads;
        this.exchanges = exchanges;
        this.messages = messages;
        this.analysisRuns = analysisRuns;
        this.usage = usage;
        this.reservations = reservations;
        this.connectorContexts = connectorContexts;
        this.groups = groups;
        this.audit = audit;
        this.retentionDays = retentionDays;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What one purge removed. Counts and booleans only — never an identifier set or a body. */
    public record PurgeOutcome(
            boolean deleted,
            int messagesDeleted,
            int exchangesDeleted,
            int payloadsDeleted,
            int documentsDeleted,
            boolean analysisRunDeleted) {

        /** The idempotent second call: the run is already gone, so nothing was removed. */
        public static PurgeOutcome alreadyGone() {
            return new PurgeOutcome(false, 0, 0, 0, 0, false);
        }
    }

    /**
     * The prototype requires an explicit positive retention window while the Lab is on.
     *
     * <p>Called at the top of every Lab request rather than at startup on purpose: an absent or
     * nonpositive window must fail Lab requests safely while ordinary RAG Brain startup and every
     * non-Lab route stay available. A startup guard would take the whole application down for a
     * prototype's misconfiguration.
     */
    public void requireConfigured() {
        if (retentionDays <= 0) {
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.RETENTION_NOT_CONFIGURED);
        }
    }

    /** The configured window in days; zero or negative means unconfigured. */
    public int retentionDays() {
        return retentionDays;
    }

    /**
     * Deletes one authorized run and everything it owns, idempotently.
     *
     * @throws IncomeLabService.LabRequestException {@code RUN_IN_PROGRESS} when the run or one of
     *     its exchanges is still {@code PROCESSING} under a live lease
     */
    public PurgeOutcome purge(UUID brainId, UUID runId) {
        PurgeOutcome outcome = isolated.execute(status -> deleteLocked(brainId, runId));
        audit.record(brainId, LabAuditService.RUN_PURGE, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.RUN, runId, null,
                Map.of("deleted", outcome.deleted(),
                        "messages", outcome.messagesDeleted(),
                        "exchanges", outcome.exchangesDeleted(),
                        "payloads", outcome.payloadsDeleted(),
                        "documents", outcome.documentsDeleted(),
                        "analysisRun", outcome.analysisRunDeleted()));
        return outcome;
    }

    /**
     * The scheduled age sweep. Applies exactly the same path as a manual purge — same lock, same
     * order, same tombstone — so there is one deletion behavior rather than two that could drift.
     */
    @Scheduled(fixedDelayString = "${ragbrain.lab.retention-sweep-ms:3600000}",
            initialDelayString = "${ragbrain.lab.retention-sweep-ms:3600000}")
    public void sweepExpiredRuns() {
        if (retentionDays <= 0) {
            return;   // Unconfigured: Lab requests already fail closed; the sweep simply idles.
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        List<LabRun> expired = runs.findFirst200ByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        int purged = 0;
        for (LabRun run : expired) {
            try {
                if (purge(run.getBrainId(), run.getId()).deleted()) {
                    purged++;
                }
            } catch (RuntimeException stillActive) {
                // A run that is still processing is skipped, not forced; the next sweep sees it.
                log.info("Lab retention sweep skipped run {} ({})", run.getId(),
                        stillActive.getClass().getSimpleName());
            }
        }
        if (purged > 0) {
            log.info("Lab retention sweep purged {} of {} expired runs (cap {})", purged,
                    expired.size(), SWEEP_LIMIT);
        }
    }

    // ---------------------------------------------------------------- the deletion itself

    private PurgeOutcome deleteLocked(UUID brainId, UUID runId) {
        LabRun run = runs.lockByIdAndBrainId(runId, brainId).orElse(null);
        if (run == null) {
            // Idempotent: a second DELETE of the same run is a success with nothing removed.
            return PurgeOutcome.alreadyGone();
        }
        if (run.getStatus() == LabRun.Status.PROCESSING) {
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.RUN_IN_PROGRESS);
        }
        List<LabDiscussionExchange> transcript =
                exchanges.findByRunIdOrderBySequenceNumberAsc(runId);
        boolean exchangeInFlight = transcript.stream()
                .anyMatch(exchange -> exchange.getStatus() == LabRun.Status.PROCESSING);
        if (exchangeInFlight) {
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.RUN_IN_PROGRESS);
        }

        // V39 put every run — including every backfilled historical one — in a group. A run that
        // shares its group with other members is one slice of a comparison or batch, and pulling
        // it out through this per-run path would mutilate the group's record; the group purge in
        // InstanceRetentionService is the honest way to remove it.
        UUID groupId = run.getRunGroupId();
        if (groupId != null
                && runs.findByRunGroupIdOrderByMemberIndexAsc(groupId).size() > 1) {
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.RUN_IN_GROUP);
        }

        UUID analysisRunId = run.getAnalysisRunId();
        // V39 leaves before V34 leaves: reservation and usage rows reference the run under
        // RESTRICT, exactly like the older children. Legacy runs have none; both deletes are
        // zero-count no-ops for them.
        reservations.deleteByRunId(runId);
        usage.deleteByRunId(runId);
        int deletedMessages = messages.deleteByRunId(runId);
        int deletedExchanges = (int) exchanges.deleteByRunId(runId);
        int deletedPayloads = (int) payloads.deleteByRunId(runId);
        reviewSnapshots.deleteByRunId(runId);
        int deletedDocuments = (int) runDocuments.deleteByRunId(runId);
        runs.delete(run);
        runs.flush();

        if (groupId != null) {
            // The run was its group's only member, so the group — and, for a connector-created
            // group, its ownership context — goes with it rather than surviving as an orphan
            // that authorizes reading nothing.
            if (connectorContexts.existsById(groupId)) {
                connectorContexts.deleteById(groupId);
            }
            groups.deleteById(groupId);
            groups.flush();
        }

        boolean analysisRunDeleted = false;
        if (analysisRunId != null && analysisRuns.existsById(analysisRunId)) {
            // Safe only AFTER lab_run is gone: lab_run.analysis_run_id is ON DELETE RESTRICT, and
            // uq_lab_run_analysis_run guarantees no other Lab run held this same analyzer row.
            analysisRuns.deleteById(analysisRunId);
            analysisRunDeleted = true;
        }
        return new PurgeOutcome(true, deletedMessages, deletedExchanges, deletedPayloads,
                deletedDocuments, analysisRunDeleted);
    }
}
