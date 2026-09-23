package com.pragmaticds.docengine.classification.rules;

import java.util.List;

/**
 * One parsed, applicable rule pack: the unit {@code PageClassifier} scores with. Score formula
 * (V6 seed comment): {@code min(1, sum(matched weights) / targetScore)}; below {@code
 * minConfidence} the pack cannot win.
 *
 * <p>{@code plausiblePageMax} (Phase D, optional): the most pages one document of this type
 * plausibly spans — a 14-page "paystub" is three paystubs. Consumed ONLY by the boundary-window
 * planner (a longer run opens an ambiguity window); it never changes classification or the
 * deterministic split. Null — every pack authored before Phase D — declares nothing and the
 * mechanism is inert for the type.
 */
public record RulePack(
        String documentTypeCode,
        String version,
        double minConfidence,
        double targetScore,
        List<Anchor> anchors,
        Integer plausiblePageMax) {

    /** The pre-Phase-D shape: no plausible page count declared. */
    public RulePack(
            String documentTypeCode,
            String version,
            double minConfidence,
            double targetScore,
            List<Anchor> anchors) {
        this(documentTypeCode, version, minConfidence, targetScore, anchors, null);
    }
}
