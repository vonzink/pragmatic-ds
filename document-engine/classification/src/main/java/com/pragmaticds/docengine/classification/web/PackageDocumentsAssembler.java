package com.pragmaticds.docengine.classification.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.classification.web.PackageDocumentsView.DocumentPageView;
import com.pragmaticds.docengine.classification.web.PackageDocumentsView.DocumentView;
import com.pragmaticds.docengine.classification.web.PackageDocumentsView.PageClassificationView;
import com.pragmaticds.docengine.classification.web.PackageDocumentsView.UnassignedPageView;
import com.pragmaticds.docengine.classification.web.PackageDocumentsView.UnassignedReason;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the split projection ({@code PackageDocumentsView}) for a package — its logical documents
 * with their pages and per-page classifications, plus the pages that stayed unassigned and WHY
 * (blank or duplicate). Factored out of {@code PackageDocumentsController} so the read model has ONE
 * assembly path: the GET endpoint returns {@link #build}, and {@code RegroupService} both snapshots
 * the grouping ({@link #groupingJson}, the decision's previous/new value) and returns the updated
 * view after a regroup.
 *
 * <p>Tenancy: {@link #build} org-guards the package with {@code findByIdAndOrgIdAndDeletedAtIsNull}
 * — a cross-tenant or soft-deleted id answers 404 exactly like a nonexistent one. Everything below
 * the guard travels through {@code @TenantId}-filtered derived queries.
 */
@Component
public class PackageDocumentsAssembler {

    private final PackageRefRepository packages;
    private final PageRepository pages;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final ClassificationResultRepository results;
    private final ObjectMapper mapper = new ObjectMapper();

    public PackageDocumentsAssembler(
            PackageRefRepository packages,
            PageRepository pages,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            ClassificationResultRepository results) {
        this.packages = packages;
        this.pages = pages;
        this.documents = documents;
        this.links = links;
        this.results = results;
    }

    /** The full {@code GET /v1/packages/{id}/documents} projection. Org-guards the package first. */
    public PackageDocumentsView build(UUID packageId, UUID orgId) {
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(packageId, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        List<Page> packagePages = pages.findByPackageIdOrderByPackagePageIndex(packageId);
        Map<UUID, Page> pagesById =
                packagePages.stream().collect(Collectors.toMap(Page::getId, Function.identity()));
        Map<UUID, ClassificationResult> currentByPage =
                results
                        .findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                                ClassificationResult.SUBJECT_PAGE,
                                packagePages.stream().map(Page::getId).toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        ClassificationResult::getSubjectId, Function.identity()));

        List<LogicalDocument> packageDocuments = documents.findByPackageIdOrderByOrdinal(packageId);
        Map<UUID, List<LogicalDocumentPage>> linksByDocument = linksByDocument(packageDocuments);

        List<DocumentView> documentViews = new ArrayList<>();
        for (LogicalDocument document : packageDocuments) {
            List<DocumentPageView> pageViews =
                    linksByDocument.getOrDefault(document.getId(), List.of()).stream()
                            .sorted(Comparator.comparingInt(LogicalDocumentPage::getOrdinal))
                            .map(
                                    link -> {
                                        Page page = pagesById.get(link.getPageId());
                                        ClassificationResult current =
                                                currentByPage.get(link.getPageId());
                                        return new DocumentPageView(
                                                link.getPageId(),
                                                page == null ? -1 : page.getPackagePageIndex(),
                                                current == null
                                                        ? null
                                                        : new PageClassificationView(
                                                                current.getDocumentTypeCode(),
                                                                current.getConfidence(),
                                                                current.getRulePackVersion(),
                                                                coQualifyingTypes(
                                                                        current.getEvidence())));
                                    })
                            .toList();
            documentViews.add(
                    new DocumentView(
                            document.getId(),
                            document.getOrdinal(),
                            document.getDocumentTypeCode(),
                            document.getClassificationConfidence(),
                            document.getReviewStatus(),
                            document.getBoundaryProvenance(),
                            document.getAbsorbedUntypedPages(),
                            pageViews));
        }

        // Every page a document holds — the complement defines the tray. A page that is neither
        // blank nor duplicate AND in no document is a CLEARED page (its verdict was overridden,
        // no regroup has assigned it yet) and must be listed, or it is reachable from nowhere
        // (audit C5). Gated on the split having produced documents at all: before SPLITTING runs
        // nothing is linked, and listing every page as CLEARED would misreport an in-flight
        // package as a tray full of reviewer work.
        java.util.Set<UUID> assignedPageIds =
                linksByDocument.values().stream()
                        .flatMap(List::stream)
                        .map(LogicalDocumentPage::getPageId)
                        .collect(Collectors.toSet());
        boolean splitHasRun = !packageDocuments.isEmpty();
        List<UnassignedPageView> unassigned =
                packagePages.stream()
                        .filter(
                                page ->
                                        page.isBlank()
                                                || page.getDuplicateOfPageId() != null
                                                || (splitHasRun
                                                        && !assignedPageIds.contains(page.getId())))
                        .map(
                                page ->
                                        new UnassignedPageView(
                                                page.getId(),
                                                page.getPackagePageIndex(),
                                                page.isBlank()
                                                        ? UnassignedReason.BLANK
                                                        : page.getDuplicateOfPageId() != null
                                                                ? UnassignedReason.DUPLICATE
                                                                : UnassignedReason.CLEARED))
                        .toList();

        return new PackageDocumentsView(packageId, List.copyOf(documentViews), unassigned);
    }

    /**
     * The grouping snapshot the regroup decision stores as {@code previous_value}/{@code new_value}:
     * each document's id, type, confidence, and its member page ids in membership order, documents in
     * ordinal order. Ids, codes, and confidences only — never page content — so it is safe to store
     * verbatim in the audit trail. No package guard: the caller ({@code RegroupService}) has already
     * org-guarded and is inside its transaction.
     */
    public String groupingJson(UUID packageId, UUID orgId) {
        List<LogicalDocument> packageDocuments = documents.findByPackageIdOrderByOrdinal(packageId);
        Map<UUID, List<LogicalDocumentPage>> linksByDocument = linksByDocument(packageDocuments);

        List<Map<String, Object>> docs = new ArrayList<>();
        for (LogicalDocument document : packageDocuments) {
            List<String> pageIds =
                    linksByDocument.getOrDefault(document.getId(), List.of()).stream()
                            .sorted(Comparator.comparingInt(LogicalDocumentPage::getOrdinal))
                            .map(link -> link.getPageId().toString())
                            .toList();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", document.getId().toString());
            doc.put("documentTypeCode", document.getDocumentTypeCode());
            doc.put("classificationConfidence", document.getClassificationConfidence());
            // In the snapshot because a regroup OVERWRITES it: after the reviewer acts, every
            // document they touched reads HUMAN, and without this the prior state would no longer
            // record whether the machine had PROVEN that boundary with a form anchor or merely
            // inferred it from a type change. An append-only decision that cannot answer "what did
            // I overrule" is a weaker audit trail than the one this table promises.
            doc.put("boundaryProvenance", document.getBoundaryProvenance());
            // Same reason: a regroup nulls it, and "what did I overrule" includes how many of
            // the document's pages the machine had merely absorbed rather than typed.
            doc.put("absorbedUntypedPages", document.getAbsorbedUntypedPages());
            doc.put("pageIds", pageIds);
            docs.add(doc);
        }
        try {
            return mapper.writeValueAsString(Map.of("documents", docs));
        } catch (JsonProcessingException e) {
            // ids/codes/confidences only — never page content — so a failure is a bug, not a leak.
            throw new IllegalStateException("grouping snapshot is not serialisable");
        }
    }

    /**
     * The suspected multi-document sheet, read off the classification evidence this method already
     * has in hand — no extra query, the same trick {@code PackageSplitter} uses for form
     * boundaries.
     *
     * <p>Evidence is DERIVED data. Absent, blank or unparseable yields an empty list: no
     * suspicion is claimed and the page reads exactly as it did before Phase B. It never fails the
     * request and never invents a second document it cannot point at.
     */
    private List<String> coQualifyingTypes(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> types = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode type :
                    mapper.readTree(evidenceJson).path("coQualifyingTypes")) {
                types.add(type.asText());
            }
            return List.copyOf(types);
        } catch (JsonProcessingException e) {
            // Type codes only, never document content — the same rule the splitter follows for
            // unreadable evidence.
            return List.of();
        }
    }

    private Map<UUID, List<LogicalDocumentPage>> linksByDocument(
            List<LogicalDocument> packageDocuments) {
        return packageDocuments.isEmpty()
                ? Map.of()
                : links
                        .findByLogicalDocumentIdIn(
                                packageDocuments.stream().map(LogicalDocument::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(LogicalDocumentPage::getLogicalDocumentId));
    }
}
