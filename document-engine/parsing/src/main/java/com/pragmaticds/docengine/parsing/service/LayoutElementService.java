package com.pragmaticds.docengine.parsing.service;

import com.pragmaticds.docengine.parsing.client.LayoutWireElement;
import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import com.pragmaticds.docengine.parsing.domain.LayoutElementSpan;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.LayoutElementRepository;
import com.pragmaticds.docengine.parsing.repo.LayoutElementSpanRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one page's worth of {@code /v1/layout} elements in one shot: the wire's REQUEST-SCOPED
 * {@code elementId}/{@code parentElementId} strings become row-UUID parentage (in any arrival
 * order), and {@code spanOrdinals} resolve to real {@code text_span} ids as indexes into the
 * request span list — the ordinal is the CALLER's identifier on the layout wire (contract), and
 * {@link #requestSpansFor} defines it deterministically: NATIVE spans by their Phase 2 per-page
 * ordinal, then OCR spans by theirs. On single-source pages that index EQUALS the span's own
 * {@code (page, ordinal)}; on MIXED pages — where both sources restart at ordinal 0 and a plain
 * {@code (page, ordinal)} lookup is ambiguous — it is what keeps the identifier unique.
 *
 * <p>Retry idempotency follows the text-span pattern: the PARSING stage calls
 * {@link #deleteAllForPackage} once before re-persisting, links before elements (FK order).
 */
@Service
public class LayoutElementService {

    private final LayoutElementRepository elements;
    private final LayoutElementSpanRepository elementSpans;
    private final TextSpanRepository textSpans;

    public LayoutElementService(
            LayoutElementRepository elements,
            LayoutElementSpanRepository elementSpans,
            TextSpanRepository textSpans) {
        this.elements = elements;
        this.elementSpans = elementSpans;
        this.textSpans = textSpans;
    }

    /**
     * The deterministic layout-request span list for a page: NATIVE spans by ordinal, then OCR
     * spans by ordinal. The REQUEST ordinal every {@code /v1/layout} spanOrdinal refers to is the
     * position in this list — build the request from it and pass the same list back to
     * {@link #persistPageElements}.
     */
    @Transactional(readOnly = true)
    public List<TextSpan> requestSpansFor(UUID pageId) {
        return textSpans.findByPageIdOrderByOrdinal(pageId).stream()
                .sorted(
                        Comparator.comparingInt(
                                        (TextSpan s) -> s.getSource() == SpanSource.OCR ? 1 : 0)
                                .thenComparingInt(TextSpan::getOrdinal))
                .toList();
    }

    @Transactional
    public List<LayoutElement> persistPageElements(
            Page page, List<LayoutWireElement> wire, List<TextSpan> requestSpans) {
        Map<Integer, TextSpan> spansByOrdinal = new HashMap<>();
        for (int i = 0; i < requestSpans.size(); i++) {
            spansByOrdinal.put(i, requestSpans.get(i));
        }

        // Parents must be rows before children can reference them (self-FK), but the wire makes
        // no ordering promise — so drain a queue, persisting an element once its parent is known.
        Map<String, UUID> idsByWireId = new HashMap<>();
        List<LayoutElement> persisted = new ArrayList<>(wire.size());
        Deque<LayoutWireElement> pending = new ArrayDeque<>(wire);
        int stuck = 0;
        while (!pending.isEmpty()) {
            LayoutWireElement candidate = pending.removeFirst();
            String parentWireId = candidate.parentElementId();
            if (parentWireId != null && !idsByWireId.containsKey(parentWireId)) {
                pending.addLast(candidate);
                if (++stuck > pending.size()) {
                    // A full rotation without progress: dangling or cyclic parent reference.
                    // Wire ids only — never document content — in the error.
                    throw new IllegalStateException(
                            "unresolvable layout parent reference: " + parentWireId);
                }
                continue;
            }
            stuck = 0;
            persisted.add(persistElement(page, candidate, idsByWireId, spansByOrdinal));
        }
        return persisted;
    }

    private LayoutElement persistElement(
            Page page,
            LayoutWireElement wire,
            Map<String, UUID> idsByWireId,
            Map<Integer, TextSpan> spansByOrdinal) {
        List<TextSpan> linked = new ArrayList<>(wire.spanOrdinals().size());
        for (Integer spanOrdinal : wire.spanOrdinals()) {
            TextSpan span = spansByOrdinal.get(spanOrdinal);
            if (span == null) {
                throw new IllegalStateException(
                        "layout span ordinal without text_span row: " + spanOrdinal);
            }
            linked.add(span);
        }
        String text =
                linked.isEmpty()
                        ? null
                        : linked.stream().map(TextSpan::getText).collect(Collectors.joining(" "));

        LayoutElement element =
                elements.save(
                        new LayoutElement(
                                page.getId(),
                                wire.parentElementId() == null
                                        ? null
                                        : idsByWireId.get(wire.parentElementId()),
                                LayoutElementType.valueOf(wire.elementType()),
                                wire.ordinal(),
                                wire.x(),
                                wire.y(),
                                wire.width(),
                                wire.height(),
                                text,
                                wire.confidence(),
                                wire.detector(),
                                wire.detectorVersion(),
                                wire.attributesJson()));
        if (wire.elementId() != null) {
            idsByWireId.put(wire.elementId(), element.getId());
        }
        for (int linkOrdinal = 0; linkOrdinal < linked.size(); linkOrdinal++) {
            elementSpans.save(
                    new LayoutElementSpan(
                            element.getId(), linked.get(linkOrdinal).getId(), linkOrdinal));
        }
        return element;
    }

    /** PARSING retry idempotency: links first (FK), then elements — the text-span pattern. */
    @Transactional
    public void deleteAllForPackage(UUID packageId) {
        UUID orgId = TenantContext.require();
        elementSpans.deleteByPackageId(packageId, orgId);
        elements.deleteByPackageId(packageId, orgId);
    }
}
