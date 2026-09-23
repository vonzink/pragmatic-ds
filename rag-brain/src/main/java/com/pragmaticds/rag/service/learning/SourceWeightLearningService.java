package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.config.ClusterJobLock;
import com.pragmaticds.rag.config.LearningProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import com.pragmaticds.rag.domain.FeedbackRating;
import com.pragmaticds.rag.domain.FeedbackSource;
import com.pragmaticds.rag.domain.RagAnswerFeedback;
import com.pragmaticds.rag.domain.WeightEventStatus;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.BrainSourceWeightEventRepository;
import com.pragmaticds.rag.repository.RagAnswerFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The learning job. On a cron ({@code ragbrain.rag.learning.job-cron}, hourly by
 * default) it aggregates recent {@code rag_answer_feedback} per document and
 * bounded-adjusts {@code brain_source_weights}, skipping every brain unless it is
 * learning-enabled and the global kill-switch is off.
 *
 * Opt-in like {@link com.pragmaticds.rag.service.ops.OpsDataRetentionService}: with
 * no feedback (or below {@code min-evidence}) it does nothing, so it is safe to run
 * before any real signal exists. Larger proposed moves and any downvote-driven
 * decrease against a top-authority source are routed to a PENDING review event
 * instead of being applied.
 */
@Service
public class SourceWeightLearningService {

    private static final Logger log = LoggerFactory.getLogger(SourceWeightLearningService.class);

    private static final String ACTOR = "learning-job";

    /** Resolves the document ids each trace retrieved (so a vote attributes to sources). */
    @FunctionalInterface
    public interface TraceDocumentResolver {
        Map<UUID, List<UUID>> documentIdsByTrace(Collection<UUID> traceIds);
    }

    /** True when a document is in the top authority tier (SourceTrustLevel.AUTHORITATIVE). */
    @FunctionalInterface
    public interface DocumentTrustResolver {
        boolean isTopAuthority(UUID documentId);
    }

    private final RagAnswerFeedbackRepository feedbackRepo;
    private final BrainSourceWeightEventRepository eventRepo;
    private final BrainRepository brainRepo;
    private final SourceWeightService weightService;
    private final TraceDocumentResolver traceDocs;
    private final DocumentTrustResolver trust;
    private final LearningProperties props;
    private final ClusterJobLock clusterJobLock;
    private final boolean globallyDisabled;

    /** Advisory-lock key so only one instance aggregates per tick (multi-instance safe). */
    private static final long LEARNING_JOB_LOCK_KEY = 4_701_010_001L;

    public SourceWeightLearningService(RagAnswerFeedbackRepository feedbackRepo,
                                       BrainSourceWeightEventRepository eventRepo,
                                       BrainRepository brainRepo,
                                       SourceWeightService weightService,
                                       TraceDocumentResolver traceDocs,
                                       DocumentTrustResolver trust,
                                       LearningProperties props,
                                       ClusterJobLock clusterJobLock,
                                       @Value("${ragbrain.rag.learning.globally-disabled:false}") boolean globallyDisabled) {
        this.feedbackRepo = feedbackRepo;
        this.eventRepo = eventRepo;
        this.brainRepo = brainRepo;
        this.weightService = weightService;
        this.traceDocs = traceDocs;
        this.trust = trust;
        this.props = props;
        this.clusterJobLock = clusterJobLock;
        this.globallyDisabled = globallyDisabled;
    }

    @Scheduled(cron = "${ragbrain.rag.learning.job-cron:0 15 * * * *}")
    public void runOnce() {
        if (globallyDisabled) {
            return; // app-level kill switch — off everywhere regardless of per-brain setting
        }
        // Run once cluster-wide: on multiple replicas only the lock winner aggregates.
        clusterJobLock.runIfLeader(LEARNING_JOB_LOCK_KEY, "learning-aggregation", this::aggregateEnabledBrains);
    }

    /** The aggregation pass over every learning-enabled brain; runs under the cluster lock. */
    void aggregateEnabledBrains() {
        for (Brain brain : brainRepo.findAll()) {
            if (!brain.isLearningEnabled()) {
                continue; // per-brain switch OFF — capture continues elsewhere, job skips
            }
            try {
                adjust(brain.getId());
            } catch (RuntimeException e) {
                // One brain's failure must not stop the others or crash the scheduler.
                log.error("Learning adjust failed for brain {}: {}", brain.getId(), e.getMessage());
            }
        }
    }

