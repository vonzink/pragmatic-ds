package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.classification.match.SpanJoin;
import com.pragmaticds.docengine.classification.match.TextFold;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.pragmaticds.docengine.platform.regex.BoundedCharSequence;

/**
 * A scope's spans joined in order the way the page PRINTED them — {@link SpanJoin} decides each
 * seam, so spans a producer cut apart mid-token (an amount whose punctuation is set in a second
 * font) close back up and only a printed gap becomes a space — with the offset table that maps a
 * matched {@code [start, end)} range back to ALL spans whose interval overlaps it. A separator
 * belongs to neither neighbour, so a match ending exactly at a span boundary does not drag in the
 * next span; where the separator is empty the two spans are simply adjacent and the same test
 * still holds. This is {@code AnchorMatcher}'s exact approach. The joined text exists only inside
 * this object — never persisted, logged, or thrown.
 *
 * <p>TWO copies of that join are held, at IDENTICAL length. Patterns run over the {@link TextFold}
 * -folded copy, so a form printing {@code Employee’s} with U+2019 matches a schema authored with
 * the ASCII {@code Employee's}. {@link #text()} returns the document's OWN characters, so the
 * displayed value {@code capture} slices out of it, the row persisted from that value, and the
 * evidence box drawn beside it all still agree with the page. The fold decides what MATCHES; it
 * never rewrites what is REPORTED.
 */
final class SpanText {

    /** A matched {@code [start, end)} range in the joined text. */
    record Range(int start, int end) {}

    private final List<SpanRef> spans;
    private final String joined;
    /**
     * {@link #joined} with printed punctuation folded to the characters labels are authored with.
     * The SAME LENGTH as {@code joined} — {@link TextFold#fold(String)} is 1 char in, 1 char out —
     * which is what lets a range found here index {@code joined} and {@link #spanStarts} alike. A
     * transform that were not length-preserving (blanket NFKC expands {@code ﬁ} to two chars)
     * would shift every later offset and hand the reviewer a confident evidence box over the wrong
     * words: strictly worse than the missing field the fold exists to fix.
     */
    private final String matchable;

    /** Per span: inclusive start offset in {@code joined}; end = start + text length. */
    private final int[] spanStarts;

    private SpanText(List<SpanRef> spans, String joined, int[] spanStarts) {
        this.spans = spans;
        this.joined = joined;
        this.matchable = TextFold.fold(joined);
        this.spanStarts = spanStarts;
    }

    static SpanText of(List<SpanRef> spans) {
        StringBuilder joined = new StringBuilder();
        int[] starts = new int[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) {
                joined.append(
                        SpanJoin.separator(spans.get(i - 1).box(), spans.get(i).box()));
            }
            starts[i] = joined.length();
            joined.append(spans.get(i).text());
        }
        return new SpanText(List.copyOf(spans), joined.toString(), starts);
    }

    /** The scope EXACTLY as the document printed it — what a captured value is sliced from. */
    String text() {
        return joined;
    }

    /**
     * The pattern a label anchor matches with, compiled by the shared {@link TextFold} seam so
     * classification's {@code AnchorMatcher} and this class cannot diverge. Literals are
     * case-insensitive containment of the FOLDED phrase — NEVER a lowered copy: case folding can
     * change string LENGTH (Turkish dotted capital İ lowers to two chars), silently shifting every
     * later offset and mis-attributing evidence spans (Phase 4 review finding). Regex labels run
     * structurally as authored, their own foldable literals rewritten ESCAPED so a folded
     * character can never become a metacharacter.
     */
    static Pattern labelPattern(LabelSpec label) {
        return TextFold.pattern(label.kind(), label.pattern());
    }

    /**
     * A VALUE pattern: always a regex, folded through the same seam so a capture pattern spelled
     * with an apostrophe reads a name the form printed with U+2019.
     */
    static Pattern valuePattern(String authored) {
        return TextFold.regexPattern(authored);
    }

    Optional<Range> findFirst(Pattern pattern) {
        return findOccurrence(pattern, 0);
    }

    /** Match #{@code occurrence} (0-based) of {@code pattern} in the joined text. */
    Optional<Range> findOccurrence(Pattern pattern, int occurrence) {
        if (joined.isEmpty()) {
            return Optional.empty();
        }
        // Bounded: `pattern` may be tenant-authored (POST /v1/extraction-schemas),
        // and a pattern that COMPILES can still backtrack without end. One budget
        // covers the whole find() loop below, so the work per call is bounded in
        // total rather than per occurrence.
        Matcher matcher = pattern.matcher(BoundedCharSequence.over(matchable));
        for (int i = 0; i <= occurrence; i++) {
            if (!matcher.find()) {
                return Optional.empty();
            }
        }
        return Optional.of(new Range(matcher.start(), matcher.end()));
    }

    /** Every occurrence, in reading order, under the same one budget as {@link #findOccurrence}. */
    List<Range> findAll(Pattern pattern) {
        if (joined.isEmpty()) {
            return List.of();
        }
        Matcher matcher = pattern.matcher(BoundedCharSequence.over(matchable));
        List<Range> all = new ArrayList<>();
        while (matcher.find()) {
            all.add(new Range(matcher.start(), matcher.end()));
        }
        return List.copyOf(all);
    }

    /** All spans whose {@code [start, end)} interval overlaps the range, in scope order. */
    List<SpanRef> overlapping(Range range) {
        List<SpanRef> hit = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            int spanStart = spanStarts[i];
            int spanEnd = spanStart + spans.get(i).text().length();
            if (spanStart < range.end() && spanEnd > range.start()) {
                hit.add(spans.get(i));
            }
        }
        return List.copyOf(hit);
    }
}
