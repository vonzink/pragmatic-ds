package com.pragmaticds.rag.service.extract;

import java.util.List;
import java.util.Map;

/**
 * The engine-internal result of one document extraction. Mapped 1:1 to the HTTP
 * response by {@code ExtractController}. {@code values} contains ONLY the
 * extractor's manifest keys — value-keys coerced to {@link Double}, meta-keys
 * kept as {@link String}. Everything else the model returned is dropped and
 * recorded in {@code warnings} instead of silently passed through.
 */
public record ExtractionResult(
        Status status,
        Map<String, Object> values,
        List<String> warnings,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        String reason
) {
    public ExtractionResult {
        values = values == null ? Map.of() : Map.copyOf(values);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public enum Status { SUCCESS, ERROR }
}
