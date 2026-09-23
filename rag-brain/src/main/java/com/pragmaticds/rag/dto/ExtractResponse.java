package com.pragmaticds.rag.dto;

import java.util.List;
import java.util.Map;

/**
 * Canonical single-document extraction response. `values` holds only the
 * extractor's manifest keys (numeric value-keys, string meta-keys); an ERROR
 * status still returns 200 with `reason` set and `values` empty — the console
 * decides how to render it (matches how brain runs surface errors).
 */
public record ExtractResponse(
        String status,
        Map<String, Object> values,
        List<String> warnings,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        String reason
) {
}
