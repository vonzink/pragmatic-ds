package com.pragmaticds.docengine.review.triage;

import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.TriageDocument;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.TriageItem;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.TriagePage;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.platform.security.Role;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import com.pragmaticds.docengine.review.triage.PackageTriageView.LabelView;
import com.pragmaticds.docengine.review.triage.PackageTriageView.TriageItemView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The unknown-document triage queue: a READ-TIME projection over rows the engine already writes.
 *
 * <p><b>Why it lives in {@code :review} and not {@code :classification}.</b> A queue item is only
 * half the story without the human answer beside it, and human answers are {@code review_decision}
 * rows. {@code :classification} cannot see {@code :review} (that is a cycle — {@code :review}
 * already depends on {@code :classification}), so the LOADING and the label JOIN live here, exactly
 * the argument {@code RegroupService}'s build file makes for hosting the regroup primitive on this
 * side of the boundary. The grouping RULE stays pure and testable in
 * {@link UnknownTriagePlanner}.
 *
 * <p><b>Package-scoped, not org-wide.</b> Every read is tenant-scoped — {@code @TenantId} filtering
 * plus RLS — and a cross-tenant package id answers 404 like a nonexistent one. It is deliberately
 * addressed per package rather than as one org-wide inbox: an org-wide queue means scanning every
 * package's pages and parsing every UNKNOWN page's evidence on each call, which wants either
 * pagination over a new index or a materialized projection. The projection would be a table, and a
 * table is a migration; this pass is a read-time surface by design. §8 of the design doc records
 * the deferral.
 *
 * <p><b>REVIEWER, enforced in code.</b> The central RBAC matrix gates {@code GET /v1/**} at
 * READONLY, and triage is not a read-only consumer's business: it is a worklist that exists to be
 * acted on, and every item names pages a reviewer is expected to label. {@code Role.includes} is
 * documented as the sanctioned mechanism for exactly this ("this helper exists for in-code
 * checks"). The matrix stays the coarse gate; this is the narrower one. See the design doc §6 for
 * the matrix line that should eventually spell the same rule out declaratively.
 */
@Service
public class UnknownTriageService {

    private final PackageRefRepository packages;
    private final PageRepository pages;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final ClassificationResultRepository results;
    private final ReviewDecisionRepository decisions;
    private final BigDecimal nearMissMargin;

    public UnknownTriageService(
            PackageRefRepository packages,
            PageRepository pages,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            ClassificationResultRepository results,
            ReviewDecisionRepository decisions,
            /*
             * A GUESS until a corpus run measures it, so it is configuration rather than a
             * constant — the same call BoundaryWindowPlanner's javadoc makes about its own N and
             * confidence floor. 0.10 says "within ten points of its own bar"; on the seeded packs
             * (thresholds 0.6-0.8) that is roughly one mid-weight anchor's worth of score, which is
             * the distance a pack fix actually closes.
             */
            @Value("${docengine.triage.near-miss-margin:0.10}") BigDecimal nearMissMargin) {
        this.packages = packages;
        this.pages = pages;
        this.documents = documents;
        this.links = links;
        this.results = results;
        this.decisions = decisions;
        this.nearMissMargin = nearMissMargin;
    }

    /** The queue for one package. */
    public PackageTriageView triage(UUID packageId) {
        AuthPrincipal principal = requireReviewer();
        // The org-scoped load IS the access check: a cross-tenant or soft-deleted package is
        // absent (404), never 403, so another org's ids are never confirmed to exist.
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(packageId, principal.orgId())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        List<LogicalDocument> packageDocuments =
                documents.findByPackageIdOrderByOrdinal(packageId);
        if (packageDocuments.isEmpty()) {
            // SPLITTING has not run (or produced nothing). There is no absorption to report yet,
            // and reporting every page as untriaged work would misread an in-flight package as a
            // reviewer's backlog — the same trap PackageDocumentsAssembler's splitHasRun gate
            // avoids for the unassigned tray.
            return new PackageTriageView(packageId, List.of());
        }

        Map<UUID, Integer> pageIndexById =
                pages.findByPackageIdOrderByPackagePageIndex(packageId).stream()
                        .collect(Collectors.toMap(Page::getId, Page::getPackagePageIndex));
        Map<UUID, List<LogicalDocumentPage>> linksByDocument =
                links
                        .findByLogicalDocumentIdIn(
                                packageDocuments.stream().map(LogicalDocument::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(LogicalDocumentPage::getLogicalDocumentId));
        Map<UUID, ClassificationResult> currentByPage =
                currentClassifications(
                        linksByDocument.values().stream()
                                .flatMap(List::stream)
                                .map(LogicalDocumentPage::getPageId)
                                .collect(Collectors.toCollection(LinkedHashSet::new)));

        List<TriageDocument> planned = new ArrayList<>();
        for (LogicalDocument document : packageDocuments) {
            List<TriagePage> memberPages =
                    linksByDocument.getOrDefault(document.getId(), List.of()).stream()
                            .sorted(Comparator.comparingInt(LogicalDocumentPage::getOrdinal))
                            .map(
                                    link -> {
                                        ClassificationResult current =
                                                currentByPage.get(link.getPageId());
                                        return new TriagePage(
                                                link.getPageId(),
                                                // -1 for a link whose page vanished: impossible
                                                // through the engine's own paths, and a crash here
                                                // would take out the whole queue for one bad row.
                                                pageIndexById.getOrDefault(link.getPageId(), -1),
                                                current == null
                                                        ? null
                                                        : current.getDocumentTypeCode(),
                                                current == null ? null : current.getEvidence());
                                    })
                            .toList();
            planned.add(
                    new TriageDocument(
                            document.getId(),
                            document.getOrdinal(),
                            document.getDocumentTypeCode(),
                            memberPages));
        }

        List<TriageItem> items = UnknownTriagePlanner.plan(planned, nearMissMargin);
        Map<UUID, List<ReviewDecision>> labelsByDocument = labelsByDocument(items);

        List<TriageItemView> views =
                items.stream()
                        .map(item -> TriageItemView.of(item, labelFor(item, labelsByDocument)))
                        .sorted(
                                // Stable: everything the planner ordered stays ordered, and
                                // ANSWERED work sinks below open work.
                                Comparator.comparing(
                                        (TriageItemView view) -> view.label() != null))
                        .toList();
        return new PackageTriageView(packageId, List.copyOf(views));
    }

    /**
     * Current PAGE classifications, batched. Chunked because the {@code IN} list is one entry per
     * page and a large package would otherwise build a single enormous statement — the same reason
     * every other batched read in the engine takes ids in hand rather than per row.
     */
    private Map<UUID, ClassificationResult> currentClassifications(Set<UUID> pageIds) {
        Map<UUID, ClassificationResult> byPage = new HashMap<>();
        List<UUID> ids = List.copyOf(pageIds);
        int chunk = 500;
        for (int start = 0; start < ids.size(); start += chunk) {
            for (ClassificationResult result :
                    results.findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                            ClassificationResult.SUBJECT_PAGE,
                            ids.subList(start, Math.min(start + chunk, ids.size())))) {
                byPage.put(result.getSubjectId(), result);
            }
        }
        return byPage;
    }

    private Map<UUID, List<ReviewDecision>> labelsByDocument(List<TriageItem> items) {
        List<UUID> documentIds =
                items.stream().map(TriageItem::logicalDocumentId).distinct().toList();
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return decisions
                .findBySubjectTypeAndSubjectIdInAndActionOrderByDecidedAtDesc(
                        ReviewDecision.SUBJECT_CLASSIFICATION,
                        documentIds,
                        ReviewDecision.ACTION_RECLASSIFY)
                .stream()
                .collect(
                        Collectors.groupingBy(
                                ReviewDecision::getSubjectId,
                                Collectors.toCollection(ArrayList::new)));
    }

    /**
     * The latest label whose page set is EXACTLY this run's.
     *
     * <p>Set equality, not overlap: several runs of one document share a subject id (the document),
     * so overlap would let a label for pages 4-5 also claim pages 4-9, and the queue would report
     * work as answered that nobody answered. Exact match says only what the reviewer actually said.
     * A re-split that changes a run's page membership therefore correctly re-opens it — the human
     * labelled a run that no longer exists.
     */
    private LabelView labelFor(TriageItem item, Map<UUID, List<ReviewDecision>> labelsByDocument) {
        Set<UUID> runPages = new HashSet<>(item.pageIds());
        for (ReviewDecision decision :
                labelsByDocument.getOrDefault(item.logicalDocumentId(), List.of())) {
            if (runPages.equals(new HashSet<>(TriageLabelJson.pageIds(decision.getNewValue())))) {
                return new LabelView(
                        decision.getId(),
                        com.pragmaticds.docengine.review.ReviewJson.read(
                                decision.getNewValue(), "documentTypeCode"),
                        decision.getReason(),
                        decision.getDecidedBy(),
                        decision.getDecidedAt());
            }
        }
        return null;
    }

    /**
     * Triage is a REVIEWER worklist. An API key carries scopes and no role, so it never passes
     * here — deliberately: a machine consumer that wants classification evidence has
     * {@code GET /v1/packages/{id}/classification}, which serves the raw evidence and asks nothing
     * of a human.
     */
    private AuthPrincipal requireReviewer() {
        AuthPrincipal principal = AuthContext.require();
        if (!principal.hasRole(Role.REVIEWER)) {
            throw new DomainException(ErrorCode.FORBIDDEN_DOCUMENT, 403);
        }
        return principal;
    }
}
