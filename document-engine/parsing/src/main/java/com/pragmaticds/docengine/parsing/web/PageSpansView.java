package com.pragmaticds.docengine.parsing.web;

import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.TextSpanRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * L1 on the wire: one window of a page's raw text spans.
 *
 * <h2>What this is, and what it deliberately is not</h2>
 *
 * <p>L1 is the RECORD — every word the parser captured, with its box, where it came from, and how
 * sure the recogniser was. It names nothing, normalises nothing and asserts nothing; a span is not a
 * value and never claims to be. That is why it carries no {@code role}, no field name, no
 * {@code confidenceComponents}: those are L3 vocabulary and presuppose a schema that named something
 * to look for. L1 has no expectations, so it also has no concept of "missing" — a filter that
 * matches nothing yields an empty array, not an absence.
 *
 * <h2>Why the values here are NOT {@code MaskableValue}</h2>
 *
 * <p>This is a decision, not an omission — do not "fix" it. Masking is defined per NAMED sensitive
 * field; L1 has no names, so there is nothing to mask against. A masker that redacted, say, every
 * nine-digit run would be an invented, untested classifier that still fails on a number split across
 * three spans. The design's answer is access control, not redaction: this resource is ADMIN-only,
 * exactly like the raw engine-result bytes, and every read is audited. A masked L1 for READONLY is a
 * separate, later piece of work that must ship WITH a testable sensitive-run detector.
 *
 * <p>Be precise about what that gate buys. It is one representation deep: the page RASTER
 * ({@code GET /v1/pages/{id}/render}) carries these same words as pixels, is reachable by READONLY
 * under the broad {@code GET /v1/**} rule, and writes no audit event. So "ADMIN-only and audited" is
 * true of this resource and is NOT true of unmasked borrower content in general — which also means
 * an access review over {@code PAGE_SPANS_ACCESSED} and {@code ENGINE_RESULT_ACCESSED} is incomplete
 * by exactly the raster reads. {@code RawContentAdminBoundaryIT} pins both halves.
 *
 * <h2>The geometry frame</h2>
 *
 * <p>{@code widthPt}/{@code heightPt}/{@code rotation} are repeated here — they already ride on
 * {@code /v1/packages/{id}/pages} — because a span box is meaningless without them. Every box is PDF
 * points, top-left origin, rotation-0, which is what lets a client turn one into a highlight.
 *
 * @param contentHash the page's duplicate-detection digest. After PARSING it is sha256 over this
 *     page's ordered span text and boxes, which is the digest OF this layer; between RENDERING and
 *     PARSING it is still sha256 of the PNG bytes. It is deliberately NOT unique — two pages that
 *     carry the same words in the same places are meant to collide, which is what
 *     {@code duplicate_of_page_id} is built on — so it is the RIGHT thing to compare across two reads
 *     of the SAME page, and the wrong thing to compare across pages. That is also why it is not the
 *     ETag by itself: the validator this endpoint issues names the page id as well. Null on a page
 *     parsed before that signal shipped, in which case no ETag is sent either.
 * @param nextCursor opaque; feed it back as {@code ?after=}. Null means this window reached the end.
 * @param returned how many spans are in {@code spans} — stated rather than left to be counted.
 * @param truncated whether more spans follow; always {@code nextCursor != null}.
 */
public record PageSpansView(
        UUID pageId,
        UUID packageId,
        int packagePageIndex,
        BigDecimal widthPt,
        BigDecimal heightPt,
        int rotation,
        String textLayer,
        String contentHash,
        List<SpanView> spans,
        String nextCursor,
        int returned,
        boolean truncated) {

    /**
     * One captured word, exactly as stored.
     *
     * @param source {@code NATIVE} (read from the text layer) or {@code OCR} (recognised)
     * @param ocrEngine the engine that produced THIS span — per span, not per page, because
     *     reconciliation may mix engines by region. Null for native spans.
     * @param confidence per-word; 1.0000 for native text, the recogniser's own score otherwise
     * @param fontSize present only for native spans — a recogniser has no font to report
     * @param fontName present only for native spans, for the same reason
     */
    public record SpanView(
            long id,
            int ordinal,
            String text,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            String source,
            String ocrEngine,
            BigDecimal confidence,
            BigDecimal fontSize,
            String fontName) {

        static SpanView of(TextSpanRow row) {
            return new SpanView(
                    row.span().getId(),
                    row.span().getOrdinal(),
                    row.span().getText(),
                    row.span().getX(),
                    row.span().getY(),
                    row.span().getWidth(),
                    row.span().getHeight(),
                    row.span().getSource().name(),
                    row.span().getOcrEngine(),
                    row.span().getConfidence(),
                    row.span().getFontSize(),
                    row.span().getFontName());
        }
    }

    static PageSpansView of(Page page, List<TextSpanRow> rows, String nextCursor) {
        return new PageSpansView(
                page.getId(),
                page.getPackageId(),
                page.getPackagePageIndex(),
                page.getWidthPt(),
                page.getHeightPt(),
                page.getRotation(),
                page.getTextLayer() == null ? null : page.getTextLayer().name(),
                page.getContentHash(),
                rows.stream().map(SpanView::of).toList(),
                nextCursor,
                rows.size(),
                nextCursor != null);
    }
}
