package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.domain.FieldEvidence;
import com.pragmaticds.docengine.extraction.overlay.FieldOverlayPort;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.extraction.repo.FieldEvidenceRepository;
import com.pragmaticds.docengine.extraction.web.PackageExportView.BoundingBoxView;
import com.pragmaticds.docengine.extraction.web.PackageExportView.ExportDocumentView;
import com.pragmaticds.docengine.extraction.web.PackageExportView.ExportFieldView;
import com.pragmaticds.docengine.extraction.web.PackageExportView.ExportUnassignedPageView;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.pii.MaskableValue;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The package export: documents, page numbers, and extracted fields in one consumer-facing
 * payload. Missing fields are included with nulls and MANUAL_REVIEW_REQUIRED — a missing field
 * is a result, not an absence.
 *
 * <p>Tenancy: the package itself is org-guarded with {@code findByIdAndOrgIdAndDeletedAtIsNull} —
 * a cross-tenant id answers 404 exactly like a nonexistent one. Everything below travels through
 * {@code @TenantId}-filtered derived queries.
 */
@RestController
public class PackageExportController {

    private final PackageRefRepository packages;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final PageRepository pages;
    private final ExtractedFieldRepository fields;
    private final FieldEvidenceRepository evidence;
    private final FieldGroupKinds groupKinds;
    private final FieldTextProvenance textProvenance;
    /** Read-time overlay of human corrections (implemented in :review). Optional by design. */
    private final ObjectProvider<FieldOverlayPort> overlay;
    private final AuditService audit;

    public PackageExportController(
            PackageRefRepository packages,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            PageRepository pages,
            ExtractedFieldRepository fields,
            FieldEvidenceRepository evidence,
            FieldGroupKinds groupKinds,
            FieldTextProvenance textProvenance,
            ObjectProvider<FieldOverlayPort> overlay,
            AuditService audit) {
        this.packages = packages;
        this.documents = documents;
        this.links = links;
        this.pages = pages;
        this.fields = fields;
        this.evidence = evidence;
        this.groupKinds = groupKinds;
        this.textProvenance = textProvenance;
        this.overlay = overlay;
        this.audit = audit;
    }

