package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.TextSpan;

/**
 * One row of an L1 window: the span, plus the ordinal THIS window sorted it by.
 *
 * <p>The second component is not decoration. A page window sorts by the span's own
 * {@code text_span.ordinal}; an element window sorts by {@code layout_element_span.ordinal}, which
 * belongs to the LINK, not to the span — the same span may sit at a different position inside a
 * different element. Carrying the sort ordinal beside the row is what lets a cursor be issued
 * without a second query, and what makes it impossible to resume an element window from a span
 * ordinal by accident.
 *
 * @param span the row itself, exactly as stored
 * @param sortOrdinal the ordinal this window ordered by — {@code text_span.ordinal} for a page
 *     window, {@code layout_element_span.ordinal} for an element window
 */
public record TextSpanRow(TextSpan span, int sortOrdinal) {}
