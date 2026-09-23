package com.pragmaticds.docengine.parsing.service;

import com.pragmaticds.docengine.parsing.client.NativeSpan;
import com.pragmaticds.docengine.parsing.client.OcrSpan;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-persists text spans, VERBATIM: the BigDecimal coordinate values that arrived on the wire
 * are the values that hit the row — no doubles in between (contract invariant 1).
 *
 * <p>NATIVE spans come from {@code /v1/text} and carry confidence 1.0 by definition. OCR spans
 * come from {@code /v1/ocr} and carry a PER-SPAN engine — reconciliation may mix engines by
 * region, and evidence must name the engine that produced it.
 */
@Service
public class TextSpanService {

    private static final BigDecimal NATIVE_CONFIDENCE = BigDecimal.ONE;

    private final TextSpanRepository spans;

    public TextSpanService(TextSpanRepository spans) {
        this.spans = spans;
    }

    @Transactional
    public List<TextSpan> persistNativeSpans(Page page, List<NativeSpan> nativeSpans) {
        return spans.saveAll(
                nativeSpans.stream()
                        .map(
                                s ->
                                        new TextSpan(
                                                page.getId(),
                                                s.ordinal(),
                                                s.text(),
                                                s.x(),
                                                s.y(),
                                                s.width(),
                                                s.height(),
                                                SpanSource.NATIVE,
                                                null,
                                                NATIVE_CONFIDENCE,
                                                s.fontSize(),
                                                s.fontName()))
                        .toList());
    }

    @Transactional
    public List<TextSpan> persistOcrSpans(Page page, List<OcrSpan> ocrSpans) {
        return spans.saveAll(
                ocrSpans.stream()
                        .map(
                                s ->
                                        new TextSpan(
                                                page.getId(),
                                                s.ordinal(),
                                                s.text(),
                                                s.x(),
                                                s.y(),
                                                s.width(),
                                                s.height(),
                                                SpanSource.OCR,
                                                s.engine(),
                                                s.confidence(),
                                                null,
                                                null))
                        .toList());
    }

    /** Stage retry idempotency: clear one source's spans for the package before re-persisting. */
    @Transactional
    public void deleteBySource(UUID packageId, SpanSource source) {
        spans.deleteByPackageIdAndSource(packageId, source, TenantContext.require());
    }
}
