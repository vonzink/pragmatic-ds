package com.pragmaticds.docengine.classification.match;

import java.math.BigDecimal;

/**
 * What goes BETWEEN two adjacent spans when a page's text is joined for matching: nothing when
 * the page printed them as one token, a single space when the page printed a gap.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>A span is a pdfplumber WORD, and pdfplumber stops growing a word for reasons that have
 * nothing to do with what a reader sees. It cuts on any change in the {@code extra_attrs} the
 * worker asks for — {@code size} and {@code fontname} — so a producer that sets the punctuation
 * of an amount in a different font from its digits hands over {@code $1,321.18} as five spans
 * that print TOUCHING. It cuts on a baseline shift, so raised cents arrive separately at a zero
 * gap. Joining every pair with a space asserted a separation the page never printed: real ADP
 * earnings statements offered {@code $1 321 18} to the MONEY pattern, which correctly refused it,
 * and a required field went missing on the highest-volume income document in the corpus while the
 * amount sat right there on the page.
 *
 * <h2>The rule</h2>
 *
 * <p>Two spans are ONE token when they share a printed row and the gap between them is under
 * {@value #EM_FRACTION_TEXT} of the em. Row membership is {@code VisualRows}' own test — centres
 * within half the height of the SMALLER of the two — applied PAIRWISE, and it is load-bearing
 * rather than decorative: rows reach each other transitively through a tall span, so two spans
 * can sit in one row band and still be printed one above the other. Without the pairwise test a
 * Schedule E column header joined {@code line 2c} to the {@code from} printed beneath it.
 *
 * <p>The gap is compared as an ABSOLUTE value, which is what makes a page stored with {@code
 * /Rotate 180} safe: its spans are handed over in READING order, so canonical x runs backwards
 * along a row and every seam is hugely negative — far outside the threshold, so nothing joins.
 * Only a genuine touch (which measures at most a few hundredths of an em either side of zero)
 * falls inside.
 *
 * <h2>Where {@value #EM_FRACTION_TEXT} comes from</h2>
 *
 * <p>It is bracketed by font metrics, not fitted to a document:
 *
 * <ul>
 *   <li><b>Above:</b> the narrowest separator a page can actually print is a space, and the
 *       narrowest space advance among the PDF standard-14 faces is 0.25 em (Times-Roman, Symbol;
 *       Helvetica 0.278, Courier 0.6). Measured across all 77 committed fixture pages — 3641
 *       same-row adjacent pairs — the narrowest printed gap is 0.2167 em, a 24 pt {@code 1040}
 *       beside 12 pt masthead text, where the em is taken from the smaller of the two.
 *   <li><b>Below:</b> a seam INSIDE one printed token carries no advance at all, only positioning
 *       residue — the producer stepping by one font's metrics while another font draws the glyph.
 *       That is bounded by the spread of punctuation advances across text faces (Helvetica's
 *       comma 0.278 em against Times' 0.250 em), and measures 0.028 em on the reproduction.
 * </ul>
 *
 * <p>0.10 em sits in the middle of that window with roughly 3x clearance on each side, and is a
 * FRACTION of the em rather than a point value, so it scales with the type size instead of
 * fitting one document's font.
 *
 * <h2>What it deliberately does NOT rescue</h2>
 *
 * <p>When a font's ToUnicode resolves the punctuation to whitespace, pdfplumber discards those
 * characters and breaks the word on them; the comma and the decimal point are then gone from the
 * text layer and the seam left behind is the dropped glyph's own advance — 0.25 em, the same
 * width a printed space leaves. No geometry separates those two cases, and joining across one
 * would report {@code $132118} for a page that says {@code $1,321.18}. The threshold sits below
 * a glyph advance precisely so that stays a miss. A confident wrong amount is strictly worse than
 * an honest one.
 */
public final class SpanJoin {

    /** Below this fraction of the em, two spans print as one token. */
    private static final BigDecimal EM_FRACTION = new BigDecimal("0.10");

    private static final String EM_FRACTION_TEXT = "0.10 em";
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final String NOTHING = "";
    private static final String SPACE = " ";

    private SpanJoin() {}

    /**
     * The separator to place between {@code left} and {@code right}, which are adjacent in the
     * page's row order. All BigDecimal: coordinates never pass through double.
     */
    public static String separator(Box left, Box right) {
        BigDecimal em = left.height().min(right.height());
        if (em.signum() <= 0) {
            // A degenerate box carries no scale to measure against; a space is the reading the
            // engine has always had, and a missing join is a miss, not a wrong answer.
            return SPACE;
        }
        if (!sameRow(left, right, em)) {
            return SPACE;
        }
        BigDecimal gap = right.x().subtract(left.x().add(left.width())).abs();
        return gap.compareTo(EM_FRACTION.multiply(em)) < 0 ? NOTHING : SPACE;
    }

    /** {@code VisualRows}' row test, pairwise: centres within half the SMALLER height. */
    private static boolean sameRow(Box left, Box right, BigDecimal em) {
        return centre(left).subtract(centre(right)).abs().compareTo(em.multiply(HALF)) <= 0;
    }

    private static BigDecimal centre(Box box) {
        return box.y().add(box.height().multiply(HALF));
    }
}
