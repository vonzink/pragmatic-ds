package com.pragmaticds.docengine.extraction.schema;

/**
 * The bounded region a ROW group walks: the anchor that opens the table and the anchor that
 * closes it (e.g. "Income or Loss From Partnerships" … "Total partnership and S corporation").
 *
 * <p>Deliberately NOT {@link RegionSpec}, which is SIGNATURE_PRESENCE's label-plus-window search
 * box. Same English word, different idea — a start/end anchor pair is not a window, and merging
 * them would give one type two meanings.
 */
public record GroupRegionSpec(LabelSpec start, LabelSpec end) {}
