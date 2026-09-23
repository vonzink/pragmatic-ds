package com.pragmaticds.rag.service.analyze;

/**
 * Deterministic per-token price table (USD per token). Keeps analyze cost
 * accounting self-contained and testable. Unknown provider/model → 0.0 (cost is
 * advisory, never a gate). Prices are per single token (Anthropic list price /
 * 1e6). Update as the price sheet changes.
 */
final class CostTable {

    private CostTable() {}

    static double usd(String provider, String model, int inputTokens, int outputTokens) {
        double inRate;
        double outRate;
        String p = provider == null ? "" : provider.toLowerCase(java.util.Locale.US);
        if ("anthropic".equals(p)) {
            // Sonnet-class default rates: $3 / $15 per 1M tokens.
            inRate = 3.0 / 1_000_000;
            outRate = 15.0 / 1_000_000;
        } else if ("openai".equals(p)) {
            inRate = 2.5 / 1_000_000;
            outRate = 10.0 / 1_000_000;
        } else {
            return 0.0;
        }
        return inputTokens * inRate + outputTokens * outRate;
    }
}
