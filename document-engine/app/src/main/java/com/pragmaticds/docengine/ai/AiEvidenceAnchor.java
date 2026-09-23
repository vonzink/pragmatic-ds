package com.pragmaticds.docengine.ai;

import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Conservative evidence anchoring for one AI-returned printed value on one cited page.
 *
 * <p>AMBIGUOUS keeps its candidate runs. The matcher alone cannot choose between two identical
 * printed strings — three $130.00 checks paid on one date are genuinely indistinguishable to it —
 * but a caller that knows the value's ROW can, from geometry the engine already persisted. So the
 * candidates ride along on the {@link Match} for {@link AiRowAnchorResolver} to arbitrate. Nothing
 * downstream sees a difference: {@link Match#spans()} is still empty for a non-MATCHED match, so
 * every gate that keys on {@code status()} behaves exactly as before.
 */
final class AiEvidenceAnchor {

    private static final int MAX_MATCHED_SPANS = 12;

    /**
     * Upper bound on retained candidate runs. A page where one string repeats more than this is
     * past the point where row geometry would single one out anyway, and the cap keeps a
     * pathological page (a column of identical zeroes) from carrying thousands of span lists.
     * Exceeding it drops the candidates, leaving an AMBIGUOUS that can never be promoted.
     */
    private static final int MAX_RETAINED_CANDIDATES = 64;

    Match match(String printedText, List<TextSpan> pageSpans) {
        String target = fold(printedText);
        if (target.isEmpty() || pageSpans == null || pageSpans.isEmpty()) {
            return Match.unanchored();
        }

        List<List<TextSpan>> candidates = new ArrayList<>();
        for (int start = 0; start < pageSpans.size(); start++) {
            StringBuilder joined = new StringBuilder();
            int limit = Math.min(pageSpans.size(), start + MAX_MATCHED_SPANS);
            for (int end = start; end < limit; end++) {
                if (!joined.isEmpty()) {
                    joined.append(' ');
                }
                joined.append(pageSpans.get(end).getText());
                String candidate = fold(joined.toString());
                boolean matches =
                        start == end ? candidate.contains(target) : candidate.equals(target);
                if (matches) {
                    candidates.add(List.copyOf(pageSpans.subList(start, end + 1)));
                }
                if (candidate.length() > target.length() && start != end) {
                    break;
                }
            }
        }

        if (candidates.isEmpty()) {
            return Match.unanchored();
        }
        if (candidates.size() > 1) {
            return candidates.size() > MAX_RETAINED_CANDIDATES
                    ? Match.ambiguous()
                    : Match.ambiguous(candidates);
        }
        return new Match(Status.MATCHED, candidates.get(0));
    }

    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ')
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    enum Status {
        MATCHED,
        AMBIGUOUS,
        UNANCHORED
    }

    /**
     * HOW a MATCHED match was arrived at. Recorded because the two are not equally strong evidence
     * and a reviewer auditing a value is entitled to know which one vouches for it: {@link #UNIQUE}
     * means the printed text occurs exactly once on the cited page, {@link #ROW_SCOPED} means it
     * occurs several times and the row's own geometry picked one. Never a confidence component —
     * a row-scoped anchor IS an anchor — purely provenance.
     */
    enum Resolution {
        UNIQUE,
        ROW_SCOPED
    }

    /**
     * @param spans the anchored run — non-empty only when {@code status == MATCHED}
     * @param candidates the runs that tied, retained ONLY for AMBIGUOUS so a row-scoped resolver
     *     can arbitrate; empty otherwise
     */
    record Match(
            Status status,
            List<TextSpan> spans,
            Resolution resolution,
            List<List<TextSpan>> candidates) {

        Match {
            spans = List.copyOf(spans);
            candidates = candidates.stream().map(List::copyOf).toList();
        }

        Match(Status status, List<TextSpan> spans) {
            this(status, spans, Resolution.UNIQUE, List.of());
        }

        static Match ambiguous() {
            return new Match(Status.AMBIGUOUS, List.of());
        }

        static Match ambiguous(List<List<TextSpan>> candidates) {
            return new Match(Status.AMBIGUOUS, List.of(), Resolution.UNIQUE, candidates);
        }

        /** This ambiguity, resolved to one run by the row it belongs to. */
        static Match rowScoped(List<TextSpan> spans) {
            return new Match(Status.MATCHED, spans, Resolution.ROW_SCOPED, List.of());
        }

        static Match unanchored() {
            return new Match(Status.UNANCHORED, List.of());
        }
    }
}
