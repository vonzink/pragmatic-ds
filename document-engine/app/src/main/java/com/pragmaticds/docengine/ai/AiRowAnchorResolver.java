package com.pragmaticds.docengine.ai;

import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves an AMBIGUOUS anchor using the ROW it belongs to.
 *
 * <p>{@link AiEvidenceAnchor} answers one question — does this printed text occur on the cited
 * page, exactly once? — and a repeating group routinely defeats it. A statement with three
 * identical $130.00 checks paid on 06/18 prints {@code 130.00} and {@code 06/18} several times, so
 * every one of those cells ties and every one is review-flagged, even though the reader was never
 * in doubt about which line it read. That is the matcher being honest about what a string match can
 * prove, not a misread: the values are right and the ledger reconciles.
 *
 * <p>The engine already holds what breaks the tie. Each row's OTHER fields — a unique description,
 * a unique running balance — anchored uniquely, and their spans say where that row sits on the
 * page. A candidate that shares the row is the one the reader read; the identical candidates twelve
 * points up and twelve points down are not. This resolver promotes on exactly that evidence, so a
 * row-scoped anchor is stronger evidence than the unanchored flag it replaces, not weaker: it
 * carries a page, a box, and a reason.
 *
 * <h2>What it will not do</h2>
 *
 * <ul>
 *   <li><b>Never demotes.</b> The only transition is AMBIGUOUS → MATCHED. A MATCHED anchor is left
 *       alone and an UNANCHORED one — text that appears nowhere on the page — stays unanchored,
 *       because no amount of row geometry conjures evidence that does not exist.
 *   <li><b>Never guesses.</b> The band must come from at least one uniquely-matched sibling, and
 *       exactly one candidate may share it. Zero survivors or two survivors both leave the anchor
 *       ambiguous — missing over wrong.
 *   <li><b>Never chains.</b> The band is measured ONCE, from {@link
 *       AiEvidenceAnchor.Resolution#UNIQUE} matches only, before any promotion is applied. A
 *       promoted anchor can therefore never widen the band that promotes the next field, which is
 *       what keeps the result independent of map iteration order.
 *   <li><b>Never crosses a page.</b> A row is on one page. Siblings matched on two different pages
 *       describe no single band, so that group is skipped entirely.
 * </ul>
 */
final class AiRowAnchorResolver {

    /**
     * Share of the shorter extent that must overlap for two boxes to count as the same row.
     * Mirrors {@code DefaultFieldExtractionEngine}'s cell-overlap convention on the other axis:
     * measured against the NARROWER of the two, and touching edges are not an overlap. Half is the
     * same default the deterministic engine uses for a column.
     */
    private static final BigDecimal DEFAULT_ROW_OVERLAP = new BigDecimal("0.5");

    private final BigDecimal rowOverlap;

    AiRowAnchorResolver() {
        this(DEFAULT_ROW_OVERLAP);
    }

    AiRowAnchorResolver(BigDecimal rowOverlap) {
        this.rowOverlap =
                rowOverlap == null || rowOverlap.signum() <= 0 ? DEFAULT_ROW_OVERLAP : rowOverlap;
    }

    /**
     * @param members every anchored coordinate with the repeating group and row it belongs to; a
     *     null {@code group} or {@code groupKey} means the value is not part of a row and is
     *     ignored
     * @param anchors the matches from {@link AiEvidenceAnchor}, keyed by coordinate
     * @return ONLY the promotions, keyed by coordinate — an empty map when nothing could be
     *     resolved. Returning the delta rather than a rewritten map is deliberate: it makes
     *     "this pass can only ever add anchors" structural rather than a claim in a comment.
     */
    Map<String, AiEvidenceAnchor.Match> resolve(
            List<Member> members, Map<String, AiEvidenceAnchor.Match> anchors) {
        if (members == null || members.isEmpty() || anchors == null || anchors.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Member>> byGroup = new LinkedHashMap<>();
        for (Member member : members) {
            if (member == null
                    || member.group() == null
                    || member.groupKey() == null
                    || member.coordinate() == null) {
                continue; // summary fields have no row to be scoped to
            }
            // Partition on (group, row), never row alone. Row keys restart per group, so a check
            // and a transaction can both be 000001 — and banding them together would let one row's
            // geometry resolve the other's ambiguous cell to a span it has no business in.
            byGroup.computeIfAbsent(member.rowId(), key -> new ArrayList<>()).add(member);
        }

        Map<String, AiEvidenceAnchor.Match> promotions = new HashMap<>();
        for (List<Member> group : byGroup.values()) {
            Band band = bandOf(group, anchors);
            if (band == null) {
                continue;
            }
            for (Member member : group) {
                AiEvidenceAnchor.Match anchor = anchors.get(member.coordinate());
                if (anchor == null
                        || anchor.status() != AiEvidenceAnchor.Status.AMBIGUOUS
                        || anchor.candidates().isEmpty()) {
                    continue;
                }
                List<TextSpan> resolved = onlyCandidateInBand(anchor.candidates(), band);
                if (resolved != null) {
                    promotions.put(member.coordinate(), AiEvidenceAnchor.Match.rowScoped(resolved));
                }
            }
        }
        return Map.copyOf(promotions);
    }

    /**
     * The row's vertical extent, measured from the group's uniquely-matched spans. Null when the
     * group has no unique match to measure from, or when those matches straddle two pages.
     */
    private static Band bandOf(List<Member> group, Map<String, AiEvidenceAnchor.Match> anchors) {
        UUID pageId = null;
        BigDecimal top = null;
        BigDecimal bottom = null;
        for (Member member : group) {
            AiEvidenceAnchor.Match anchor = anchors.get(member.coordinate());
            if (anchor == null
                    || anchor.status() != AiEvidenceAnchor.Status.MATCHED
                    || anchor.resolution() != AiEvidenceAnchor.Resolution.UNIQUE) {
                continue;
            }
            for (TextSpan span : anchor.spans()) {
                if (span == null || span.getY() == null || span.getHeight() == null) {
                    continue;
                }
                if (pageId == null) {
                    pageId = span.getPageId();
                } else if (!pageId.equals(span.getPageId())) {
                    return null; // one row, two pages: not a band this resolver can trust
                }
                BigDecimal spanTop = span.getY();
                BigDecimal spanBottom = spanTop.add(span.getHeight());
                top = top == null ? spanTop : top.min(spanTop);
                bottom = bottom == null ? spanBottom : bottom.max(spanBottom);
            }
        }
        return pageId == null || top == null ? null : new Band(pageId, top, bottom);
    }

    /** The single candidate run sitting on {@code band}, or null when zero or several do. */
    private List<TextSpan> onlyCandidateInBand(List<List<TextSpan>> candidates, Band band) {
        List<TextSpan> found = null;
        for (List<TextSpan> candidate : candidates) {
            if (!inBand(candidate, band)) {
                continue;
            }
            if (found != null) {
                return null; // two rows claim it — exactly the tie this pass refuses to break
            }
            found = candidate;
        }
        return found;
    }

    /** Every span of the run is on the band's page, and the run's extent shares the row. */
    private boolean inBand(List<TextSpan> candidate, Band band) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        BigDecimal top = null;
        BigDecimal bottom = null;
        for (TextSpan span : candidate) {
            if (span == null
                    || span.getY() == null
                    || span.getHeight() == null
                    || !band.pageId().equals(span.getPageId())) {
                return false;
            }
            BigDecimal spanTop = span.getY();
            BigDecimal spanBottom = spanTop.add(span.getHeight());
            top = top == null ? spanTop : top.min(spanTop);
            bottom = bottom == null ? spanBottom : bottom.max(spanBottom);
        }
        return sharesRow(band.top(), band.bottom(), top, bottom);
    }

    /**
     * The vertical twin of the deterministic engine's same-column test: the two extents overlap by
     * at least {@code rowOverlap} of the SHORTER of the two. Shorter, not taller, because a wrapped
     * description is routinely twice the height of the amount beside it and measuring against the
     * description would reject every real cell.
     *
     * <p>A degenerate extent — a zero-height span, which the worker does emit — makes the fraction
     * meaningless, so it is not a row match. That is the conservative answer: the anchor stays
     * ambiguous and review-flagged rather than being pinned on a box with no vertical extent.
     */
    private boolean sharesRow(
            BigDecimal bandTop, BigDecimal bandBottom, BigDecimal top, BigDecimal bottom) {
        BigDecimal overlap = bandBottom.min(bottom).subtract(bandTop.max(top));
        if (overlap.signum() <= 0) {
            return false;
        }
        BigDecimal shorter = bandBottom.subtract(bandTop).min(bottom.subtract(top));
        if (shorter.signum() <= 0) {
            return false;
        }
        return overlap.compareTo(shorter.multiply(rowOverlap)) >= 0;
    }

    /** One anchored coordinate and the repeating group it belongs to. */
    /**
     * @param coordinate the anchor key, {@code fieldName#groupKey}
     * @param group which repeating group this cell belongs to — {@code "transactions"},
     *     {@code "checks"}, … Null for a value that is not in a repeating group at all.
     * @param groupKey the row's key WITHIN that group. Row keys are only unique inside their own
     *     group: a check with no printed number and the first transaction are both {@code 000001}.
     *     Partitioning on the key alone therefore merges unrelated rows, which is why {@code group}
     *     exists rather than the key being trusted on its own.
     */
    record Member(String coordinate, String group, String groupKey) {

        /**
         * The partition a row band is computed over. Never null for a member that has a row.
         *
         * <p>NUL rather than a printable separator: a groupKey can be a check number as printed on
         * the form, so any character a bank might print is a character that could forge a collision
         * across groups — which is the very thing this key exists to prevent.
         */
        String rowId() {
            return group + '\0' + groupKey;
        }
    }

    /** One row's vertical extent on one page. */
    private record Band(UUID pageId, BigDecimal top, BigDecimal bottom) {}
}
