package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;

/**
 * The ONE derivation of the wire's {@code effectiveStatus} (design D11): whose value a read model
 * is serving for an occurrence, and whether a consumer may use it. Both read-model assemblers —
 * {@code DocumentFieldsReader} and {@code PackageExportController} — call {@link #of} and nothing
 * else computes it, so {@code /fields}, {@code /export} and the Markdown rendering (which renders
 * {@code FieldView} verbatim) cannot drift apart on which occurrences a human refused.
 *
 * <p>Derived from {@code (review_status, correction-present)} rather than a decision-table query:
 * {@code extracted_field.review_status} is flipped in the SAME transaction that appends each
 * decision ({@code FieldCorrectionService.apply} is {@code applyReviewStatus}'s only caller), so
 * it IS the materialized latest field decision — zero extra queries, and no window for a
 * concurrent PATCH to make {@code effectiveStatus} contradict {@code reviewStatus} within one
 * response row. The correction-present term is what keeps CORRECT-then-CONFIRM reading
 * {@link #CORRECTED}: the served value is still the human's, and this key's stated purpose is the
 * served value's provenance, not the latest action's name.
 */
public final class EffectiveStatus {

    /** The untouched machine parse — including one a human CONFIRMED (not a fourth value). */
    public static final String MACHINE = "MACHINE";

    /** The served displayed/normalized value is a human's correction; rawValue stays machine. */
    public static final String CORRECTED = "CORRECTED";

    /**
     * A named human examined the served value and REFUSED it. The value channel still carries it —
     * the review surface must show what was rejected — but a consumer must not use it: treat the
     * occurrence as missing-for-use, never as a current value and never as a defaulted zero.
     */
    public static final String REJECTED = "REJECTED";

    private EffectiveStatus() {}

    /**
     * @param reviewStatus the row's {@code review_status} ({@code ExtractedField.REVIEW_*})
     * @param corrected whether the read-time overlay is serving a human correction for the row
     */
    public static String of(String reviewStatus, boolean corrected) {
        if (ExtractedField.REVIEW_REJECTED.equals(reviewStatus)) {
            return REJECTED;
        }
        return corrected ? CORRECTED : MACHINE;
    }
}
