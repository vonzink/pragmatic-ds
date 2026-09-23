package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.run.domain.EstimateQuality;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What one model call is expected to consume and cost, before it runs.
 *
 * <p>Always a range, even when the tokenizer is exact — in that case the bounds are simply equal.
 * One shape rather than two means no caller has to branch on quality to read a number, and the
 * quality says how far apart the bounds are allowed to be rather than whether they exist.
 *
 * <p>{@code costUsdMax} is what a budget check reserves. Reserving the maximum is the whole point:
 * a reservation that held the midpoint would let a run that lands at its upper bound overshoot a
 * budget that had already approved it.
 */
public record ModelEstimate(
        UUID pricingVersionId,
        String provider,
        String model,
        long inputTokensMin,
        long inputTokensMax,
        long outputTokensMin,
        long outputTokensMax,
        BigDecimal costUsdMin,
        BigDecimal costUsdMax,
        EstimateQuality quality) {

    public ModelEstimate {
        Objects.requireNonNull(pricingVersionId, "pricingVersionId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(costUsdMin, "costUsdMin");
        Objects.requireNonNull(costUsdMax, "costUsdMax");
        Objects.requireNonNull(quality, "quality");
        if (inputTokensMin < 0 || outputTokensMin < 0
                || inputTokensMax < inputTokensMin || outputTokensMax < outputTokensMin
                || costUsdMax.compareTo(costUsdMin) < 0) {
            throw new IllegalArgumentException("an estimate's bounds must be ordered");
        }
    }

    /**
     * Everything that will occupy the context window of one pinned run.
     *
     * @param corpusTokenAllowance the release's retrieval ceiling rather than what was actually
     *     retrieved: an estimate is computed before retrieval runs, so the allowance is the only
     *     honest bound on it.
     * @param conversationTokenCount prior discussion context; zero for a fresh run.
     * @param parsedFactTokenAllowance an exact upper bound for a fact block that has not been
     *     rendered yet. Preflight uses it because rendering feeds {@code prompt_sha256} and
     *     belongs to execution; execution passes the real block in {@code parsedFactBlock} and
     *     leaves this zero. Supplying both simply bounds the run more conservatively.
     */
    public record EstimateInput(
            String systemPrompt,
            String taskPrompt,
            String parsedFactBlock,
            long corpusTokenAllowance,
            List<String> toolSchemas,
            String outputSchema,
            long conversationTokenCount,
            long parsedFactTokenAllowance,
            int outputCeiling) {

        public EstimateInput {
            toolSchemas = List.copyOf(Objects.requireNonNull(toolSchemas, "toolSchemas"));
            if (corpusTokenAllowance < 0 || conversationTokenCount < 0 || outputCeiling < 0
                    || parsedFactTokenAllowance < 0) {
                throw new IllegalArgumentException("token allowances must not be negative");
            }
        }
    }

    /**
     * What a provider said it consumed. Every field is nullable because providers omit
     * categories — and an omitted category stays null all the way through rather than becoming a
     * zero that would later read as a measurement.
     */
    public record ProviderUsage(Long inputTokens, Long cachedInputTokens, Long outputTokens) {

        /** True when the provider reported nothing this call can be priced from. */
        public boolean isAbsent() {
            return inputTokens == null && outputTokens == null;
        }
    }

    /** One priced call. {@code costUsd} is null exactly when the usage was absent. */
    public record ActualCost(
            BigDecimal costUsd,
            Long totalTokens,
            com.pragmaticds.rag.lab.run.domain.UsageQuality quality) {}
}
