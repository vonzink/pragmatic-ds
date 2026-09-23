package com.pragmaticds.rag.lab.engine;

import com.pragmaticds.rag.lab.engine.DocumentEngineFailure.Code;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReviewState;
import com.pragmaticds.rag.lab.engine.ReviewedFields.ReviewedField;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Rewrites each pinned occurrence's VALUE channel from the engine read model and leaves its
 * identity (evidence, schema, confidence, validation) from the envelope.
 *
 * <p>Join key is (fieldName, groupKey) within a document. Both sides must name the same keys;
 * either direction of mismatch is a contract violation, because a run that silently ignored a
 * field on one side would be analyzing a document neither surface describes.
 */
public final class ReviewedValueOverlay {

    public record Overlaid(EngineResultEnvelope envelope, ReviewSnapshot snapshot) {}

    public Overlaid apply(EngineResultEnvelope pinned, Map<UUID, ReviewedFields> byDocumentId) {
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(byDocumentId, "byDocumentId");
        if (byDocumentId.size() != pinned.documents().size()) {
            throw new DocumentEngineFailure(Code.READMODEL_DOCUMENT_MISMATCH);
        }
        List<LogicalDocument> documents = new ArrayList<>();
        StringBuilder digestInput = new StringBuilder();
        Map<UUID, String> schemaVersions = new LinkedHashMap<>();
        int machine = 0;
        int corrected = 0;
        int rejected = 0;

        // Process documents in original pinned order for output, but track digest/counts/versions in ordinal order
        List<LogicalDocument> byOrdinal = new ArrayList<>(pinned.documents());
        byOrdinal.sort(Comparator.comparingInt(LogicalDocument::ordinal));

        Map<LogicalDocument, FieldOccurrence[]> overlaidFields = new HashMap<>();

        for (LogicalDocument document : pinned.documents()) {
            ReviewedFields view = byDocumentId.get(document.id());
            if (view == null || !view.documentTypeCode().equals(document.documentTypeCode())) {
                throw new DocumentEngineFailure(Code.READMODEL_DOCUMENT_MISMATCH);
            }
            Map<String, ReviewedField> byKey = new HashMap<>();
            for (ReviewedField field : view.fields()) {
                if (byKey.put(key(field.fieldName(), field.groupKey()), field) != null) {
                    throw new DocumentEngineFailure(Code.READMODEL_FIELD_MISMATCH);
                }
            }
            List<FieldOccurrence> fields = new ArrayList<>();
            for (FieldOccurrence occurrence : document.fields()) {
                ReviewedField reviewed = byKey.remove(key(occurrence.name(), occurrence.groupKey()));
                if (reviewed == null) {
                    throw new DocumentEngineFailure(Code.READMODEL_FIELD_MISMATCH);
                }
                fields.add(overlay(occurrence, reviewed));
            }
            if (!byKey.isEmpty()) {
                throw new DocumentEngineFailure(Code.READMODEL_FIELD_MISMATCH);
            }
            overlaidFields.put(document, fields.toArray(new FieldOccurrence[0]));
        }

        // Emit documents in original pinned order, but count/digest in ordinal order
        for (LogicalDocument document : pinned.documents()) {
            documents.add(new LogicalDocument(document.id(), document.documentTypeCode(),
                    document.ordinal(), document.pageIds(), List.of(overlaidFields.get(document))));
        }

        // Digest and counts in ordinal order
        for (LogicalDocument document : byOrdinal) {
            ReviewedFields view = byDocumentId.get(document.id());
            FieldOccurrence[] fields = overlaidFields.get(document);
            for (FieldOccurrence field : fields) {
                switch (field.reviewState()) {
                    case MACHINE -> machine++;
                    case CORRECTED -> corrected++;
                    case REJECTED -> rejected++;
                    case UNREVIEWED_SOURCE -> throw new IllegalStateException("parser never sets this");
                }
            }
            digestInput.append(view.sha256()).append('\n');
            schemaVersions.put(document.id(), view.schemaVersion());
        }

        EngineResultEnvelope overlaid = new EngineResultEnvelope(
                pinned.artifact(), pinned.envelopeVersion(), pinned.canonicalizationVersion(),
                pinned.packageId(), pinned.generation(), pinned.sources(), pinned.pages(),
                documents, pinned.unassignedPageIds(), pinned.provenance());
        ReviewSnapshot snapshot = new ReviewSnapshot(
                sha256(digestInput.toString()), documents.size(), machine, corrected, rejected,
                schemaVersions);
        return new Overlaid(overlaid, snapshot);
    }

    /**
     * A REJECTED or value-less occurrence deliberately retains the envelope's evidence and
     * confidence as citation identity even though its status is MISSING: those members describe
     * where the pinned envelope found (or failed to find) the value, not the value itself, and
     * stay meaningful for a reviewer even after the value channel is cleared.
     */
    private static FieldOccurrence overlay(FieldOccurrence pinned, ReviewedField reviewed) {
        return switch (reviewed.effectiveStatus()) {
            case REJECTED -> new FieldOccurrence(
                    pinned.name(), pinned.groupKey(), FieldStatus.MISSING, pinned.dataType(),
                    null, null, null, pinned.schema(), "NONE", pinned.extractorVersion(),
                    pinned.confidence(), pinned.confidenceComponents(), pinned.validationStatus(),
                    reviewed.sensitive(), pinned.evidence(), ReviewState.REJECTED);
            case CORRECTED -> {
                if (!reviewed.hasValue()) {
                    throw new DocumentEngineFailure(Code.ENGINE_READMODEL_MALFORMED);
                }
                yield new FieldOccurrence(
                        pinned.name(), pinned.groupKey(), FieldStatus.FOUND, pinned.dataType(),
                        reviewed.displayedText(), reviewed.rawValue(), reviewed.normalized(),
                        pinned.schema(), reviewed.extractionMethod(), pinned.extractorVersion(),
                        pinned.confidence(), pinned.confidenceComponents(),
                        pinned.validationStatus(), reviewed.sensitive(), pinned.evidence(),
                        ReviewState.CORRECTED);
            }
            case MACHINE -> {
                if (!reviewed.hasValue()) {
                    yield new FieldOccurrence(
                            pinned.name(), pinned.groupKey(), FieldStatus.MISSING,
                            pinned.dataType(), null, null, null, pinned.schema(), "NONE",
                            pinned.extractorVersion(), pinned.confidence(),
                            pinned.confidenceComponents(), pinned.validationStatus(),
                            reviewed.sensitive(), pinned.evidence(), ReviewState.MACHINE);
                }
                yield new FieldOccurrence(
                        pinned.name(), pinned.groupKey(), FieldStatus.FOUND, pinned.dataType(),
                        reviewed.displayedText(), reviewed.rawValue(), reviewed.normalized(),
                        pinned.schema(), reviewed.extractionMethod(), pinned.extractorVersion(),
                        pinned.confidence(), pinned.confidenceComponents(),
                        pinned.validationStatus(), reviewed.sensitive(), pinned.evidence(),
                        ReviewState.MACHINE);
            }
            case UNREVIEWED_SOURCE -> throw new IllegalStateException("parser never sets this");
        };
    }

    private static String key(String name, String groupKey) {
        return name + ":" + groupKey;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
