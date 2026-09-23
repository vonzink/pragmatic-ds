package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.platform.pii.MaskableValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Response shape of {@code GET /v1/packages/{id}/export} — the brief's downstream-consumer
 * export: every document with its extracted fields, ready to feed an LOS without another query.
 *
 * <p>Page references are 1-BASED page numbers ({@code packagePageIndex + 1}) because the export
 * is consumer-facing — "page 1" means the first page a human sees, unlike the API's internal
 * 0-based indices. Missing fields ARE included (nulls + MANUAL_REVIEW_REQUIRED): a consumer must
 * see that payDate was looked for and not found, not wonder where it went.
 */
public record PackageExportView(
        UUID packageId,
        List<ExportDocumentView> documents,
        List<ExportUnassignedPageView> unassignedPages) {

    /**
     * @param boundaryProvenance how this document's starting boundary was decided — {@code HUMAN},
     *     {@code RULE}, {@code PACKAGE_START}, {@code TYPE_CHANGE} or {@code AI}; null for
     *     documents split before the engine recorded it (V24). An LOS ingesting a split package
     *     can then treat an inferred boundary differently from a proven or human-placed one
     *     instead of trusting all four equally.
     * @param absorbedUntypedPages how many of the document's pages had no type and were absorbed
     *     as continuations by the splitter (V46, issue #60); null where the engine did not count
     *     (pre-V46 splits, human-reshaped documents). An LOS can treat a document with many
     *     absorbed pages as "probably two documents" instead of trusting its page range.
     */
    public record ExportDocumentView(
            UUID id,
            int ordinal,
            String documentTypeCode,
            BigDecimal classificationConfidence,
            String reviewStatus,
            String boundaryProvenance,
            Integer absorbedUntypedPages,
            List<Integer> pageNumbers,
            List<ExportFieldView> fields) {}

    /**
     * @param groupKey the repeating-group key printed on the form — {@code "A"}/{@code "B"}/
     *     {@code "C"} for a Schedule E property column, the row ordinal zero-padded to two digits
     *     for an entity table — or null for a field that does not repeat. <b>This component is not
     *     optional and it is not decoration.</b> An export field is identified by
     *     {@code fieldName} alone: no id, no ordinal. Emitting three occurrences under one name
     *     would recreate Spec 5a's own failure class one layer downstream — a consumer building a
     *     name-keyed map keeps exactly one of three rents and silently discards the rest, which is
     *     the confident-partial loss this spec exists to end, moved rather than fixed. A plain
     *     {@code String}, deliberately: a column letter is a coordinate, never PII, and masking it
     *     would hide the label that makes the occurrence findable.
     * @param groupKind {@code "COLUMN"} / {@code "ROW"} / {@code "NONE"} — how the form lays the
     *     occurrences out, present and never null. The export needs it for the same reason
     *     {@code /fields} does: a grouped field whose region could not be read exports ONE entry
     *     with a null key, indistinguishable from an ungrouped missing field without it. Derived
     *     from the schema the row cites — see {@link FieldGroupKinds}.
     * @param textProvenance where this field's VALUE characters came from — {@code NATIVE},
     *     {@code OCR}, {@code MIXED} or {@code UNKNOWN}, with the engine named when there is one.
     *     Present and never null. The export needs it more than {@code /fields} does, not less: an
     *     export is what a downstream system books WITHOUT a human in the loop, so it is the one
     *     surface where "this number was guessed from pixels" has to travel with the number rather
     *     than be discoverable by asking a second endpoint. Derived from the {@code text_span} rows
     *     the field's VALUE evidence cites — see {@link FieldTextProvenance}.
     * @param value the captured raw value ({@code raw_value})
     * @param normalizedValue the populated normalized arm: string for STRING/ENUM, number for
     *     NUMBER/MONEY, ISO-8601 date string for DATE; null when missing. A rental loss carries
     *     its sign here: {@code (18,470)} on the page normalizes to {@code -18470}.
     * @param boundingBox the UNION of the field's VALUE evidence boxes; null when missing. Per
     *     OCCURRENCE, so a consumer can point a reviewer at the right column.
     * @param effectiveStatus whose value this entry is serving and whether a consumer may use it —
     *     {@code "MACHINE"} | {@code "CORRECTED"} | {@code "REJECTED"}, present and never omitted
     *     (design D11, derived by {@link EffectiveStatus}, identically to {@code /fields}).
     *     {@code REJECTED} means a named human refused the served value: it stays in the payload so
     *     a review surface can show it, and this key is the machine-readable "do not use it" — the
     *     export is what a downstream system books WITHOUT a human in the loop, so the refusal has
     *     to travel with the value rather than be discoverable by asking a second endpoint.
     */
    public record ExportFieldView(
            String fieldName,
            String groupKey,
            String groupKind,
            TextProvenanceView textProvenance,
            MaskableValue value,
            MaskableValue normalizedValue,
            MaskableValue displayedText,
            Integer pageNumber,
            BoundingBoxView boundingBox,
            String extractionMethod,
            BigDecimal confidence,
            String validationStatus,
            String reviewStatus,
            String effectiveStatus) {}

    public record BoundingBoxView(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}

    /**
     * Named distinctly from classification's {@code UnassignedPageView} on purpose. springdoc keys
     * OpenAPI components by SIMPLE class name, so two same-named nested records collapse into one
     * schema and the loser's field names vanish from the spec — here the export's 1-based
     * {@code pageNumber} silently overwrote the documents endpoint's 0-based
     * {@code packagePageIndex}, so a generated client read the wrong field for
     * {@code /v1/packages/{id}/documents}. Renaming the TYPE changes no JSON: nested record names
     * are never serialised. Phase 6 finding.
     */
    public record ExportUnassignedPageView(UUID pageId, int pageNumber, String reason) {}
}
