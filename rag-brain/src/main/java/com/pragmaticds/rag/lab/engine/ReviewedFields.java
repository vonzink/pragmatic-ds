package com.pragmaticds.rag.lab.engine;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One document's reviewed field values, as {@code GET /v1/documents/{id}/fields} served them:
 * masked, review-aware, and NOT byte-frozen. {@code sha256} and {@code byteCount} identify the
 * exact bytes this parse came from so a later read can be compared without keeping the values.
 */
public record ReviewedFields(
        UUID documentId,
        String documentTypeCode,
        String schemaVersion,
        List<ReviewedField> fields,
        String sha256,
        int byteCount) {

    public ReviewedFields {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(documentTypeCode, "documentTypeCode");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(sha256, "sha256");
        fields = List.copyOf(fields);
    }

    /** The engine's grouping shape, carried on every field (never null on the wire). */
    public enum GroupKind {
        NONE,
        ROW,
        COLUMN
    }

    /** One occurrence's reviewed value channel plus the identity needed to join it. */
    public record ReviewedField(
            String fieldName,
            String groupKey,
            GroupKind groupKind,
            EngineResultEnvelope.ReviewState effectiveStatus,
            String dataType,
            String displayedText,
            String rawValue,
            EngineResultEnvelope.NormalizedValue normalized,
            String extractionMethod,
            boolean sensitive,
            List<UUID> evidencePageIds) {

        public ReviewedField {
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(groupKind, "groupKind");
            Objects.requireNonNull(effectiveStatus, "effectiveStatus");
            Objects.requireNonNull(dataType, "dataType");
            Objects.requireNonNull(normalized, "normalized");
            Objects.requireNonNull(extractionMethod, "extractionMethod");
            evidencePageIds = List.copyOf(evidencePageIds);
        }

        /** Rule 1 on the read model: no value arm at all means the engine did not find it. */
        public boolean hasValue() {
            return displayedText != null
                    || normalized.text() != null
                    || normalized.number() != null
                    || normalized.date() != null;
        }
    }
}
