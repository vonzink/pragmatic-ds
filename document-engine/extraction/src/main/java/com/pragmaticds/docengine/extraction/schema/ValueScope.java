package com.pragmaticds.docengine.extraction.schema;

/** Where a {@link ValueSpec} pattern searches, relative to the located label (if any). */
public enum ValueScope {
    /** Spans on the label's visual line, strictly right of the label's right edge. */
    LINE_RIGHT,
    /** The whole visual line containing the anchor (TABLE_CLUSTER cells use the cell text). */
    LINE,
    /** The whole page in reading order (REGEX method — no label). */
    PAGE;

    public static ValueScope fromWire(String wire) {
        return valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
