package com.pragmaticds.rag.lab.findings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the findings many tools produced into the one array a run publishes.
 *
 * <p>Two things a tool cannot do, done here. A subject key needs the run's subject scope, and a
 * tool holds no run context — that is the whole reason tools are deterministic. And ordering has
 * to be decided once across every tool's output, or the array would depend on Spring bean order
 * and the golden files would move on their own.
 *
 * <p><b>Ordering is on stage-one fields.</b> Sorting by subject key would be circular — it does
 * not exist until this class runs — and would give no order at all when no scope was supplied.
 * Rule id, then document type, ordinal, field name and group key: all present the moment a tool
 * returns.
 *
 * <p>Nothing is logged: a summary names a field on a borrower's document.
 */
public final class FindingAssembler {

    private FindingAssembler() {}

    /** Two rules claiming one subject. A configuration error, carrying no borrower values. */
    public static final class DuplicateSubjectException extends RuntimeException {
        public DuplicateSubjectException() {
            super("DUPLICATE_FINDING_SUBJECT");
        }
    }

    /**
     * Stamps subject keys and returns the findings in publication order.
     *
     * @param subjectScope the Suite's opaque per-loan scope, or null when none was supplied — in
     *     which case subject keys stay null and waivers simply cannot carry
     */
    public static List<Finding> assemble(List<Finding> merged, String subjectScope) {
        Objects.requireNonNull(merged, "merged");

        List<Finding> ordered = new ArrayList<>(merged);
        ordered.sort(publicationOrder());

        if (subjectScope == null) {
            return List.copyOf(ordered);
        }

        Set<String> seen = new HashSet<>();
        List<Finding> stamped = new ArrayList<>(ordered.size());
        for (Finding finding : ordered) {
            String subjectKey = subjectKey(finding, subjectScope);
            // Rejected rather than merged: two rules that describe one subject would share one
            // waiver, so waiving either would silently waive the other.
            if (!seen.add(subjectKey)) {
                throw new DuplicateSubjectException();
            }
            stamped.add(finding.withSubjectKey(subjectKey));
        }
        return List.copyOf(stamped);
    }

    private static String subjectKey(Finding finding, String subjectScope) {
        List<String> parts = new ArrayList<>();
        parts.add(subjectScope);
        parts.add(finding.ruleId());
        parts.add(finding.ruleVersion());
        for (FindingAnchor anchor : finding.anchors()) {
            parts.add(anchor.subjectPart());
        }
        return FindingDigest.of(parts.toArray(new String[0]));
    }

    /**
     * Rule id, then document type, ordinal, field name, group key — every key extracted through a
     * named helper method rather than a chain of lambdas, because {@code Comparator.comparing}
     * chained with {@code thenComparing} cannot always infer a lambda's parameter and return types
     * across the chain; a method reference to a typed helper sidesteps that instead of fighting it
     * with explicit type witnesses.
     */
    private static Comparator<Finding> publicationOrder() {
        return Comparator.comparing(Finding::ruleId)
                .thenComparing(FindingAssembler::documentTypeCode)
                .thenComparingInt(FindingAssembler::documentOrdinal)
                .thenComparing(FindingAssembler::fieldName)
                .thenComparing(FindingAssembler::groupKey);
    }

    private static String documentTypeCode(Finding finding) {
        return finding.anchors().isEmpty() ? "" : finding.anchors().get(0).documentTypeCode();
    }

    private static int documentOrdinal(Finding finding) {
        return finding.anchors().isEmpty() ? Integer.MIN_VALUE : finding.anchors().get(0).documentOrdinal();
    }

    private static String fieldName(Finding finding) {
        return finding.anchors().isEmpty() ? "" : finding.anchors().get(0).fieldName();
    }

    /**
     * Null group keys sort first. {@code Comparator.nullsFirst} is not reachable through an
     * {@code int}/{@code String}-typed key extractor chain here, so the absent case (no anchor, or
     * an anchor with a null group key) is folded into the empty string, which sorts before every
     * non-empty group key.
     */
    private static String groupKey(Finding finding) {
        if (finding.anchors().isEmpty()) {
            return "";
        }
        String groupKey = finding.anchors().get(0).groupKey();
        return groupKey == null ? "" : groupKey;
    }
}
