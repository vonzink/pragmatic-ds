package com.pragmaticds.docengine.classification.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.classification.web.PackageClassificationView.PageClassificationEvidenceView;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The classification-evidence read surface. {@code POST /v1/documents/{id}/classification} is the
 * RECLASSIFY override (a write); until this endpoint the evidence a classification stored had no
 * read path at all. The corpus scorer ({@code tools/corpus_score.py}) is the first consumer.
 *
 * <p>Pages with no CURRENT result (CLASSIFYING has not judged them) contribute no entry — absent,
 * not null-padded. No assembler class: unlike the documents view (shared with RegroupService),
 * this projection has exactly one consumer, so the assembly lives here until a second appears.
 *
 * <p>Tenancy: org-guarded with {@code findByIdAndOrgIdAndDeletedAtIsNull} — a cross-tenant or
 * soft-deleted id answers 404 exactly like a nonexistent one (the PackageDocumentsController
 * pattern). Everything below the guard travels through {@code @TenantId}-filtered queries.
 */
@RestController
public class PackageClassificationController {

    private final PackageRefRepository packages;
    private final PageRepository pages;
    private final ClassificationResultRepository results;
    private final ObjectMapper mapper = new ObjectMapper();

    public PackageClassificationController(
            PackageRefRepository packages,
            PageRepository pages,
            ClassificationResultRepository results) {
        this.packages = packages;
        this.pages = pages;
        this.results = results;
    }

    @GetMapping("/v1/packages/{id}/classification")
    public PackageClassificationView get(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(id, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        List<Page> packagePages = pages.findByPackageIdOrderByPackagePageIndex(id);
        Map<UUID, ClassificationResult> currentByPage =
                results
                        .findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                                ClassificationResult.SUBJECT_PAGE,
                                packagePages.stream().map(Page::getId).toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        ClassificationResult::getSubjectId, Function.identity()));

        List<PageClassificationEvidenceView> views = new ArrayList<>();
        for (Page page : packagePages) {
            ClassificationResult current = currentByPage.get(page.getId());
            if (current == null) {
                continue;
            }
            views.add(
                    new PageClassificationEvidenceView(
                            page.getId(),
                            page.getPackagePageIndex(),
                            current.getDocumentTypeCode(),
                            current.getConfidence(),
                            current.getRulePackVersion(),
                            evidenceNode(current.getEvidence())));
        }
        return new PackageClassificationView(id, List.copyOf(views));
    }

    /** The stored jsonb re-emitted verbatim as JSON — never re-shaped, never a quoted string. */
    private JsonNode evidenceNode(String evidence) {
        try {
            return mapper.readTree(evidence);
        } catch (JsonProcessingException e) {
            // The column is jsonb — unparseable content is a bug, not a request error.
            throw new IllegalStateException("stored classification evidence is not JSON", e);
        }
    }
}
