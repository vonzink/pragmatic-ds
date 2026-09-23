package com.pragmaticds.docengine.extraction.extract;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The confidence formula's inputs, stored separately on the field row (jsonb
 * {@code confidence_components}) so the score is auditable and tunable rather than a bare number.
 *
 * <p>{@code overall = spanConfidence × anchorStrength × normalizerCertainty}, scale 4 HALF_UP.
 * {@code spanConfidence} is the MINIMUM of the value spans' confidences — one shaky word taints
 * the whole value.
 */
public record ConfidenceBreakdown(
        BigDecimal spanConfidence, BigDecimal anchorStrength, BigDecimal normalizerCertainty) {

    public static final ConfidenceBreakdown ZERO =
            new ConfidenceBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public BigDecimal overall() {
        return spanConfidence
                .multiply(anchorStrength)
                .multiply(normalizerCertainty)
                .setScale(4, RoundingMode.HALF_UP);
    }
}
