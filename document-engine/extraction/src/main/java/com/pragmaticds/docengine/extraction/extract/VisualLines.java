package com.pragmaticds.docengine.extraction.extract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups a page's spans into visual lines by vertical-center proximity: two spans share a line
 * when their centers are within half the SMALLER of the two heights (scan in center order, compare
 * each span to the previous one — deterministic). Lines come back ordered top-to-bottom; spans
 * within a line ordered by x. All comparisons are BigDecimal — coordinates never pass through
 * double.
 *
 * <h2>Why the SMALLER height, not the larger</h2>
 *
 * The threshold names how far a span may sit from a line and still belong to it. Taking it from
 * the LARGER of the two lets one big word reach half its own height in every direction and drag
 * unrelated small rows onto its line — the bridging that made a tall box-12a caption swallow the
 * name row beneath it, and that a real Form 1040 masthead reproduces exactly: a 24 pt form number
 * set beside a 6 pt Treasury line and a 12 pt title bridges both, the merged line is emitted left
 * to right, and the two rows are read THROUGH each other:
 *
 * <pre>
 *   Form 1040 Department U.S. Individual of the Treasury-Internal Income Revenue Tax Service Return
 * </pre>
 *
 * <p>{@code taxYear} anchors on {@code U.S. Individual Income Tax Return} and reads
 * {@code LINE_RIGHT}; spliced like that its label cannot match and a REQUIRED field goes missing
 * on every real 1040. Taking the threshold from the SMALLER span makes the reach mutual, so a
 * tall word can no longer annex small text it merely sits beside, while text of different sizes
 * on ONE printed row — a 6 pt caption beside its 10 pt value, whose centers differ by about a
 * quarter of the size gap — stays comfortably together.
 *
 * <p>This is the same rule classification's {@code VisualRows} applies to anchor matching, and
 * deliberately so: two subsystems that disagree about where a printed row ends will disagree
 * about what a document says. {@code VisualLinesTest} pins the rule directly; every case that
 * predates it used equal heights, where the two readings coincide.
 */
final class VisualLines {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private VisualLines() {}

    /**
     * The page's spans resequenced row by row: rows in the order their first span was READ, and
     * each row in its own spans' reading order. The page-global {@link SpanText} a label is
     * looked up in is built from this, for exactly the reason {@code VisualRows} exists on the
     * classification side — the parser's single page-wide sequence reads a dense masthead's two
     * rows through each other, and a label spliced apart matches nothing.
     *
     * <p>Ordering by READING position rather than by x is the half that keeps this safe: it can
     * only ever SPLIT a run the parser produced, never re-sequence one, so a page stored with
     * {@code /Rotate 180} — whose canonical boxes run opposite to the order its words were read
     * in — is left exactly as it is today.
     */
    static List<SpanRef> inRowOrder(List<SpanRef> spans) {
        if (spans.size() < 2) {
            return List.copyOf(spans);
        }
        List<SpanRef> reading = List.copyOf(spans);
        // Reading position by object IDENTITY, resolved once. Not List#indexOf inside the
        // comparator (quadratic on a thousand-span page), and not equals-based lookup either —
        // two spans of a page can be equal by value and must still keep their own places.
        Map<SpanRef, Integer> positionOf = new IdentityHashMap<>(reading.size());
        for (int i = 0; i < reading.size(); i++) {
            positionOf.putIfAbsent(reading.get(i), i);
        }
        Comparator<SpanRef> byReadingPosition = Comparator.comparingInt(positionOf::get);

        List<List<SpanRef>> rows = new ArrayList<>(group(reading));
        rows.replaceAll(
                row -> {
                    List<SpanRef> resequenced = new ArrayList<>(row);
                    resequenced.sort(byReadingPosition);
                    return resequenced;
                });
        rows.sort(Comparator.comparingInt(row -> positionOf.get(row.get(0))));

        List<SpanRef> ordered = new ArrayList<>(reading.size());
        rows.forEach(ordered::addAll);
        return List.copyOf(ordered);
    }

    static List<List<SpanRef>> group(List<SpanRef> spans) {
        List<SpanRef> byCenter = new ArrayList<>(spans);
        byCenter.sort(
                Comparator.comparing(VisualLines::center).thenComparing(span -> span.box().x()));

        List<List<SpanRef>> lines = new ArrayList<>();
        List<SpanRef> current = null;
        SpanRef previous = null;
        for (SpanRef span : byCenter) {
            if (previous == null || splits(previous, span)) {
                current = new ArrayList<>();
                lines.add(current);
            }
            current.add(span);
            previous = span;
        }

        List<List<SpanRef>> ordered = new ArrayList<>();
        for (List<SpanRef> line : lines) {
            line.sort(Comparator.comparing(span -> span.box().x()));
            ordered.add(List.copyOf(line));
        }
        return List.copyOf(ordered);
    }

    private static boolean splits(SpanRef above, SpanRef below) {
        BigDecimal gap = center(below).subtract(center(above)).abs();
        BigDecimal threshold =
                above.box().height().min(below.box().height()).multiply(HALF);
        return gap.compareTo(threshold) > 0;
    }

    private static BigDecimal center(SpanRef span) {
        return span.box().y().add(span.box().height().multiply(HALF));
    }
}
