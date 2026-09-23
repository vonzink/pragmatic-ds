package com.pragmaticds.docengine.extraction.overlay;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import java.util.UUID;

/**
 * One {@code extracted_field} occurrence reduced to the two things a carry-forward needs: WHICH
 * occurrence it is, and WHAT the machine read for it.
 *
 * <p>The row id is deliberately NOT the identity. A re-extraction is a delete-then-recreate, so
 * every id changes; a {@code review_decision} that names an id is pointing at a row that no longer
 * exists the moment the package is re-run. The STABLE identity is {@link #coordinate()} — {@code
 * (logical_document_id, field_name, group_key)} — which is exactly the tuple Postgres already
 * enforces as unique among a document's current rows ({@code extracted_field_one_current}, widened
 * for repeating groups in Spec 5a). Because the database guarantees that tuple is unique per
 * generation, a coordinate can never ambiguously match two occurrences: a correction made against
 * "property B, rents received" cannot land on property A. That guarantee is the whole reason this
 * key was chosen over anything derived from position, ordinal or confidence.
 *
 * <p>{@code logicalDocumentId} is part of the coordinate on purpose. A regroup that MOVES a page
 * into a NEW logical document gives that document a fresh id, so a correction made on the old
 * document does not follow the page across. That is the intended outcome: the occurrence a human
 * looked at is not demonstrably the occurrence now carrying the name, and this repo would rather
 * lose a correction than re-attach one to the wrong row.
 *
 * <p>{@code schemaId} and {@code displayedText} are not identity — they are the EVIDENCE the
 * decision was made against, used to decide whether a carried decision is still trustworthy. See
 * {@link ReviewCarryForwardPlanner}.
 */
public record FieldSnapshot(
        UUID fieldId,
        UUID logicalDocumentId,
        String fieldName,
        String groupKey,
        UUID schemaId,
        String displayedText,
        String reviewStatus) {

    /**
     * UNIT SEPARATOR, not a printable character: a field name and a group key are authored content,
     * and a schema containing the separator would otherwise let two distinct coordinates collide
     * into one string — precisely the mis-attachment this key exists to make impossible. Written as
     * a char constant rather than an invisible literal in a string.
     */
    private static final char SEPARATOR = (char) 0x1F;

    /**
     * The stable identity of an occurrence across re-extractions. Never the row id. A null group
     * key is the pre-Spec-5a ungrouped row and joins as empty, matching the {@code
     * coalesce(group_key, '')} the unique index already uses.
     */
    public String coordinate() {
        return logicalDocumentId
                + String.valueOf(SEPARATOR)
                + fieldName
                + SEPARATOR
                + (groupKey == null ? "" : groupKey);
    }

    /** Snapshots a persisted row. Values are carried in memory only — never logged. */
    public static FieldSnapshot of(ExtractedField field) {
        return new FieldSnapshot(
                field.getId(),
                field.getLogicalDocumentId(),
                field.getFieldName(),
                field.getGroupKey(),
                field.getSchemaId(),
                field.getDisplayedText(),
                field.getReviewStatus());
    }
}
