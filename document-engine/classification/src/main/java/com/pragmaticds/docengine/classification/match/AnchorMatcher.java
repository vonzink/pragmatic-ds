package com.pragmaticds.docengine.classification.match;

import com.pragmaticds.docengine.classification.rules.Anchor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import com.pragmaticds.docengine.platform.regex.BoundedCharSequence;
import com.pragmaticds.docengine.platform.regex.RegexBudgetExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Matches one page's anchors against its spans joined in reading order (span {@code ordinal}),
 * each seam decided by {@link SpanJoin} so the join reads as the page PRINTED it rather than
 * inserting a space wherever the parser happened to end a word. The joined text exists only
 * inside this object — it is never persisted, logged, or returned; matches surface as span ids,
 * boxes, and offset ranges.
 *
 * <p>Both sides of the comparison pass through {@link TextFold}, the shared punctuation seam
 * extraction's {@code SpanText} also uses: a page that prints {@code Buyer’s} with U+2019 matches
 * a pack authored with the ASCII {@code Buyer's}, and vice versa. The fold is 1 char in, 1 char
 * out, so the matched offsets still index the ORIGINAL joined text and the spans a hit names are
 * the spans a reader sees.
 *
 * <p>Literal anchors are case-insensitive containment (a quoted {@code CASE_INSENSITIVE |
 * UNICODE_CASE} regex over the folded text — never a lowered copy, whose length can drift under
 * case folding and shift offsets). Regex anchors run {@code java.util.regex} structurally as
 * authored (the seeded YTD anchor relies on {@code \b} boundaries; packs wanting
 * case-insensitivity embed {@code (?i)}), with only their own foldable literals rewritten, and
 * rewritten ESCAPED so a folded character can never become a metacharacter — see {@link
 * TextFold#regexPattern}.
 *
 * <p>A hit maps back to ALL spans whose {@code [start, end)} interval in the joined text overlaps
 * the matched range. A separator belongs to neither neighbour, so a match ending exactly at a
 * span boundary does not drag in the next span — and where {@link SpanJoin} emitted no separator
 * at all the two spans are simply adjacent, which the same half-open test handles unchanged.
 * First occurrence wins when a pattern appears more than once — one anchor is one piece of
 * evidence, not a frequency count.
 */
public final class AnchorMatcher {

    private static final Logger log = LoggerFactory.getLogger(AnchorMatcher.class);

    private final List<AnchorSpan> spans;
    /**
     * The joined page text with printed punctuation folded to the characters anchors are authored
     * with. Same LENGTH as the unfolded join — {@link TextFold#fold(String)} is 1:1 — so {@link
     * #spanStarts}, computed from the spans' own text lengths, still addresses it exactly.
     */
    private final String joined;

    /** Per span: inclusive start offset in {@code joined}; end = start + text length. */
    private final int[] spanStarts;

    private AnchorMatcher(List<AnchorSpan> spans, String joined, int[] spanStarts) {
        this.spans = spans;
        this.joined = joined;
        this.spanStarts = spanStarts;
    }

    /** @param spansInReadingOrder the page's spans already ordered by {@code ordinal} */
    public static AnchorMatcher forSpans(List<AnchorSpan> spansInReadingOrder) {
        // The page's own geometry decides where a printed row ENDS before anything is joined —
        // see VisualRows for why the parser's single page-wide sequence is not safe to match a
        // phrase against.
        List<AnchorSpan> spans = VisualRows.inRowOrder(spansInReadingOrder);
        StringBuilder joined = new StringBuilder();
        int[] starts = new int[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) {
                // Same seam extraction's SpanText joins on, and deliberately so: two
                // subsystems that disagree about where a printed TOKEN ends will disagree
                // about what a document says, exactly as VisualRows/VisualLines share the
                // rule for where a printed ROW ends.
                joined.append(SpanJoin.separator(spans.get(i - 1).box(), spans.get(i).box()));
            }
            starts[i] = joined.length();
            joined.append(spans.get(i).text());
        }
        // Folded ONCE over the whole join rather than per span: TextFold.fold is length-
        // preserving, so `starts` — measured on the spans' own text — indexes the result
        // unchanged, and no other code has to know the fold happened.
        return new AnchorMatcher(spans, TextFold.fold(joined.toString()), starts);
    }

    public AnchorMatch match(Anchor anchor) {
        if (joined.isEmpty()) {
            return AnchorMatch.miss(anchor.id());
        }
        // Both kinds compile through the shared seam: literals quoted and case-insensitive,
        // regexes structurally as authored. Neither ever runs over a LOWERED copy — case folding
        // can change string LENGTH (Turkish dotted capital İ lowers to two chars), silently
        // shifting every later offset and mis-attributing evidence spans (Phase 4 review finding).
        // Bounded like the extraction sites. Rule packs are still migration-only, so
        // this is defence and not a live exposure — but it is the same engine and the
        // same backtracking, and a pack authoring surface would inherit the hole.
        Matcher matcher =
                TextFold.pattern(anchor.kind(), anchor.pattern())
                        .matcher(BoundedCharSequence.over(joined));
        try {
            if (!matcher.find()) {
                return AnchorMatch.miss(anchor.id());
            }
            return hit(anchor.id(), matcher.start(), matcher.end());
        } catch (RegexBudgetExceededException e) {
            // An anchor that could not finish is an anchor that did not match. Never a throw:
            // classification scores a page by summing the anchors that DID match, and one
            // pathological pattern must not be able to fail a page — still less a package.
            log.warn(
                    "regex budget exhausted, anchor treated as a miss: anchor={} budget={}"
                            + " subjectChars={}",
                    anchor.id(),
                    e.budget(),
                    e.subjectLength());
            return AnchorMatch.miss(anchor.id());
        }
    }

    private AnchorMatch hit(String anchorId, int start, int end) {
        List<Long> spanIds = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            int spanStart = spanStarts[i];
            int spanEnd = spanStart + spans.get(i).text().length();
            if (spanStart < end && spanEnd > start) {
                spanIds.add(spans.get(i).id());
                boxes.add(spans.get(i).box());
            }
        }
        return new AnchorMatch(anchorId, true, List.copyOf(spanIds), List.copyOf(boxes), start, end);
    }
}
