package com.pragmaticds.docengine.extraction.schema;

/**
 * How a repeating field's occurrences are LOCATED on the page (design D3). Two geometries, one
 * concept: they share the group-key mechanism and the evidence contract, so the model stays one
 * idea with two locators.
 *
 * <p>{@link #COLUMN} — each key is located by its printed column header and the value is the one
 * whose x-range falls under it, on the label's own line. Schedule E Part I's properties A/B/C.
 *
 * <p>{@link #ROW} — the rows of a bounded table region are walked, one occurrence per row, and
 * the key is the row's ordinal. Schedule E Parts II-IV's entity tables.
 */
public enum GroupKind {
    COLUMN,
    ROW;

    /** Schema jsonb may spell the kind in either case, like every other wire enum here. */
    public static GroupKind fromWire(String wire) {
        return valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
