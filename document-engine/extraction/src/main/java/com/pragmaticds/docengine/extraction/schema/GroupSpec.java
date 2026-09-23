package com.pragmaticds.docengine.extraction.schema;

import java.util.List;

/**
 * A field's repeating-group declaration: this field occurs once per group key, each occurrence
 * carrying its own value, confidence and evidence. Null on a {@link FieldSpec} means the field
 * is single-valued — exactly the pre-Spec-5a behaviour.
 *
 * <p>The key stored on each occurrence is the value PRINTED ON THE FORM (design D2): {@code A} /
 * {@code B} / {@code C} for Schedule E's property columns, the preprinted row LETTER for entity
 * tables that letter their rows ({@link #rowLabels}), and the row's own ordinal only for tables
 * where the form prints no key at all. A synthetic index that disagreed with the printed key
 * would be a second coordinate system to reconcile.
 *
 * <p>Exactly one of the two shapes is populated, per {@link #kind}. Which components a kind uses
 * is enforced by {@code ExtractionSchemaLoader}, not here: the loader is the one door schema JSON
 * comes through, and keeping this record free of invariants keeps hand-built specs in unit tests
 * cheap — the same division {@link ExtractorSpec} already follows.
 *
 * @param header COLUMN only — the printed header that locates the key columns
 * @param keys COLUMN only — the printed keys, in page order, distinct and non-blank
 * @param region ROW only — the anchors that open and close the table
 * @param maxRows ROW only — the hard cap on occurrences. REQUIRED on every ROW group and never
 *     defaulted: an end anchor that fails to match would otherwise leave the region running to
 *     the bottom of the document, emitting hundreds of persisted occurrences.
 * @param rowLabels ROW only, OPT-IN — the row letters the form PREPRINTS in the table's
 *     left-margin gutter ({@code A}..{@code Z}, single characters, strictly ascending). When
 *     declared, each row's key is the letter found at the row's own left edge — the join key the
 *     form itself provides, which is what lets a table printed as TWO sub-tables (Schedule E
 *     Parts II/III print name rows and money rows separately, lettered twice) join name row A to
 *     money row A by the form's own labeling. Null keeps the counted-ordinal behaviour every
 *     unlabeled table (K-1s, Part IV) relies on.
 */
public record GroupSpec(
        GroupKind kind,
        LabelSpec header,
        List<String> keys,
        GroupRegionSpec region,
        Integer maxRows,
        List<String> rowLabels) {

    /** A column group: the header locates each printed key's column. */
    public static GroupSpec column(LabelSpec header, List<String> keys) {
        return new GroupSpec(GroupKind.COLUMN, header, List.copyOf(keys), null, null, null);
    }

    /** A row group: one occurrence per row of the bounded region, capped at {@code maxRows}. */
    public static GroupSpec row(GroupRegionSpec region, int maxRows) {
        return new GroupSpec(GroupKind.ROW, null, null, region, maxRows, null);
    }

    /**
     * A row group whose rows the form letters: one occurrence per PRINTED label, keyed by the
     * letter at each row's own left edge.
     */
    public static GroupSpec labeledRow(
            GroupRegionSpec region, int maxRows, List<String> rowLabels) {
        return new GroupSpec(GroupKind.ROW, null, null, region, maxRows, List.copyOf(rowLabels));
    }
}
