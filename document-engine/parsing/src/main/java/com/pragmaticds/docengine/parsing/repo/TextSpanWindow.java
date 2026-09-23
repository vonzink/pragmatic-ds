package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.SpanSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;

/**
 * One page of L1, described as a value: which page, which filters, where the last read stopped, and
 * how many rows to take. Immutable — every derivation returns a new window, so a caller can hold
 * "this request" and derive "the next request" without either mutating the other.
 *
 * <h2>Why this type exists at all</h2>
 *
 * <p>A span window has five independent optional filters and TWO different sort orders, and the two
 * orders are not interchangeable. {@link Mode#PAGE} walks {@code (source, ordinal, id)} — the page's
 * own reading order. {@link Mode#ELEMENT} walks {@code (layout_element_span.ordinal, id)} — one
 * element's own member order, which is a property of the LINK and need not agree with page order.
 * Pairing the wrong cursor with the wrong query is the one bug a paginated endpoint cannot survive,
 * so the pairing is made here, once, and {@link #fetch} is the only way to run either.
 *
 * <h2>Totality, which is the whole point of the cursor</h2>
 *
 * <p>{@code (page_id, ordinal)} is deliberately NOT unique: a MIXED page carries a NATIVE block and
 * an OCR block that each restart at ordinal 0 (see {@link TextSpanRepository}). So ordinal alone
 * cannot be a cursor — resuming "after ordinal 4" on such a page either re-serves the OCR block's
 * first five rows or skips them. {@code (source, ordinal, id)} is total, because {@code id} is the
 * table's identity key. {@code (linkOrdinal, id)} is likewise total within one element, because a
 * span appears in an element at most once (composite primary key).
 *
 * <h2>No default is a sentinel that could lie</h2>
 *
 * <p>{@link #UNBOUNDED_MIN}/{@link #UNBOUNDED_MAX} sit outside the {@code numeric(10,2)} domain of
 * every box column, so an unfiltered window cannot silently drop a span whose stored box falls
 * outside the page frame — which is the shape of the open sideways-scan defect, and precisely the
 * row an operator would be hunting for.
 */
public final class TextSpanWindow {

    /** Beyond the {@code numeric(10,2)} range of {@code text_span.x/y/width/height} (V4). */
    private static final BigDecimal UNBOUNDED_MIN = new BigDecimal("-1000000000");

    private static final BigDecimal UNBOUNDED_MAX = new BigDecimal("1000000000");

    /** Every span source; the unfiltered {@code source} predicate. Never empty. */
    private static final Set<SpanSource> ALL_SOURCES = Set.of(SpanSource.values());

    /** Ordinals are non-negative and ids positive, so this seed is strictly before every row. */
    private static final int BEFORE_FIRST_ORDINAL = -1;

    private static final long BEFORE_FIRST_ID = -1L;

    /** The repo's own default page size for L1 (design D6). */
    public static final int DEFAULT_LIMIT = 1000;

    /** The repo's own ceiling for L1 (design D6). */
    public static final int MAX_LIMIT = 5000;

    /** Which of the two total orders this window walks. */
    public enum Mode {
        /** The page's reading order: {@code (source, ordinal, id)}. */
        PAGE,
        /** One element's member order: {@code (layout_element_span.ordinal, id)}. */
        ELEMENT
    }

    private final UUID orgId;
    private final UUID pageId;
    private final UUID elementId;
    private final Set<SpanSource> sources;
    private final BigDecimal minConfidence;
    private final BigDecimal boxLeft;
    private final BigDecimal boxTop;
    private final BigDecimal boxRight;
    private final BigDecimal boxBottom;
    private final SpanSource cursorSource;
    private final int cursorOrdinal;
    private final long cursorId;
    private final int limit;

    private TextSpanWindow(
            UUID orgId,
            UUID pageId,
            UUID elementId,
            Set<SpanSource> sources,
            BigDecimal minConfidence,
            BigDecimal boxLeft,
            BigDecimal boxTop,
            BigDecimal boxRight,
            BigDecimal boxBottom,
            SpanSource cursorSource,
            int cursorOrdinal,
            long cursorId,
            int limit) {
        this.orgId = orgId;
        this.pageId = pageId;
        this.elementId = elementId;
        this.sources = sources;
        this.minConfidence = minConfidence;
        this.boxLeft = boxLeft;
        this.boxTop = boxTop;
        this.boxRight = boxRight;
        this.boxBottom = boxBottom;
        this.cursorSource = cursorSource;
        this.cursorOrdinal = cursorOrdinal;
        this.cursorId = cursorId;
        this.limit = limit;
    }

