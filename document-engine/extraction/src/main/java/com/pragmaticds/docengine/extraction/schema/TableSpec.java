package com.pragmaticds.docengine.extraction.schema;

/**
 * A grid address for TABLE_CLUSTER: the cell where the row whose first cell matches
 * {@code rowLabel} crosses the column whose header cell matches {@code columnHeader}.
 */
public record TableSpec(LabelSpec rowLabel, LabelSpec columnHeader) {}
