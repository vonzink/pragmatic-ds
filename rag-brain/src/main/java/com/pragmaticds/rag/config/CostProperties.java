package com.pragmaticds.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Typed tuning knobs for the per-brain daily LLM spend cap, bound from
 * ragbrain.rag.cost.* in application.yml. Opt-in and OFF by default:
 * dailyBudgetUsd &lt;= 0 means unlimited (SpendGuardService.isOverBudget always
 * returns false), matching the codebase's safety posture for new features.
 *
 * <p>{@code modelPrices} maps a model name (as returned by the model router /
 * AiResponse) to its {@link ModelPrice} — USD per 1,000,000 tokens, input and
 * output priced separately. {@code fallbackPrice} prices any model absent from
 * the map; it should be set conservatively (at or above the most expensive known
 * model) so an unrecognized model never silently under-counts spend.
 */
@ConfigurationProperties(prefix = "ragbrain.rag.cost")
public record CostProperties(
        double dailyBudgetUsd,
        Map<String, ModelPrice> modelPrices,
        ModelPrice fallbackPrice
) {
    /** USD price per 1,000,000 tokens, input and output priced separately. */
    public record ModelPrice(double inputPerMillion, double outputPerMillion) {}
}
