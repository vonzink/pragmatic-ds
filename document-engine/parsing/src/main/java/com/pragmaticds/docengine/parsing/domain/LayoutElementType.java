package com.pragmaticds.docengine.parsing.domain;

/**
 * {@code layout_element.element_type} (docs/DATA_MODEL.md 4). CHECKBOX and SIGNATURE exist in the
 * schema but have no Phase 3 detector — the worker reports them in {@code notImplemented} instead
 * of ever emitting them, so an empty result is distinguishable from "looked and found none".
 */
public enum LayoutElementType {
    PARAGRAPH,
    HEADER,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    FORM_FIELD,
    CHECKBOX,
    SIGNATURE,
    IMAGE,
    LINE
}
