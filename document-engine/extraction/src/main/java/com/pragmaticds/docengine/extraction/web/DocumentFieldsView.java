package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.platform.pii.MaskableValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Response shape of {@code GET /v1/documents/{id}/fields}: a logical document's current
 * extracted fields with their full evidence chains — the projection review renders boxes from.
 * Entries are ordered by field name, then by GROUP KEY with an unkeyed occurrence first; evidence
 * is VALUE before LABEL, each in ordinal order. A field that repeats within one document (Spec 5a)
 * returns one entry per occurrence, each with its own key, its own value and its own evidence.
 */
public record DocumentFieldsView(
        UUID documentId, String documentTypeCode, String schemaVersion, List<FieldView> fields) {

    /**
     * @param groupKey the repeating-group key PRINTED ON THE FORM — {@code "A"}/{@code "B"}/
     *     {@code "C"} for a Schedule E property column, the row ordinal zero-padded to two digits
     *     for an entity table — or null for a field that does not repeat, which is every field
     *     before Spec 5a. Present-and-null rather than omitted: a consumer reads an explicit
     *     "this field does not repeat" instead of inferring it from a missing key. Deliberately a
     *     plain {@code String} and not a {@link MaskableValue}: a column letter is a coordinate,
     *     never PII, and masking it would hide the very label that makes an occurrence findable.
     * @param groupKind how the field's occurrences are laid out on the FORM — {@code "COLUMN"},
     *     {@code "ROW"}, or {@code "NONE"} for a field the schema does not group. Present and
     *     never null, so a consumer never has to guess. It is here because {@code groupKey} alone
     *     cannot answer "does this field repeat?": a ROW-grouped field whose table region could
     *     not be located persists ONE occurrence with a NULL key, which on the wire is otherwise
     *     byte-identical to an ungrouped missing field. Derived from the schema the row cites, so
     *     it reports what the PRODUCING schema declared. See {@link FieldGroupKinds}.
     * @param textProvenance where this occurrence's VALUE characters came from — a text layer, OCR,
     *     both, or nothing. Present and never null, like {@code groupKind} and for the same reason:
     *     an omitted key is an ambiguity, and an OCR'd value that reads exactly like a native one is
     *     precisely the ambiguity worth ending. Derived at read time from the {@code text_span}
     *     rows this occurrence's VALUE evidence cites — see {@link FieldTextProvenance}. It sits
     *     BESIDE {@code confidence}, never inside it: confidence stays the three-component product.
     * @param effectiveStatus whose value {@code displayedText}/{@code normalized} are serving and
     *     whether a consumer may use it — {@code "MACHINE"} | {@code "CORRECTED"} |
     *     {@code "REJECTED"}, present and never omitted (design D11, derived by
     *     {@link EffectiveStatus}). {@code REJECTED} means a named human refused the served value:
     *     it stays on the wire so review can show what was refused, and this key is the
     *     machine-readable "do not use it". {@code reviewStatus} beside it is the row's raw review
     *     state and is NOT a substitute — a CONFIRMED row serves a machine value ({@code MACHINE})
     *     unless an earlier correction is what is being served ({@code CORRECTED}).
     */
    public record FieldView(
            java.util.UUID id,
            String fieldName,
            String groupKey,
            String groupKind,
            TextProvenanceView textProvenance,
            String dataType,
            MaskableValue displayedText,
            MaskableValue rawValue,
            NormalizedView normalized,
            String extractionMethod,
            String extractorVersion,
            BigDecimal confidence,
            ConfidenceComponentsView confidenceComponents,
            String validationStatus,
            String reviewStatus,
            String effectiveStatus,
            boolean sensitive,
            List<EvidenceView> evidence) {}

    /**
     * Exactly one arm populated per data type; all null for a missing field. All three arms are
     * {@link MaskableValue}: a sensitive MONEY/DATE field's normalized number/date is PII too, so it
     * must mask on the wire like the text arm. {@code MaskableValue} holds an {@code Object}, so a
     * non-sensitive number stays a JSON number and a non-sensitive date its ISO string — the wire
     * shape is unchanged for every field that is not sensitive.
     */
    public record NormalizedView(MaskableValue text, MaskableValue number, MaskableValue date) {}

    /** The confidence formula's inputs — auditable, not a bare number. Null when missing. */
    public record ConfidenceComponentsView(
            BigDecimal spanConfidence, BigDecimal anchorStrength, BigDecimal normalizerCertainty) {}

    public record EvidenceView(
            String role,
            int ordinal,
            UUID pageId,
            int packagePageIndex,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            Long textSpanId,
            UUID layoutElementId) {}
}
