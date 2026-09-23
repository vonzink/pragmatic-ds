package com.pragmaticds.rag.lab.findings;

import java.util.List;
import java.util.Objects;

/**
 * One thing a deterministic rule noticed about a parsed package.
 *
 * <p>Three identity fields that are easy to conflate and expensive to un-conflate:
 * {@link #anchors()} say where to highlight and hold for one package; {@link #subjectKey()} says
 * which problem this is and holds for a loan's lifetime; {@link #inputDigest()} says what the
 * values were and is meant to change. A waiver matches on the subject key alone, and a changed
 * input digest is what stops a waiver taken against one number from silently covering another.
 *
 * <p>{@code subjectKey} is null as emitted here. Emitting this type holds no run context, and the
 * subject scope is run context; {@link FindingAssembler} stamps it via {@link
 * #withSubjectKey(String)}.
 *
 * <p>{@code summary} may contain borrower figures and must never be logged.
 */
public record Finding(
        String ruleId,
        String ruleVersion,
        Severity severity,
        String summary,
        String subjectKey,
        String inputDigest,
        List<FindingAnchor> anchors,
        List<String> citationIds) {

    /** How much a finding should stop someone. Advisory here; the Suite decides what it gates. */
    public enum Severity {
        BLOCKING,
        WARNING,
        INFO
    }

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(inputDigest, "inputDigest");
        anchors = List.copyOf(anchors);
        citationIds = List.copyOf(citationIds);
    }

    /** A copy carrying the assembled subject key. */
    public Finding withSubjectKey(String assignedSubjectKey) {
        return new Finding(ruleId, ruleVersion, severity, summary,
                Objects.requireNonNull(assignedSubjectKey, "assignedSubjectKey"),
                inputDigest, anchors, citationIds);
    }

    /**
     * A copy carrying the citation ids resolved against the run's pinned snapshot.
     *
     * <p>No caller yet. Corpus citation attachment is deferred — see the design's §6 — and this is
     * the seam it will use; the rule this slice ships cites nothing worth pinning.
     */
    public Finding withCitationIds(List<String> resolved) {
        return new Finding(ruleId, ruleVersion, severity, summary, subjectKey, inputDigest,
                anchors, resolved);
    }
}
