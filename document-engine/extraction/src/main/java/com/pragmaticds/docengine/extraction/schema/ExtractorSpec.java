package com.pragmaticds.docengine.extraction.schema;

import java.util.List;

/**
 * One rung of a field's extractor ladder.
 *
 * @param strength the anchor-strength confidence component this extractor contributes when it
 *     succeeds — data, not code, so the confidence formula stays auditable and tunable
 * @param label the label anchor (ANCHOR_LABEL, LABEL_BELOW and LABEL_ABOVE only, null otherwise)
 * @param table the grid address (TABLE_CLUSTER only, null otherwise)
 * @param value how to capture the value text once the anchor/cell is located — null for the two
 *     detector rungs, whose value comes from a detection, not a text pattern
 * @param options label→enum mapping (CHECKBOX_STATE only, null otherwise)
 * @param proximityPt center-to-label-edge distance cap in canonical points (CHECKBOX_STATE only,
 *     null otherwise)
 * @param region the signature search region (SIGNATURE_PRESENCE only, null otherwise)
 * @param maxDropPt how far BELOW the label's bottom edge a candidate line's top may lie, in
 *     canonical points (LABEL_BELOW only, null otherwise). The loader defaults it to 24.0 when
 *     the rung omits it: on a real IRS W-2 a caption's own value row sits about 6 pt below its
 *     bottom edge and the NEXT row's value about 30 pt below it, so 24 admits the right cell
 *     and excludes the decoy.
 * @param cellOverlap the fraction of the NARROWER of the two boxes — the label's (LABEL_BELOW)
 *     or the column header's (ROW_CELL) and a candidate span's — their horizontal extents must
 *     share for the span to count as being in the same cell. Null for every other method. The
 *     loader defaults it to 0.5 for both rungs, deliberately the same number: they must not
 *     drift apart about what "the same column" means.
 * @param columnHeader the printed column header that locates a row group's cell (ROW_CELL only,
 *     null otherwise). Not the same thing as {@link TableSpec#columnHeader()}, which addresses a
 *     DETECTED grid: ROW_CELL reads geometry, never a layout tree.
 * @param maxRisePt how far ABOVE the label's top edge a candidate span's bottom may lie, in
 *     canonical points (LABEL_ABOVE only, null otherwise) — {@code maxDropPt}'s mirror, and a
 *     separate component rather than that one re-read upside down, because a schema author
 *     writing "drop" for a rung that reads UP would be misled by the name. The loader defaults
 *     it to 24.0 when the rung omits it: on the real online print-out a balance's bottom edge
 *     sits 3.7 pt above its caption's top edge, and the horizontal cell — not this reach — is
 *     what keeps the neighbouring tile's amount out.
 * @param joinCells the captions of the ADJACENT cells on the label's own caption row whose first
 *     value line is read TOGETHER with the label's cell as one value, in left-to-right order
 *     (LABEL_BELOW only, null otherwise). Measured on real IRS forms (2026-09-14): a Form W-2
 *     prints box e as "Employee's first name and initial" | "Last name" | "Suff." and a Form
 *     1040 its identity rows as "Your first name and middle initial" | "Last name", the
 *     person's name split across the cells — half a name in one cell, the other half under the
 *     next caption. Each join caption is looked for ONLY on the label's own row, to its right,
 *     so a spouse row's "Last name" never borrows the taxpayer row's, and an empty joined cell
 *     contributes nothing: the value pattern still has to match the composed text whole, which
 *     is what keeps a first-name-only cell from becoming a confident partial (design D5).
 */
public record ExtractorSpec(
        ExtractionMethod method,
        double strength,
        LabelSpec label,
        TableSpec table,
        ValueSpec value,
        List<CheckboxOption> options,
        Double proximityPt,
        RegionSpec region,
        Double maxDropPt,
        Double cellOverlap,
        LabelSpec columnHeader,
        Double maxRisePt,
        List<LabelSpec> joinCells) {

    /**
     * The pre-join shape: every rung that predates {@code joinCells} constructs through this, so
     * the detector, table, tile and row rungs — and every test that builds them positionally —
     * are untouched by the LABEL_BELOW-only join dimension.
     */
    public ExtractorSpec(
            ExtractionMethod method,
            double strength,
            LabelSpec label,
            TableSpec table,
            ValueSpec value,
            List<CheckboxOption> options,
            Double proximityPt,
            RegionSpec region,
            Double maxDropPt,
            Double cellOverlap,
            LabelSpec columnHeader,
            Double maxRisePt) {
        this(
                method,
                strength,
                label,
                table,
                value,
                options,
                proximityPt,
                region,
                maxDropPt,
                cellOverlap,
                columnHeader,
                maxRisePt,
                null);
    }

    /** The Spec 5a shape — ROW_CELL's column header and everything before it. */
    public ExtractorSpec(
            ExtractionMethod method,
            double strength,
            LabelSpec label,
            TableSpec table,
            ValueSpec value,
            List<CheckboxOption> options,
            Double proximityPt,
            RegionSpec region,
            Double maxDropPt,
            Double cellOverlap,
            LabelSpec columnHeader) {
        this(
                method,
                strength,
                label,
                table,
                value,
                options,
                proximityPt,
                region,
                maxDropPt,
                cellOverlap,
                columnHeader,
                null);
    }

    /** The Spec 4 shape — LABEL_BELOW and everything before it. */
    public ExtractorSpec(
            ExtractionMethod method,
            double strength,
            LabelSpec label,
            TableSpec table,
            ValueSpec value,
            List<CheckboxOption> options,
            Double proximityPt,
            RegionSpec region,
            Double maxDropPt,
            Double cellOverlap) {
        this(
                method,
                strength,
                label,
                table,
                value,
                options,
                proximityPt,
                region,
                maxDropPt,
                cellOverlap,
                null);
    }

    /** The Spec 3 shape — the two detector rungs and everything before them. */
    public ExtractorSpec(
            ExtractionMethod method,
            double strength,
            LabelSpec label,
            TableSpec table,
            ValueSpec value,
            List<CheckboxOption> options,
            Double proximityPt,
            RegionSpec region) {
        this(method, strength, label, table, value, options, proximityPt, region, null, null);
    }

    /** The pre-Spec-3 shape — every ANCHOR_LABEL / TABLE_CLUSTER / REGEX rung builds this. */
    public ExtractorSpec(
            ExtractionMethod method,
            double strength,
            LabelSpec label,
            TableSpec table,
            ValueSpec value) {
        this(method, strength, label, table, value, null, null, null, null, null);
    }
}
