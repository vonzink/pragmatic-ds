package com.pragmaticds.docengine.classification.match;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Re-groups one page's spans into the ROWS the page actually prints, so a multi-word anchor is
 * matched against a caption rather than against two captions read through each other.
 *
 * <h2>The defect this exists for</h2>
 *
 * The parser hands classification ONE page-wide sequence. It builds that sequence by growing a
 * running vertical envelope over the page's words and emitting each band left to right — which is
 * a sound total order, and the order every persisted {@code ordinal} and every extraction offset
 * is built on, so it is not something classification may change.
 *
 * <p>It is not, however, safe to match a PHRASE against. A real Form 1040 masthead sets the form
 * number {@code 1040} 24 pt tall beside two 6 pt and 12 pt boilerplate rows that share the same
 * left margin. One tall word inflates the envelope to cover both rows, and reading the merged
 * band left to right splices them together:
 *
 * <pre>
 *   Department U.S. Individual of the Treasury-Internal Income Revenue Tax Service Return
 * </pre>
 *
 * <p>Neither {@code U.S. Individual Income Tax Return} (weight 5) nor {@code Department of the
 * Treasury—Internal Revenue Service} (weight 2) survives that, so a real filled 1040 page 1
 * scored 3 of 10 and classified UNKNOWN while every anchor it needed was printed right there.
 * Both anchors are exactly what the IRS prints — the pack is right and the page is right; only
 * the order they were compared in was wrong.
 *
 * <h2>Geometry decides where a row ENDS; the parser decides what comes FIRST</h2>
 *
 * Two halves, and mixing them up is how this becomes a different defect:
 *
 * <ul>
 *   <li><b>Row boundaries come from geometry.</b> Two spans share a row while their vertical
 *       CENTRES lie within half the height of the SMALLER of them.
 *   <li><b>Order inside a row, and between rows, comes from the parser.</b> A row is emitted in
 *       its spans' own reading order and rows are emitted in the order their first span was read.
 *       This seam can therefore only ever SPLIT a run the parser produced — never re-sequence
 *       one. That is what keeps it safe on a page stored with {@code /Rotate 180}, whose
 *       canonical boxes run opposite to the order the words were read in: ordering rows by x
 *       there would silently reverse every caption on the page. Measured: over the 37 committed
 *       fixture PDFs this rewrites the joined text of two pages (both rotated, in the table
 *       region) and changes NO anchor outcome for any of the nine packs.
 * </ul>
 *
 * <h2>Why the threshold is the SMALLER height, and not the larger</h2>
 *
 * Extraction's {@code VisualLines} groups by centre proximity against half the LARGER of the two
 * heights. That rule does not hold here, and the difference is not cosmetic: against the real
 * masthead it separates the two rows only because a 2.3 pt-tall glyph — one letter of the
 * sideways word "Form" the IRS prints down the left edge — happens to interrupt the chain between
 * them. Delete that one glyph, or let an OCR pass merge it into its neighbour, and both anchors
 * break again with nothing in the page to explain why. Taking the threshold from the SMALLER span
 * makes a tall word unable to reach across small text at all, so the rows separate on the
 * geometry that carries the meaning — a 6 pt row and a 12 pt row whose centres are 8.6 pt apart.
 * {@code AnchorMatcherTest#the_masthead_still_reads_correctly_without_the_sideways_form_label} is
 * that difference, written down.
 *
 * <p>The same tall-span bridging is what made extraction group a name row into its own caption's
 * line, and the fix there was likewise to regroup locally rather than trust a page-wide line set.
 *
 * <h2>What this is NOT</h2>
 *
 * Not tolerance. Every span survives exactly once, separators are unchanged, and the matcher
 * still demands the anchor's characters CONTIGUOUSLY. A page that merely mentions a form still
 * cannot qualify as one, which is the invariant every rule pack's weights are calibrated against.
 *
 * <p>All comparisons are BigDecimal and no coordinate is ever divided: the rule {@code gap >
 * min(height) / 2} is evaluated as {@code |2·centre_a - 2·centre_b| > min(height)} with {@code
 * 2·centre = 2y + height}, which is exact addition only.
 */
final class VisualRows {

    private static final BigDecimal TWO = new BigDecimal("2");

    private VisualRows() {}

    /**
     * @param spansInReadingOrder the page's spans ordered by {@code ordinal}
     * @return the same spans, every one exactly once, regrouped row by row
     */
    static List<AnchorSpan> inRowOrder(List<AnchorSpan> spansInReadingOrder) {
        if (spansInReadingOrder.size() < 2) {
            return List.copyOf(spansInReadingOrder);
        }

        // Reading position is the span's index here — the caller's contract is that the list is
        // already in ordinal order, so no ordinal has to be carried on AnchorSpan itself.
        List<Placed> placed = new ArrayList<>(spansInReadingOrder.size());
        for (int ordinal = 0; ordinal < spansInReadingOrder.size(); ordinal++) {
            placed.add(new Placed(spansInReadingOrder.get(ordinal), ordinal));
        }

        // Scan in centre order, comparing each span to the previous one. Ties break on x then on
        // reading position so the grouping is a pure function of the page, never of hash order.
        placed.sort(
                Comparator.comparing((Placed p) -> doubleCentre(p.span()))
                        .thenComparing(p -> p.span().x())
                        .thenComparingInt(Placed::ordinal));

        List<List<Placed>> rows = new ArrayList<>();
        List<Placed> current = null;
        Placed previous = null;
        for (Placed p : placed) {
            if (previous == null || splits(previous.span(), p.span())) {
                current = new ArrayList<>();
                rows.add(current);
            }
            current.add(p);
            previous = p;
        }

        // Reading order restored WITHIN each row, and rows emitted in the order their first span
        // was read — so the output is the input resequenced only where geometry says a row ended.
        for (List<Placed> row : rows) {
            row.sort(Comparator.comparingInt(Placed::ordinal));
        }
        rows.sort(Comparator.comparingInt(row -> row.get(0).ordinal()));

        List<AnchorSpan> ordered = new ArrayList<>(spansInReadingOrder.size());
        for (List<Placed> row : rows) {
            for (Placed p : row) {
                ordered.add(p.span());
            }
        }
        return List.copyOf(ordered);
    }

    /** Twice the span's vertical centre: {@code 2y + height}. Exact — no division. */
    private static BigDecimal doubleCentre(AnchorSpan span) {
        return span.y().multiply(TWO).add(span.height());
    }

    /**
     * True when the two spans belong to different printed rows: their centres are more than half
     * the SMALLER span's height apart. Compared at twice scale so no coordinate is divided.
     */
    private static boolean splits(AnchorSpan above, AnchorSpan below) {
        BigDecimal doubleGap = doubleCentre(below).subtract(doubleCentre(above)).abs();
        return doubleGap.compareTo(above.height().min(below.height())) > 0;
    }

    /** One span with the reading position it arrived in. */
    private record Placed(AnchorSpan span, int ordinal) {}
}
