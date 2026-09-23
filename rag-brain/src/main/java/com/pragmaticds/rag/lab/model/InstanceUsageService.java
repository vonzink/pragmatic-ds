package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.model.ModelEstimate.ActualCost;
import com.pragmaticds.rag.lab.model.ModelEstimate.ProviderUsage;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Records what one instance run actually consumed, once.
 *
 * <p><b>Absent is not zero.</b> A provider that returns no usage metadata leaves the run's usage
 * row {@link UsageQuality#UNAVAILABLE} with no numbers at all. Writing zeros would make a call
 * nobody measured indistinguishable from a call that really was free, and that figure would then
 * flow into every cost report looking like a measurement. The same applies per category: a
 * provider that reports totals but not the cached split leaves the cached column null.
 *
 * <p><b>Priced against the run's own catalog version.</b> Not today's — pricing a finished run
 * against a catalog that has since changed is exactly what versioning exists to prevent, and it
 * would silently restate historical costs every time a rate moved.
 *
 * <p><b>Daily spend is recorded exactly once, and only when it was measured.</b> The row is the
 * gate: {@code report} is a no-op on a usage row that has already left PENDING, so a retried
 * completion cannot double-count a brain's budget. A run whose usage is unavailable contributes
 * nothing to the daily total rather than contributing a made-up zero.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceUsageService {

    private static final Logger log = LoggerFactory.getLogger(InstanceUsageService.class);

    private final LabModelUsageRepository usage;
    private final InstanceCostEstimator estimator;
    private final SpendGuardService spend;
    private final InstanceControlMetrics metrics;
    private final TransactionTemplate isolated;

    public InstanceUsageService(LabModelUsageRepository usage,
                                InstanceCostEstimator estimator,
                                SpendGuardService spend,
                                InstanceControlMetrics metrics,
                                PlatformTransactionManager transactionManager) {
        this.usage = Objects.requireNonNull(usage, "usage");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.spend = Objects.requireNonNull(spend, "spend");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.isolated = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Reads a provider response into the usage vocabulary, keeping absent categories absent. */
    public static ProviderUsage from(AiResponse response) {
        if (response == null) {
            return new ProviderUsage(null, null, null);
        }
        return new ProviderUsage(
                asLong(response.promptTokens()),
                asLong(response.cachedPromptTokens()),
                asLong(response.completionTokens()));
    }

    /**
     * Records one run's actual usage and its cost.
     *
     * <p>Returns what was recorded, or empty when there was no usage row to record against — a
     * run created before this path existed, or one whose group was purged. Absence is not an
     * error here: the run itself is already terminal and its truth does not depend on this.
     */
    public Optional<ActualCost> report(UUID runId, UUID brainId, ProviderUsage reported) {
        Objects.requireNonNull(reported, "reported");
        return Optional.ofNullable(isolated.execute(status -> {
            LabModelUsage row = usage.findByRunIdAndBrainId(runId, brainId).orElse(null);
            if (row == null) {
                return null;
            }
            if (row.getUsageQuality() != UsageQuality.PENDING) {
                // The row is the idempotency gate. A retried completion must not double-count a
                // brain's budget, and the database guard would refuse the second write anyway.
                log.debug("Instance run {} already recorded its usage", runId);
                return null;
            }

            ActualCost cost = estimator.priceActual(row.getPricingVersionId(), row.getProvider(),
                    row.getModel(), reported);
            if (cost.quality() == UsageQuality.UNAVAILABLE) {
                row.unavailable();
                usage.saveAndFlush(row);
                metrics.usageQuality(cost.quality(), row.getProvider());
                // Nothing measured, so nothing is added to the daily total. A zero here would
                // quietly claim the call was free.
                return cost;
            }

            row.report(cost.quality(), reported.inputTokens(), reported.cachedInputTokens(),
                    reported.outputTokens(), cost.totalTokens(), cost.costUsd());
            usage.saveAndFlush(row);
            // Once per row, guarded by the PENDING check above: a double report never counts twice.
            metrics.usageQuality(cost.quality(), row.getProvider());

            // The shared daily counter the ask pipeline also writes to, so a brain's budget sees
            // instance runs and ordinary asks in one place. It treats a null category as zero,
            // which is right for a rolling aggregate and only reached here because the call WAS
            // measured; the precise per-category record stays on the usage row above.
            spend.recordSpend(brainId, row.getModel(),
                    reported.inputTokens(), reported.outputTokens());
            return cost;
        }));
    }

    private static Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
