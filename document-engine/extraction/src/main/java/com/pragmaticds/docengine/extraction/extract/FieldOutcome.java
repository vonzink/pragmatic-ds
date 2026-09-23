package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import java.util.List;
import java.util.UUID;

/**
 * The engine's verdict for one schema field OCCURRENCE — exactly one per field for an ungrouped
 * field, and one per group key for a grouped one. A missing occurrence is a RESULT: {@link
 * #missing(FieldSpec, String)} yields method NONE, no evidence, zero confidence, and the caller
 * persists it as MANUAL_REVIEW_REQUIRED.
 *
 * @param method the extractor rung that succeeded, or NONE
 * @param pageId the page the value was read from (null when missing)
 * @param displayedText exactly as it appears on the page ("$3,565.87")
 * @param rawValue the captured text before normalization
 * @param valueEvidence VALUE-role evidence in ordinal order — never empty when found, with one
 *     exception: a SIGNATURE_PRESENCE UNSIGNED outcome is found with NO value evidence, because
 *     its value IS the absence of ink in the located region (Spec 3, D5)
 * @param labelEvidence LABEL-role evidence in ordinal order (empty for label-less methods)
 * @param groupKey the occurrence's key as PRINTED on the form — {@code A}/{@code B}/{@code C} for
 *     a column group, the row ordinal for a row group — and null for an ungrouped field, which is
 *     exactly today's behaviour (Spec 5a, design D1/D2)
 */
public record FieldOutcome(
        FieldSpec field,
        ExtractionMethod method,
        double anchorStrength,
        UUID pageId,
        String displayedText,
        String rawValue,
        NormalizedValue normalized,
        List<EvidenceRef> valueEvidence,
        List<EvidenceRef> labelEvidence,
        ConfidenceBreakdown confidence,
        String groupKey) {

    /**
     * The pre-Spec-5a shape: an UNGROUPED outcome, whose group key is null. Every rung that
     * predates grouping constructs through this, so the detector rungs and every test that
     * compares against their outcomes are untouched by the group dimension.
     */
    public FieldOutcome(
            FieldSpec field,
            ExtractionMethod method,
            double anchorStrength,
            UUID pageId,
            String displayedText,
            String rawValue,
            NormalizedValue normalized,
            List<EvidenceRef> valueEvidence,
            List<EvidenceRef> labelEvidence,
            ConfidenceBreakdown confidence) {
        this(
                field,
                method,
                anchorStrength,
                pageId,
                displayedText,
                rawValue,
                normalized,
                valueEvidence,
                labelEvidence,
                confidence,
                null);
    }

    public static FieldOutcome missing(FieldSpec field) {
        return missing(field, null);
    }

    /**
     * A missing OCCURRENCE. "Column C is empty" and "we failed to read column C" must stay
     * distinguishable, so the row is written rather than skipped — and it is written with NO
     * normalized value at all, because a defaulted {@code 0.00} in a rental expense silently
     * changes a qualifying-income calculation (design D5).
     */
    public static FieldOutcome missing(FieldSpec field, String groupKey) {
        return new FieldOutcome(
                field,
                ExtractionMethod.NONE,
                0.0,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                ConfidenceBreakdown.ZERO,
                groupKey);
    }

    public boolean found() {
        return method != ExtractionMethod.NONE;
    }
}
