package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.util.List;

/**
 * A layout element projected for extraction, with its grid position already parsed out of the
 * jsonb {@code attributes} column. TABLE_CLUSTER navigates TABLE → TABLE_ROW → TABLE_CELL trees;
 * {@code row}/{@code col} are the worker's 0-based cell indices (null on non-cell nodes; TABLE_ROW
 * carries {@code row} only).
 *
 * @param spans the element's linked spans in link order (empty for TABLE and TABLE_ROW nodes —
 *     text lives on the cells)
 */
public record LayoutNode(
        java.util.UUID id,
        LayoutElementType type,
        Box box,
        Integer row,
        Integer col,
        List<SpanRef> spans,
        List<LayoutNode> children) {}