    /**
     * One brain's pass: tally weighted up/down votes per document over the lookback
     * window, then for each document with enough evidence propose a bounded new
     * weight and either APPLY it or route it to PENDING review.
     */
    @Transactional
    public void adjust(UUID brainId) {
        // Lookback matches the cron cadence generously; a document only moves once per run.
        // The processed-cursor (processed_at IS NULL) makes each feedback row consumed
        // exactly once: without it, one vote burst was re-tallied every run and ratcheted
        // a weight to its clamp bound / re-emitted the same PENDING proposal forever.
        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        List<RagAnswerFeedback> feedback =
                feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(brainId, since);
        if (feedback.isEmpty()) {
            return;
        }

        Map<UUID, List<UUID>> docsByTrace =
                traceDocs.documentIdsByTrace(feedback.stream().map(RagAnswerFeedback::getTraceId).distinct().toList());

        Map<UUID, int[]> tally = new HashMap<>(); // documentId -> {up, down} (admin-weighted)
        for (RagAnswerFeedback row : feedback) {
            List<UUID> docs = docsByTrace.get(row.getTraceId());
            if (docs == null || docs.isEmpty()) {
                continue; // trace retrieved nothing attributable
            }
            int votes = FeedbackSource.ADMIN.name().equals(row.getSource()) ? props.adminVoteWeight() : 1;
            boolean up = FeedbackRating.UP.name().equals(row.getRating());
            for (UUID docId : docs) {
                int[] ud = tally.computeIfAbsent(docId, k -> new int[2]);
                if (up) {
                    ud[0] += votes;
                } else {
                    ud[1] += votes;
                }
            }
        }

        for (Map.Entry<UUID, int[]> e : tally.entrySet()) {
            UUID docId = e.getKey();
            int up = e.getValue()[0];
            int down = e.getValue()[1];
            int evidence = up + down;
            if (evidence < props.minEvidence()) {
                continue; // min-evidence gate — neutral until enough signal
            }

            double current = weightService.weightFor(brainId, docId);
            double proposed = LearningWeightMath.computeNewWeight(current, up, down, props);
            if (proposed == current) {
                continue; // no net move (e.g. balanced votes at neutral)
            }

            boolean beyondThreshold = LearningWeightMath.proposedDelta(proposed) > props.reviewThreshold();
            // Decay alone always pulls a top-authority weight above 1.0 downward, toward
            // neutral, even on a net-zero (or net-positive) run with no real downvote —
            // that motion never buries the source, so it must not count as a "bury" risk.
            // Only a genuine net-negative vote signal (down outweighs up) paired with a
            // downward move is the pattern this gate exists to catch.
            boolean netNegativeSignal = down > up;
            boolean decreasesTopAuthority =
                    proposed < current && netNegativeSignal && trust.isTopAuthority(docId);

            if (beyondThreshold || decreasesTopAuthority) {
                writeEvent(brainId, docId, current, null, proposed, evidence,
                        WeightEventStatus.PENDING.name(), pendingReason(beyondThreshold, decreasesTopAuthority));
            } else {
                weightService.upsertWeight(brainId, docId, proposed, evidence, ACTOR);
                writeEvent(brainId, docId, current, proposed, proposed, evidence,
                        WeightEventStatus.APPLIED.name(), "auto-applied within bounds");
            }
        }

        // Consume every row this pass read, in the SAME transaction as the weight
        // upsert + event writes above. A retry (or the next cron tick) cannot
        // re-apply the burst, and a rejected proposal is not re-emitted next run.
        OffsetDateTime processedAt = OffsetDateTime.now();
        for (RagAnswerFeedback row : feedback) {
            row.setProcessedAt(processedAt);
        }
        feedbackRepo.saveAll(feedback);
    }

    private static String pendingReason(boolean beyondThreshold, boolean decreasesTopAuthority) {
        if (beyondThreshold && decreasesTopAuthority) {
            return "exceeds review threshold and lowers a top-authority source";
        }
        return beyondThreshold ? "exceeds review threshold" : "lowers a top-authority source";
    }

    private void writeEvent(UUID brainId, UUID documentId, Double oldWeight, Double newWeight,
                            double proposedWeight, int evidence, String status, String reason) {
        BrainSourceWeightEvent event = new BrainSourceWeightEvent(
                brainId, documentId, oldWeight, newWeight, proposedWeight, evidence, status, reason, ACTOR);
        eventRepo.save(event);
    }
}
