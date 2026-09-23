package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.CatalogModel;
import com.pragmaticds.rag.lab.model.ModelEstimate.ActualCost;
import com.pragmaticds.rag.lab.model.ModelEstimate.EstimateInput;
import com.pragmaticds.rag.lab.model.ModelEstimate.ProviderUsage;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Prices one model call, before it runs and after it answers.
 *
 * <p>Two rules separate this from {@code SpendGuardService}, which stays exactly as it is for the
 * ask pipeline.
 *
 * <p><b>There is no fallback price.</b> A model that is not in the catalog is refused rather than
 * priced at some conservative default. The legacy guard's fallback exists so an unrecognized model
 * never silently under-counts spend on a path that has already run; here the estimate happens
 * <em>before</em> dispatch, so refusing is both possible and correct. Pricing a run against a
 * guess would put a number on the record that nothing can reproduce.
 *
 * <p><b>Absent usage is not zero.</b> {@code SpendGuardService.recordSpend} treats a null token
 * count as zero, which is right for a best-effort daily total that must never block a request.
 * It is wrong here: an instance run's recorded cost is evidence, and a zero that actually meant
 * "the provider told us nothing" would be indistinguishable from a call that really was free.
 * {@link #priceActual} returns {@link UsageQuality#UNAVAILABLE} with a null cost instead.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceCostEstimator {

    /** Money is stored as NUMERIC(18,6), so every figure is rounded to match before it leaves. */
    public static final int COST_SCALE = 6;

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private final InstanceModelCatalogService catalog;
    private final InstanceTokenEstimator tokens;

    public InstanceCostEstimator(InstanceModelCatalogService catalog,
                                 InstanceTokenEstimator tokens) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /**
     * Bounds what one pinned run will consume and cost.
     *
     * <p>Input is everything that will occupy the context window: both prompts, the rendered
     * parsed facts, the release's retrieval allowance, every tool schema, the output schema, and
     * any prior discussion. Output is bounded by the release's ceiling and by the model's own,
     * whichever is lower, because a release cannot ask for more output than the model can produce.
     *
     * <p>The output <em>minimum</em> is zero on purpose. A refusal, an empty envelope, or a
     * provider error can all produce almost nothing, and claiming a floor the run might not reach
     * would make the lower bound useless as a lower bound.
     */
    public ModelEstimate estimate(String provider, String model, EstimateInput input) {
        Objects.requireNonNull(input, "input");
        CatalogModel priced = catalog.require(provider, model);

        List<String> texts = new ArrayList<>();
        texts.add(input.systemPrompt());
        texts.add(input.taskPrompt());
        texts.add(input.parsedFactBlock());
        texts.add(input.outputSchema());
        texts.addAll(input.toolSchemas());

        InstanceTokenEstimator.TokenRange inputTokens = tokens.estimateAll(texts)
                // An allowance and a saved conversation are already counts, not text.
                .plus(InstanceTokenEstimator.TokenRange.exactly(input.corpusTokenAllowance()))
                .plus(InstanceTokenEstimator.TokenRange.exactly(input.conversationTokenCount()))
                .plus(InstanceTokenEstimator.TokenRange.exactly(input.parsedFactTokenAllowance()));

        long outputMax = Math.min(input.outputCeiling(), priced.outputTokenCeiling());
        InstanceTokenEstimator.TokenRange outputTokens =
                new InstanceTokenEstimator.TokenRange(0, outputMax,
                        EstimateQuality.EXACT_TOKENIZER);

        BigDecimal costMin = cost(priced, inputTokens.min(), 0L, outputTokens.min());
        BigDecimal costMax = cost(priced, inputTokens.max(), 0L, outputTokens.max());

        // The estimate is only as good as its worst component, and the input half is what carries
        // measured text. A cheap-looking exact label on a heuristic count is the failure mode.
        EstimateQuality quality = inputTokens.quality() == EstimateQuality.EXACT_TOKENIZER
                && outputTokens.quality() == EstimateQuality.EXACT_TOKENIZER
                ? EstimateQuality.EXACT_TOKENIZER
                : EstimateQuality.ESTIMATED_RANGE;

        return new ModelEstimate(catalog.active().versionId(), priced.provider(), priced.model(),
                inputTokens.min(), inputTokens.max(), outputTokens.min(), outputTokens.max(),
                costMin, costMax, quality);
    }

    /**
     * Prices what the provider actually reported, against the version the run was pinned to.
     *
     * <p>The historical version, not today's: pricing a finished run against a catalog that has
     * since changed is the exact mistake versioning exists to prevent, and it would silently
     * restate costs every time a price moved.
     *
     * <p>A usage report with no input and no output tokens is not priced at all. Neither is one
     * whose model is absent from the version it claims — that combination means the run's pinned
     * identity and the catalog disagree, and guessing which is right is not an option.
     */
    public ActualCost priceActual(UUID pricingVersionId, String provider, String model,
                                  ProviderUsage usage) {
        Objects.requireNonNull(usage, "usage");
        if (usage.isAbsent()) {
            return new ActualCost(null, null, UsageQuality.UNAVAILABLE);
        }
        CatalogModel priced = catalog.findInVersion(pricingVersionId, provider, model).orElse(null);
        if (priced == null) {
            return new ActualCost(null, null, UsageQuality.UNAVAILABLE);
        }

        long input = usage.inputTokens() == null ? 0L : usage.inputTokens();
        long cached = usage.cachedInputTokens() == null ? 0L : usage.cachedInputTokens();
        long output = usage.outputTokens() == null ? 0L : usage.outputTokens();

        // Providers report cached input as a subset of input tokens, so charging both rates on the
        // same tokens would double-count them. Only the uncached remainder pays the full rate, and
        // a provider that reports more cached than input is clamped rather than trusted into a
        // negative charge.
        long billableCached = Math.min(cached, input);
        long uncached = input - billableCached;

        BigDecimal total = cost(priced, uncached, billableCached, output);
        return new ActualCost(total, input + output, UsageQuality.REPORTED);
    }

    /**
     * USD for one call at one entry's rates.
     *
     * <p>Cached input falls back to the full input rate when the entry declares none. Null there
     * means "this provider has no separate cached rate", not "cached input is free", so charging
     * zero for it would invent a discount the provider never offered.
     */
    private static BigDecimal cost(CatalogModel priced, long inputTokens, long cachedTokens,
                                   long outputTokens) {
        BigDecimal cachedRate = priced.cachedInputUsdPerMillion() == null
                ? priced.inputUsdPerMillion()
                : priced.cachedInputUsdPerMillion();
        return perMillion(inputTokens, priced.inputUsdPerMillion())
                .add(perMillion(cachedTokens, cachedRate))
                .add(perMillion(outputTokens, priced.outputUsdPerMillion()))
                .setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal perMillion(long tokenCount, BigDecimal usdPerMillion) {
        return BigDecimal.valueOf(tokenCount)
                .multiply(usdPerMillion)
                .divide(MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }
}
