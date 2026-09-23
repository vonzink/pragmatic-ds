package com.pragmaticds.rag.lab.findings;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns one field occurrence into the anchor a finding carries.
 *
 * <p>Pure and local: everything needed is already inside the verified envelope, so resolving an
 * anchor costs no I/O and cannot fail on a network. That is what lets anchoring happen inside a
 * deterministic tool rather than beside it.
 *
 * <p>The evidence span with the lowest ordinal wins. Several spans is one occurrence read across
 * several places, not several findings; picking by ordinal is deterministic regardless of how the
 * envelope happens to order them, and whether to draw the rest is a decision for whoever renders
 * the page.
 */
public final class FindingAnchorResolver {

    private FindingAnchorResolver() {}

    /** The anchor for one occurrence; unanchorable rather than absent when it has no evidence. */
    public static FindingAnchor resolve(LogicalDocument document, FieldOccurrence occurrence) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(occurrence, "occurrence");

        Optional<EvidenceSpan> first = occurrence.evidence().stream()
                .min(Comparator.comparingInt(EvidenceSpan::ordinal));

        return new FindingAnchor(
                document.id(),
                document.documentTypeCode(),
                document.ordinal(),
                occurrence.name(),
                occurrence.groupKey(),
                first.map(EvidenceSpan::pageId).orElse(null),
                first.map(EvidenceSpan::box).orElse(null));
    }
}
