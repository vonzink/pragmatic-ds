package com.pragmaticds.rag.lab.findings;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a finding came from, in the two senses that differ.
 *
 * <p>{@code pageId} and {@code box} say where to draw the highlight and are scoped to one Document
 * Engine package, because a logical document's id is. {@link #subjectPart()} says which problem
 * this is and deliberately excludes both, so the same problem found on a re-uploaded document
 * still matches a waiver taken against the original.
 *
 * <p>{@code pageId} null means the occurrence carried no evidence span. That finding is still
 * true and is still emitted; it simply cannot be highlighted.
 *
 * <p><b>Duplicate-subject safety rests upstream.</b> {@link #subjectPart()} carries no occurrence
 * ordinal, so two occurrences of one field on one document would produce one subject part and, if
 * two findings were raised over them, a duplicate the assembler would reject. That cannot happen
 * because the Document Engine's envelope assembler already dedupes on
 * {@code OccurrenceIdentity(documentId, fieldName, groupKey)} before RAG Brain sees the envelope.
 * Slice 2's rules lean on that guarantee; a rule that raises one finding per occurrence rather
 * than per field would need an ordinal here first.
 */
public record FindingAnchor(
        UUID logicalDocumentId,
        String documentTypeCode,
        int documentOrdinal,
        String fieldName,
        String groupKey,
        UUID pageId,
        Box box) {

    public FindingAnchor {
        Objects.requireNonNull(logicalDocumentId, "logicalDocumentId");
        Objects.requireNonNull(documentTypeCode, "documentTypeCode");
        Objects.requireNonNull(fieldName, "fieldName");
        if (documentOrdinal < 0) {
            throw new IllegalArgumentException("documentOrdinal must not be negative");
        }
        // A page without a rectangle cannot be drawn, so it is not a partial anchor — it is a
        // malformed one, and accepting it would put an unhighlightable highlight on the wire.
        if (pageId != null && box == null) {
            throw new IllegalArgumentException("an anchor with a pageId requires a box");
        }
    }

    /** Whether this anchor can be highlighted. */
    public boolean anchored() {
        return pageId != null;
    }

    /**
     * The identity-bearing projection: document type, ordinal, field, group key. Geometry and the
     * package-scoped document id are excluded, because both change on re-upload and the problem
     * does not.
     */
    public String subjectPart() {
        return FindingDigest.of(
                documentTypeCode, Integer.toString(documentOrdinal), fieldName, groupKey);
    }
}