    /** An unfiltered, uncursored window — name the page next. */
    public static TextSpanWindow forOrg(UUID orgId) {
        return new TextSpanWindow(
                orgId,
                null,
                null,
                ALL_SOURCES,
                BigDecimal.ZERO,
                UNBOUNDED_MIN,
                UNBOUNDED_MIN,
                UNBOUNDED_MAX,
                UNBOUNDED_MAX,
                SpanSource.NATIVE,
                BEFORE_FIRST_ORDINAL,
                BEFORE_FIRST_ID,
                DEFAULT_LIMIT);
    }

    public TextSpanWindow page(UUID pageId) {
        return copy(pageId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight, boxBottom,
                cursorSource, cursorOrdinal, cursorId, limit);
    }

    public TextSpanWindow limit(int limit) {
        return copy(pageId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight, boxBottom,
                cursorSource, cursorOrdinal, cursorId, limit);
    }

    public TextSpanWindow source(SpanSource source) {
        return copy(pageId, elementId, Set.of(source), minConfidence, boxLeft, boxTop, boxRight,
                boxBottom, cursorSource, cursorOrdinal, cursorId, limit);
    }

    public TextSpanWindow minConfidence(BigDecimal minConfidence) {
        return copy(pageId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight, boxBottom,
                cursorSource, cursorOrdinal, cursorId, limit);
    }

    /** Spans whose box INTERSECTS this window — the "give me the earnings region" query. */
    public TextSpanWindow box(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
        return copy(pageId, elementId, sources, minConfidence, x, y, x.add(width), y.add(height),
                cursorSource, cursorOrdinal, cursorId, limit);
    }

    /** Switches to {@link Mode#ELEMENT}: this element's member spans, in the element's own order. */
    public TextSpanWindow element(UUID elementId) {
        return copy(pageId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight, boxBottom,
                cursorSource, cursorOrdinal, cursorId, limit);
    }

    /**
     * Resume strictly after this row, in whichever order this window walks. The row carries the
     * ordinal it was SORTED by, which is why this cannot resume an element window from a span
     * ordinal by accident.
     */
    public TextSpanWindow after(TextSpanRow row) {
        return mode() == Mode.ELEMENT
                ? afterElementMember(row.sortOrdinal(), row.span().getId())
                : afterPagePosition(row.span().getSource(), row.sortOrdinal(), row.span().getId());
    }

    /** Resume strictly after this position in the page's own order. */
    public TextSpanWindow afterPagePosition(SpanSource source, int ordinal, long spanId) {
        return copy(pageId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight, boxBottom,
                source, ordinal, spanId, limit);
    }

    /** Resume strictly after this position in one element's member order. */
    public TextSpanWindow afterElementMember(int linkOrdinal, long spanId) {
        return copy(pageId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight, boxBottom,
                cursorSource, linkOrdinal, spanId, limit);
    }

    public Mode mode() {
        return elementId == null ? Mode.PAGE : Mode.ELEMENT;
    }

    public UUID pageId() {
        return pageId;
    }

    public UUID elementId() {
        return elementId;
    }

    /**
     * Runs this window. The single entry point, so no call site can pair one mode's cursor with the
     * other mode's ordering.
     */
    public List<TextSpanRow> fetch(TextSpanRepository repository) {
        PageRequest page = PageRequest.ofSize(limit);
        if (mode() == Mode.ELEMENT) {
            return repository.findElementSpansAfter(
                    pageId, orgId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight,
                    boxBottom, cursorOrdinal, cursorId, page);
        }
        // The OCR block sits wholly after the NATIVE block, so a cursor parked anywhere in NATIVE
        // must still admit every OCR row — that arm, not an enum inequality, is how the source
        // half of the total order is expressed.
        return repository.findPageSpansAfter(
                pageId, orgId, sources, minConfidence, boxLeft, boxTop, boxRight, boxBottom,
                cursorSource, cursorOrdinal, cursorId, cursorSource == SpanSource.NATIVE, page);
    }

    private TextSpanWindow copy(
            UUID pageId,
            UUID elementId,
            Set<SpanSource> sources,
            BigDecimal minConfidence,
            BigDecimal boxLeft,
            BigDecimal boxTop,
            BigDecimal boxRight,
            BigDecimal boxBottom,
            SpanSource cursorSource,
            int cursorOrdinal,
            long cursorId,
            int limit) {
        return new TextSpanWindow(
                orgId, pageId, elementId, sources, minConfidence, boxLeft, boxTop, boxRight,
                boxBottom, cursorSource, cursorOrdinal, cursorId, limit);
    }
}
