package com.pragmaticds.docengine.extraction.schema;

/**
 * Mirrors the {@code extracted_field_method_check} constraint (V7, widened by V11 §1, V12 §1 and
 * V13 §2). Phase 5 implements the first three; {@code NONE} records a field that no extractor
 * could find — a missing field is a result, not an absence. {@code CHECKBOX_STATE} and {@code
 * SIGNATURE_PRESENCE} are the Spec 3 detector-backed rungs. {@code LABEL_BELOW} is Spec 4's
 * box-grid rung: the label captions a cell and the value sits on the next line inside it, which
 * is how real IRS and lender forms are laid out. {@code ROW_CELL} is Spec 5a's row-group rung:
 * one occurrence per ROW of a bounded table region, read from the column its printed header
 * names — the shape Schedule E's entity tables and both K-1s have. {@code LABEL_ABOVE} (V40) is
 * LABEL_BELOW's vertical mirror: the value sits on the line directly ABOVE its caption, the tile
 * layout a bank's online activity print-out states its balances in.
 */
public enum ExtractionMethod {
    ANCHOR_LABEL,
    TABLE_CLUSTER,
    REGEX,
    FORM_FIELD,
    OCR_LINE,
    LLM,
    AI,
    HUMAN,
    NONE,
    CHECKBOX_STATE,
    SIGNATURE_PRESENCE,
    LABEL_BELOW,
    ROW_CELL,
    LABEL_ABOVE,
    /** Computed from other captured fields of the same document (spec 2026-09-23 §4); no evidence. */
    DERIVED;

    public static ExtractionMethod fromWire(String wire) {
        return valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