    @GetMapping("/v1/packages/{id}/export")
    public PackageExportView get(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(id, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        List<Page> packagePages = pages.findByPackageIdOrderByPackagePageIndex(id);
        Map<UUID, Integer> pageIndexById =
                packagePages.stream().collect(Collectors.toMap(Page::getId, Page::getPackagePageIndex));

        List<LogicalDocument> packageDocuments = documents.findByPackageIdOrderByOrdinal(id);
        Map<UUID, List<LogicalDocumentPage>> linksByDocument =
                packageDocuments.isEmpty()
                        ? Map.of()
                        : links
                                .findByLogicalDocumentIdIn(
                                        packageDocuments.stream().map(LogicalDocument::getId).toList())
                                .stream()
                                .collect(Collectors.groupingBy(LogicalDocumentPage::getLogicalDocumentId));
        Map<UUID, List<ExtractedField>> fieldsByDocument =
                packageDocuments.isEmpty()
                        ? Map.of()
                        : fields
                                .findByLogicalDocumentIdInAndCurrentTrue(
                                        packageDocuments.stream().map(LogicalDocument::getId).toList())
                                .stream()
                                .collect(Collectors.groupingBy(ExtractedField::getLogicalDocumentId));
        List<ExtractedField> allFields =
                fieldsByDocument.values().stream().flatMap(List::stream).toList();
        // Layer-4 overlay: a corrected field exports its corrected value; the machine's Layer-2
        // value stays in extracted_field (and is exposed through the field-history strip).
        Map<UUID, String> effectiveValues = effectiveValues(allFields);
        // One lookup for every producing schema the package's rows cite (design 5b D10): the kind
        // is DERIVED from the schema, not stored on the row, so an export cannot report a grouping
        // the schema never declared.
        FieldGroupKinds.Lookup kinds =
                groupKinds.forSchemas(allFields.stream().map(ExtractedField::getSchemaId).toList());
        Map<UUID, List<FieldEvidence>> valueEvidenceByField =
                allFields.isEmpty()
                        ? Map.of()
                        : evidence
                                .findByExtractedFieldIdInOrderByExtractedFieldIdAscRoleAscOrdinalAsc(
                                        allFields.stream().map(ExtractedField::getId).toList())
                                .stream()
                                .filter(row -> FieldEvidence.ROLE_VALUE.equals(row.getRole()))
                                .collect(Collectors.groupingBy(FieldEvidence::getExtractedFieldId));
        // One span lookup for the WHOLE package — chunked inside, because a large package's VALUE
        // evidence can cite far more spans than a single statement may bind.
        FieldTextProvenance.Lookup provenance =
                textProvenance.forSpans(
                        valueEvidenceByField.values().stream()
                                .flatMap(List::stream)
                                .map(FieldEvidence::getTextSpanId)
                                .filter(java.util.Objects::nonNull)
                                .toList());

        List<ExportDocumentView> documentViews = new ArrayList<>();
        for (LogicalDocument document : packageDocuments) {
            List<Integer> pageNumbers =
                    linksByDocument.getOrDefault(document.getId(), List.of()).stream()
                            .sorted(Comparator.comparingInt(LogicalDocumentPage::getOrdinal))
                            .map(link -> pageIndexById.getOrDefault(link.getPageId(), -2) + 1)
                            .toList();
            List<ExportFieldView> fieldViews =
                    fieldsByDocument.getOrDefault(document.getId(), List.of()).stream()
                            // Field name, then the occurrence's own key. The batch fetch above is
                            // UNORDERED, so without the tiebreak three same-named rows exported in
                            // whatever order the scan returned them and "property A" was a race.
                            // nullsFirst matches the fields endpoint's ORDER BY exactly, so the
                            // two read paths cannot disagree about where an unkeyed occurrence
                            // sits; an ungrouped field's single row is where it has always been.
                            .sorted(
                                    Comparator.comparing(ExtractedField::getFieldName)
                                            .thenComparing(
                                                    ExtractedField::getGroupKey,
                                                    Comparator.nullsFirst(
                                                            Comparator.naturalOrder())))
                            .map(
                                    field ->
                                            toFieldView(
                                                    field,
                                                    valueEvidenceByField.getOrDefault(
                                                            field.getId(), List.of()),
                                                    pageIndexById,
                                                    effectiveValues.get(field.getId()),
                                                    kinds,
                                                    provenance))
                            .toList();
            documentViews.add(
                    new ExportDocumentView(
                            document.getId(),
                            document.getOrdinal(),
                            document.getDocumentTypeCode(),
                            document.getClassificationConfidence(),
                            document.getReviewStatus(),
                            document.getBoundaryProvenance(),
                            document.getAbsorbedUntypedPages(),
                            pageNumbers,
                            fieldViews));
        }

        // Same rule as the documents endpoint (audit C5): a page that is neither blank nor
        // duplicate and in NO document is a CLEARED page — its verdict was overridden and no
        // regroup has assigned it yet — and an export that omits it exports a package with a
        // page missing from every list. Gated on the split having produced documents, so an
        // in-flight package does not export its whole page set as reviewer work.
        java.util.Set<UUID> assignedPageIds =
                linksByDocument.values().stream()
                        .flatMap(List::stream)
                        .map(LogicalDocumentPage::getPageId)
                        .collect(Collectors.toSet());
        boolean splitHasRun = !packageDocuments.isEmpty();
        List<ExportUnassignedPageView> unassigned =
                packagePages.stream()
                        .filter(
                                page ->
                                        page.isBlank()
                                                || page.getDuplicateOfPageId() != null
                                                || (splitHasRun
                                                        && !assignedPageIds.contains(page.getId())))
                        .map(
                                page ->
                                        new ExportUnassignedPageView(
                                                page.getId(),
                                                page.getPackagePageIndex() + 1,
                                                page.isBlank()
                                                        ? "BLANK"
                                                        : page.getDuplicateOfPageId() != null
                                                                ? "DUPLICATE"
                                                                : "CLEARED"))
                        .toList();

        // Auditable read of a consumer-facing payload (docs/DATA_MODEL.md 7). PII-free metadata:
        // counts only, never a value.
        audit.record(
                AuditEvent.ACTION_EXPORT_GENERATED,
                "DOCUMENT_PACKAGE",
                id,
                Map.of("documents", documentViews.size(), "corrections", effectiveValues.size()));

        return new PackageExportView(id, List.copyOf(documentViews), unassigned);
    }

    /**
     * The human-corrected effective value per field, keyed only by corrected fields. Empty when
     * no overlay implementation is on the classpath ({@code :review} absent).
     */
    private Map<UUID, String> effectiveValues(List<ExtractedField> allFields) {
        FieldOverlayPort port = overlay.getIfAvailable();
        if (port == null || allFields.isEmpty()) {
            return Map.of();
        }
        return port.effectiveValues(allFields.stream().map(ExtractedField::getId).toList());
    }

    private ExportFieldView toFieldView(
            ExtractedField field,
            List<FieldEvidence> valueEvidence,
            Map<UUID, Integer> pageIndexById,
            String correctedValue,
            FieldGroupKinds.Lookup kinds,
            FieldTextProvenance.Lookup provenance) {
        Integer pageNumber =
                valueEvidence.isEmpty()
                        ? null
                        : pageIndexById.getOrDefault(valueEvidence.get(0).getPageId(), -2) + 1;
        // The effective displayed value: the correction if present, else the machine's value.
        // raw_value stays the machine capture, so the payload carries both.
        String displayed = correctedValue != null ? correctedValue : field.getDisplayedText();
        // The TYPED arm must follow the correction too, re-derived from the corrected string by the
        // field's data type — otherwise a consumer books the stale machine number beside a corrected
        // display (Phase 7b review finding). rawValue above still preserves the machine capture.
        Object normalized =
                correctedValue != null
                        ? CorrectedTypedValue.of(field.getDataType(), correctedValue).exportArm()
                        : normalizedValueOf(field);
        boolean sensitive = field.isSensitive();
        return new ExportFieldView(
                field.getFieldName(),
                field.getGroupKey(),
                kinds.kindOf(field.getSchemaId(), field.getFieldName()),
                // The spans this VALUE was captured from — the same VALUE-only rule the fields
                // endpoint applies, so the two surfaces cannot disagree about one occurrence.
                provenance.of(
                        valueEvidence.stream()
                                .map(FieldEvidence::getTextSpanId)
                                .filter(java.util.Objects::nonNull)
                                .toList()),
                MaskableValue.of(field.getRawValue(), sensitive),
                MaskableValue.of(normalized, sensitive),
                MaskableValue.of(displayed, sensitive),
                pageNumber,
                unionBox(valueEvidence),
                field.getExtractionMethod(),
                field.getConfidence(),
                field.getValidationStatus(),
                field.getReviewStatus(),
                // D11's verdict on the SERVED value, from the same one-place derivation as
                // /fields: correctedValue is the overlay's entry for this row, so the two read
                // paths cannot disagree about whose value an occurrence is serving.
                EffectiveStatus.of(field.getReviewStatus(), correctedValue != null));
    }

    /** The populated normalized arm: string, number, or ISO-8601 date string. Null when missing. */
    private static Object normalizedValueOf(ExtractedField field) {
        if (field.getNormalizedText() != null) {
            return field.getNormalizedText();
        }
        if (field.getNormalizedNumber() != null) {
            return field.getNormalizedNumber();
        }
        if (field.getNormalizedDate() != null) {
            return field.getNormalizedDate().toString();
        }
        return null;
    }

    /** UNION of the field's VALUE evidence boxes; null when the field is missing. */
    private static BoundingBoxView unionBox(List<FieldEvidence> valueEvidence) {
        if (valueEvidence.isEmpty()) {
            return null;
        }
        BigDecimal minX = null;
        BigDecimal minY = null;
        BigDecimal maxRight = null;
        BigDecimal maxBottom = null;
        for (FieldEvidence row : valueEvidence) {
            BigDecimal right = row.getX().add(row.getWidth());
            BigDecimal bottom = row.getY().add(row.getHeight());
            minX = minX == null ? row.getX() : minX.min(row.getX());
            minY = minY == null ? row.getY() : minY.min(row.getY());
            maxRight = maxRight == null ? right : maxRight.max(right);
            maxBottom = maxBottom == null ? bottom : maxBottom.max(bottom);
        }
        return new BoundingBoxView(
                minX, minY, maxRight.subtract(minX), maxBottom.subtract(minY));
    }
}
