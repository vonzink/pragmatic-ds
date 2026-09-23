package com.pragmaticds.rag.service.cost;

import com.pragmaticds.rag.config.CostProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-brain daily LLM spend cap: the enforcement half of the spend-cap circuit
 * breaker (design: per-brain daily spend cap; DB schema + usage accumulation in
 * V28 / {@link com.pragmaticds.rag.domain.BrainDailyUsage}).
 *
 * Opt-in and OFF by default: a resolved budget of {@code <= 0} means unlimited,
 * so {@link #isOverBudget(UUID)} always returns {@code false} for a brain (or
 * deployment) that never configured a cap — matching the codebase's safety
 * posture for new features (see {@link CostProperties}).
 *
 * {@link #recordSpend} always accumulates today's usage row regardless of
 * whether a budget is configured, so usage tracking (admin dashboard, and
 * {@code isOverBudget} itself) has continuous history the moment an operator
 * turns the cap on.
 */
@Service
public class SpendGuardService {

    private final CostProperties costProperties;
    private final BrainRepository brainRepository;
    private final BrainDailyUsageRepository usageRepository;
    private final Clock clock;

    public SpendGuardService(CostProperties costProperties,
                             BrainRepository brainRepository,
                             BrainDailyUsageRepository usageRepository,
                             Clock clock) {
        this.costProperties = costProperties;
        this.brainRepository = brainRepository;
        this.usageRepository = usageRepository;
        this.clock = clock;
    }

    /**
     * The budget that applies to a brain today: the brain's own
     * {@code dailyCostBudgetUsd} override when set (non-null), otherwise the
     * global {@code ragbrain.rag.cost.daily-budget-usd} default. An unknown
     * brain resolves to {@link BigDecimal#ZERO} (unlimited) rather than throwing,
     * since callers use this purely to decide whether to enforce a cap.
     */
    public BigDecimal resolveBudget(UUID brainId) {
        Brain brain = brainRepository.findById(brainId).orElse(null);
        if (brain == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal override = brain.getDailyCostBudgetUsd();
        return override != null ? override : BigDecimal.valueOf(costProperties.dailyBudgetUsd());
    }

    /**
     * Whether the brain has already met or exceeded its resolved daily budget.
     * Always {@code false} when the resolved budget is {@code <= 0} (unlimited);
     * in that case today's usage is never even looked up.
     */
    public boolean isOverBudget(UUID brainId) {
        BigDecimal budget = resolveBudget(brainId);
        if (budget.signum() <= 0) {
            return false;
        }
        BigDecimal spentToday = usageRepository.findByBrainIdAndUsageDate(brainId, LocalDate.now(clock))
                .map(u -> u.getCostEstimateUsd())
                .orElse(BigDecimal.ZERO);
        return spentToday.compareTo(budget) >= 0;
    }

    /**
     * USD cost of one model call, priced per 1,000,000 tokens (input/output
     * priced separately). Pure function of its inputs — no I/O, safe to unit
     * test directly. An unrecognized (or null) model name falls back to the
     * conservative {@code fallback-price} so a new/unpriced model never
     * silently under-counts spend.
     */
    public BigDecimal estimateCost(String modelName, long promptTokens, long completionTokens) {
        CostProperties.ModelPrice price = modelName == null
                ? costProperties.fallbackPrice()
                : costProperties.modelPrices().getOrDefault(modelName, costProperties.fallbackPrice());

        BigDecimal million = BigDecimal.valueOf(1_000_000L);
        BigDecimal promptCost = BigDecimal.valueOf(promptTokens)
                .multiply(BigDecimal.valueOf(price.inputPerMillion()))
                .divide(million, 6, RoundingMode.HALF_UP);
        BigDecimal completionCost = BigDecimal.valueOf(completionTokens)
                .multiply(BigDecimal.valueOf(price.outputPerMillion()))
                .divide(million, 6, RoundingMode.HALF_UP);
        return promptCost.add(completionCost);
    }

    /**
     * Accumulates one paid model call's usage into today's per-brain row (UPSERT,
     * see {@link BrainDailyUsageRepository#upsertSpend}). Null token counts (a
     * provider that did not report usage) are treated as zero rather than
     * throwing, so a missing-usage response never blocks the request pipeline.
     */
    // REQUIRES_NEW: the ask pipeline (AskService.ask) is deliberately non-@Transactional
    // (must not pin a JDBC connection across the embedding + model calls), so this
    // is the one write on that path that isn't self-transactional. Without its own
    // tx boundary, the @Modifying upsertSpend call below throws
    // TransactionRequiredException. Matches the AuditLogService.record /
    // RagTraceService.record convention: supply + commit an independent transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSpend(UUID brainId, String modelName, Long promptTokens, Long completionTokens) {
        long prompt = promptTokens == null ? 0L : promptTokens;
        long completion = completionTokens == null ? 0L : completionTokens;
        BigDecimal cost = estimateCost(modelName, prompt, completion);
        usageRepository.upsertSpend(brainId, LocalDate.now(clock), prompt, completion, cost);
    }
}
