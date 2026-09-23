package com.pragmaticds.docengine.extraction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.domain.FieldEvidence;
import com.pragmaticds.docengine.extraction.overlay.FieldOverlayPort;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.extraction.repo.FieldEvidenceRepository;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.ConfidenceComponentsView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.EvidenceView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.FieldView;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.pii.MaskableValue;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Assembles {@link DocumentFieldsView} — the per-document occurrence projection with the evidence
 * chain behind every value, corrections overlaid at read time.
 *
 * <p>Extracted from {@code DocumentFieldsController} so the read model has more than one reader:
 * the Markdown surface ({@code DocumentMarkdownController}) renders THIS view rather than
 * re-querying, which is what makes the two surfaces incapable of disagreeing about a value, a
 * mask, an order or a group key. The guard sequence lives here too, once, for the same reason.
 *
 * <p>Tenancy: {@link #require} is the org guard — a cross-tenant or tombstoned id answers 404
 * exactly like a nonexistent one. Everything below it travels through {@code @TenantId}-filtered
 * derived queries.
 */
@Service
public class DocumentFieldsReader {

    private final LogicalDocumentRepository documents;
    private final PackageRefRepository packages;
    private final ExtractedFieldRepository fields;
    private final FieldEvidenceRepository evidence;
    private final PageRepository pages;
    private final FieldGroupKinds groupKinds;
    private final FieldTextProvenance textProvenance;
    /** Read-time overlay of human corrections (implemented in :review). Optional by design. */
    private final ObjectProvider<FieldOverlayPort> overlay;

    private final ObjectMapper mapper = new ObjectMapper();

    public DocumentFieldsReader(
            LogicalDocumentRepository documents,
            PackageRefRepository packages,
            ExtractedFieldRepository fields,
            FieldEvidenceRepository evidence,
            PageRepository pages,
            FieldGroupKinds groupKinds,
            FieldTextProvenance textProvenance,
            ObjectProvider<FieldOverlayPort> overlay) {
        this.documents = documents;
        this.packages = packages;
        this.fields = fields;
        this.evidence = evidence;
        this.pages = pages;
        this.groupKinds = groupKinds;
        this.textProvenance = textProvenance;
        this.overlay = overlay;
    }

    /**
     * The readable document behind an id, or an opaque 404. A tombstoned package's document is
     * unreadable — its field values and normalized arms must 404 like a nonexistent document
     * (soft-delete read-exclusion).
     */
    public LogicalDocument require(UUID documentId) {
        UUID orgId = TenantContext.require();
        LogicalDocument document =
                documents
                        .findByIdAndOrgId(documentId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(document.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        return document;
    }

    /** The full occurrence projection for an already-guarded document. */
    public DocumentFieldsView fieldsOf(LogicalDocument document) {
        List<ExtractedField> currentFields =
                fields.findCurrentOccurrencesByLogicalDocumentId(document.getId());
        Map<UUID, List<FieldEvidence>> evidenceByField =
                currentFields.isEmpty()
                        ? Map.of()
                        : evidence
                                .findByExtractedFieldIdInOrderByExtractedFieldIdAscRoleAscOrdinalAsc(
                                        currentFields.stream().map(ExtractedField::getId).toList())
                                .stream()
                                .sorted(evidenceOrder())
                                .collect(Collectors.groupingBy(FieldEvidence::getExtractedFieldId));
        Map<UUID, Integer> pageIndexById =
                pages.findByPackageIdOrderByPackagePageIndex(document.getPackageId()).stream()
                        .collect(Collectors.toMap(Page::getId, Page::getPackagePageIndex));
        // Layer-4 overlay: a corrected field shows its corrected value; the machine's Layer-2
        // displayed_text is untouched in extracted_field and surfaced through the history strip.
        Map<UUID, String> effectiveValues = effectiveValues(currentFields);
        // One lookup for the whole document — it carries both the group kinds and the schema
        // version, so the version query this method used to issue separately is gone.
        FieldGroupKinds.Lookup kinds =
                groupKinds.forSchemas(
                        currentFields.stream().map(ExtractedField::getSchemaId).toList());
        // The VALUE spans behind every occurrence, resolved in ONE query for the whole document.
        // VALUE only: a LABEL span says how the value was FOUND, not what it says, so a native
        // caption beside a recognised number must not launder that number into looking read.
        Map<UUID, List<Long>> valueSpansByField =
                valueSpansByField(currentFields, evidenceByField);
        FieldTextProvenance.Lookup provenance =
                textProvenance.forSpans(
                        valueSpansByField.values().stream().flatMap(List::stream).toList());

        List<FieldView> fieldViews =
                currentFields.stream()
                        .map(
                                field ->
                                        new FieldView(
                                                // The id a client PATCHes to correct this field —
                                                // an id, never PII, so it is not masked.
                                                field.getId(),
                                                field.getFieldName(),
                                                // The occurrence's own coordinate. Null for every
                                                // ungrouped field, which is byte-identical to the
                                                // pre-Spec-5a response apart from the key itself.
                                                field.getGroupKey(),
                                                // ...and the SHAPE the key belongs to, so a null
                                                // key on a grouped field is legible as one.
                                                kinds.kindOf(
                                                        field.getSchemaId(), field.getFieldName()),
                                                // ...and whether this occurrence's characters were
                                                // READ from a text layer or RECOGNISED from pixels.
                                                provenance.of(
                                                        valueSpansByField.getOrDefault(
                                                                field.getId(), List.of())),
                                                field.getDataType(),
                                                MaskableValue.of(
                                                        effectiveValues.getOrDefault(
                                                                field.getId(),
                                                                field.getDisplayedText()),
                                                        field.isSensitive()),
                                                MaskableValue.of(
                                                        field.getRawValue(), field.isSensitive()),
                                                normalizedViewOf(
                                                        field, effectiveValues.get(field.getId())),
                                                field.getExtractionMethod(),
                                                field.getExtractorVersion(),
                                                field.getConfidence(),
                                                componentsOf(field.getConfidenceComponents()),
                                                field.getValidationStatus(),
                                                field.getReviewStatus(),
                                                // ...and D11's verdict on the SERVED value: the
                                                // same transaction that appends a decision flips
                                                // review_status, and the overlay map says whether
                                                // the value above is a human's — so this is the
                                                // latest decision, with zero extra queries.
                                                EffectiveStatus.of(
                                                        field.getReviewStatus(),
                                                        effectiveValues.containsKey(
                                                                field.getId())),
                                                field.isSensitive(),
                                                evidenceByField
                                                        .getOrDefault(field.getId(), List.of())
                                                        .stream()
                                                        .map(
                                                                row ->
                                                                        new EvidenceView(
                                                                                row.getRole(),
                                                                                row.getOrdinal(),
                                                                                row.getPageId(),
                                                                                pageIndexById
                                                                                        .getOrDefault(
                                                                                                row.getPageId(),
                                                                                                -1),
                                                                                row.getX(),
                                                                                row.getY(),
                                                                                row.getWidth(),
                                                                                row.getHeight(),
                                                                                row.getTextSpanId(),
                                                                                row.getLayoutElementId()))
                                                        .toList()))
                        .toList();

        return new DocumentFieldsView(
                document.getId(),
                document.getDocumentTypeCode(),
                schemaVersionOf(currentFields, kinds),
                fieldViews);
    }

    /**
     * {@code fieldId → the span ids its VALUE evidence cites}, skipping evidence with no span (a
     * reparse may null the link, and an element-only capture never had one).
     */
    private static Map<UUID, List<Long>> valueSpansByField(
            List<ExtractedField> currentFields, Map<UUID, List<FieldEvidence>> evidenceByField) {
        Map<UUID, List<Long>> byField = new java.util.HashMap<>();
        for (ExtractedField field : currentFields) {
            List<Long> spans =
                    evidenceByField.getOrDefault(field.getId(), List.of()).stream()
                            .filter(row -> FieldEvidence.ROLE_VALUE.equals(row.getRole()))
                            .map(FieldEvidence::getTextSpanId)
                            .filter(java.util.Objects::nonNull)
                            .toList();
            if (!spans.isEmpty()) {
                byField.put(field.getId(), spans);
            }
        }
        return byField;
    }

    /**
     * The normalized arm for a field: re-derived from the correction when one is present (so the
     * typed value follows the correction, not the stale machine value — Phase 7b review finding),
     * else the machine's Layer-2 normalized columns.
     */
    private static DocumentFieldsView.NormalizedView normalizedViewOf(
            ExtractedField field, String correctedValue) {
        boolean sensitive = field.isSensitive();
        if (correctedValue != null) {
            CorrectedTypedValue typed = CorrectedTypedValue.of(field.getDataType(), correctedValue);
            return new DocumentFieldsView.NormalizedView(
                    MaskableValue.of(typed.text(), sensitive),
                    MaskableValue.of(typed.number(), sensitive),
                    MaskableValue.of(typed.date(), sensitive));
        }
        return new DocumentFieldsView.NormalizedView(
                MaskableValue.of(field.getNormalizedText(), sensitive),
                MaskableValue.of(field.getNormalizedNumber(), sensitive),
                MaskableValue.of(field.getNormalizedDate(), sensitive));
    }

    /**
     * The human-corrected effective value per field, keyed only by corrected fields. Empty when
     * no overlay implementation is on the classpath ({@code :review} absent) — then the machine
     * value renders unchanged.
     */
    private Map<UUID, String> effectiveValues(List<ExtractedField> currentFields) {
        FieldOverlayPort port = overlay.getIfAvailable();
        if (port == null || currentFields.isEmpty()) {
            return Map.of();
        }
        return port.effectiveValues(currentFields.stream().map(ExtractedField::getId).toList());
    }

    /** VALUE evidence renders before LABEL — the value box is what review highlights first. */
    static java.util.Comparator<FieldEvidence> evidenceOrder() {
        return java.util.Comparator.comparing(
                        (FieldEvidence row) -> FieldEvidence.ROLE_VALUE.equals(row.getRole()) ? 0 : 1)
                .thenComparing(FieldEvidence::getRole)
                .thenComparingInt(FieldEvidence::getOrdinal);
    }

    /** The schema version behind the rows (they share one schema per document by construction). */
    private static String schemaVersionOf(
            List<ExtractedField> currentFields, FieldGroupKinds.Lookup kinds) {
        return currentFields.isEmpty()
                ? null
                : kinds.versionOf(currentFields.get(0).getSchemaId());
    }

    private ConfidenceComponentsView componentsOf(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return new ConfidenceComponentsView(
                    node.path("spanConfidence").decimalValue(),
                    node.path("anchorStrength").decimalValue(),
                    node.path("normalizerCertainty").decimalValue());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // ids only — never the column body.
            throw new IllegalStateException("unreadable confidence components");
        }
    }
}
