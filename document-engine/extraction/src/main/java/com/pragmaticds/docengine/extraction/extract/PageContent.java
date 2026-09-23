package com.pragmaticds.docengine.extraction.extract;

import java.util.List;
import java.util.UUID;

/**
 * One page of a logical document, projected for extraction.
 *
 * @param spans ALL of the page's spans in reading order — NATIVE block then OCR block, each by
 *     ordinal (the {@code findByPageIdOrderBySourceAscOrdinalAsc} convention)
 * @param tables the page's TABLE-rooted layout trees in reading order (empty when the worker
 *     found no grid — the case the ANCHOR_LABEL fallback rungs exist for)
 * @param detections the page's CHECKBOX/SIGNATURE elements in element order (empty when the
 *     worker detected none, or has not looked — the detector rungs then fail and the ladder
 *     moves on)
 */
public record PageContent(
        UUID pageId,
        int packagePageIndex,
        List<SpanRef> spans,
        List<LayoutNode> tables,
        List<DetectionRef> detections) {

    /** Detection-less pages — the shape every pre-Spec-3 caller builds. */
    public PageContent(
            UUID pageId, int packagePageIndex, List<SpanRef> spans, List<LayoutNode> tables) {
        this(pageId, packagePageIndex, spans, tables, List.of());
    }
}
