package com.pragmaticds.docengine.review.regroup;

import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.DocumentTypeRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.classification.web.PackageDocumentsAssembler;
import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.review.ReviewJson;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import com.pragmaticds.docengine.review.regroup.RegroupRequest.Move;
import com.pragmaticds.docengine.review.regroup.RegroupRequest.NewDocument;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The regroup primitive (Spec 2 design §4-8): one endpoint applies a reviewer's membership delta —
 * moves, new documents, deletions — as ONE validated transaction, records it as an append-only
 * {@code review_decision}, marks every reshaped document human-shaped (null confidence, IN_REVIEW),
 * and re-kicks the EXTRACTING stage so the affected fields refresh.
 *
 * <p>Lives in {@code :review}, not {@code :classification}, because it BOTH edits
 * {@code logical_document_page} (classification's tables) AND writes a {@code review_decision}
 * (review's table). {@code :review} already depends on {@code :classification}; the reverse would be
 * a Gradle cycle. It sits beside {@link com.pragmaticds.docengine.review.ReviewDecisionService} — the same
 * shape (principal + org guard + append-only decision + audit), one grouping level up.
 *
 * <p>Validation rejects as a whole (design §6): any failure aborts the transaction, so nothing is
 * partially applied.
 */
@Service
public class RegroupService {

    private final PackageRefRepository packages;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository memberships;
    private final PageRepository pages;
    private final DocumentTypeRepository documentTypes;
    private final ReviewDecisionRepository decisions;
    private final AuditService audit;
    private final JobService jobs;
    private final PackageDocumentsAssembler view;

    public RegroupService(
            PackageRefRepository packages,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository memberships,
            PageRepository pages,
            DocumentTypeRepository documentTypes,
            ReviewDecisionRepository decisions,
            AuditService audit,
            JobService jobs,
            PackageDocumentsAssembler view) {
        this.packages = packages;
        this.documents = documents;
        this.memberships = memberships;
        this.pages = pages;
        this.documentTypes = documentTypes;
        this.decisions = decisions;
        this.audit = audit;
        this.jobs = jobs;
        this.view = view;
    }

    @Transactional
    public RegroupResult regroup(UUID packageId, RegroupRequest req) {
        AuthPrincipal principal = AuthContext.require();
        UUID orgId = principal.orgId();
        if (principal.userId() == null) {
            // decided_by is NOT NULL — only a human may make a regroup decision.
            throw new DomainException(ErrorCode.FORBIDDEN_DOCUMENT, 403);
        }
        // Org guard + tombstone guard: a cross-tenant or soft-deleted package is 404 (Phase 7c).
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(packageId, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        // 1. Claim the job row FIRST — before any logical_document write (#71). Every writer on a
        // package now takes the two in one order, job row then documents, so a concurrent
        // per-document AI re-run (which holds the job row while inserting extracted_field rows,
        // whose FK takes FOR KEY SHARE on the parent document) can only WAIT here, never cycle.
        // Claiming after the DELETE in step 5 is what made the two orders ABBA: Postgres broke the
        // cycle by killing one side with 40P01, so a legitimate pair of concurrent actions cost a
        // reviewer a 500. Dispatch still defers to afterCommit, so the re-kick runs after commit.
        jobs.reExtract(packageId);

        // 2. Snapshot the prior grouping for previous_value.
        String previous = view.groupingJson(packageId, orgId);

        // 3. Create the new documents (human-shaped from birth), wiring tempId -> real id.
        Map<String, UUID> tempIds = new HashMap<>();
        int ordinal = nextDocumentOrdinal(packageId);
        for (NewDocument nd : nullToEmpty(req.newDocuments())) {
            requireKnownType(orgId, nd.documentTypeCode());
            LogicalDocument doc =
                    new LogicalDocument(
                            packageId,
                            ordinal++,
                            nd.documentTypeCode(),
                            null,
                            LogicalDocument.BOUNDARY_HUMAN);
            doc.humanRegroup(nd.documentTypeCode()); // IN_REVIEW + null confidence + HUMAN boundary
            documents.save(doc);
            if (nd.tempId() != null && !nd.tempId().isBlank()) {
                tempIds.put(nd.tempId(), doc.getId());
            }
        }
        Set<UUID> newDocIds = new HashSet<>(tempIds.values());

        // 4. Apply moves + new-doc assignments as a single desired membership.
        Set<UUID> affectedExisting = applyMembership(packageId, orgId, req, tempIds, newDocIds);

        // 5. Delete the requested documents (each must already be empty).
        deleteRequestedDocuments(packageId, orgId, req);

        // 6. Validate the resulting grouping as a whole.
        validateResultingGrouping(packageId, req);

        // 7. Mark the reshaped EXISTING documents human-shaped (the new ones already are).
        markAffectedHumanShaped(orgId, affectedExisting);

        // 8. Append the decision + audit (counts/ids only — PII-free).
        decisions.save(
                new ReviewDecision(
                        ReviewDecision.SUBJECT_LOGICAL_DOCUMENT,
                        packageId, // package-wide regroup: subject_id is the package, not one doc
                        ReviewDecision.ACTION_REGROUP,
                        ReviewJson.raw(previous),
                        ReviewJson.raw(view.groupingJson(packageId, orgId)),
                        req.reason(),
                        principal.userId()));
        audit.record(
                AuditEvent.ACTION_DOCUMENT_REGROUPED,
                ReviewDecision.SUBJECT_LOGICAL_DOCUMENT,
                packageId,
                Map.of(
                        "intent", safeIntent(req.intent()),
                        "moved", nullToEmpty(req.moves()).size(),
                        "created", nullToEmpty(req.newDocuments()).size(),
                        "deleted", nullToEmpty(req.deletedDocumentIds()).size()));

        return new RegroupResult(view.build(packageId, orgId));
    }

    /**
     * Applies the desired membership: unassign = delete the row; assign = relocate the page to the
     * resolved target (delete any current row, then insert with a fresh ordinal). Returns the set of
     * EXISTING documents whose membership changed (prior owners that lost a page, existing targets
     * that gained one) — the ones to re-shape as human. New documents are excluded (already shaped).
     */
    private Set<UUID> applyMembership(
            UUID packageId,
            UUID orgId,
            RegroupRequest req,
            Map<String, UUID> tempIds,
            Set<UUID> newDocIds) {
        // Build the assignment map (pageId -> target doc) and the unassign set.
        Map<UUID, UUID> assign = new LinkedHashMap<>();
        Set<UUID> unassign = new LinkedHashSet<>();
        for (Move move : nullToEmpty(req.moves())) {
            if (move.toDocumentId() == null) {
                unassign.add(move.pageId());
            } else {
                putAssignment(assign, move.pageId(), resolveTarget(move.toDocumentId(), tempIds));
            }
        }
        for (NewDocument nd : nullToEmpty(req.newDocuments())) {
            UUID target = tempIds.get(nd.tempId());
            for (UUID pageId : nullToEmpty(nd.pageIds())) {
                putAssignment(assign, pageId, target);
            }
        }
        // A page cannot be both assigned and unassigned in one call (design §6.3).
        for (UUID pageId : assign.keySet()) {
            if (unassign.contains(pageId)) {
                throw DomainException.conflict(
                        ErrorCode.CONFLICT, Map.of("reason", "PAGE_IN_TWO_DOCUMENTS"));
            }
        }

        // Validate every touched page belongs to the package+org; an assigned page must be
        // assignable (not a blank/duplicate that still carries its signal — design §6.1, §6.5).
        Set<UUID> touched = new LinkedHashSet<>();
        touched.addAll(assign.keySet());
        touched.addAll(unassign);
        for (UUID pageId : touched) {
            Page page =
                    pages.findByIdAndOrgId(pageId, orgId)
                            .filter(p -> packageId.equals(p.getPackageId()))
                            .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
            if (assign.containsKey(pageId)
                    && (page.isBlank() || page.getDuplicateOfPageId() != null)) {
                throw DomainException.conflict(
                        ErrorCode.PAGE_NOT_ASSIGNABLE, Map.of("pageId", pageId.toString()));
            }
        }

        // Every assignment target must be a document in this package (existing or just created).
        Set<UUID> packageDocIds = new HashSet<>();
        for (LogicalDocument doc : documents.findByPackageIdOrderByOrdinal(packageId)) {
            packageDocIds.add(doc.getId());
        }
        for (UUID target : assign.values()) {
            if (!packageDocIds.contains(target)) {
                throw DomainException.notFound(ErrorCode.NOT_FOUND);
            }
        }

        // Delete first (prior owners recorded), then FLUSH — so the inserts below cannot collide with
        // a soon-to-be-deleted row on the unique(page_id) index within one flush.
        Set<UUID> affected = new LinkedHashSet<>();
        for (UUID pageId : touched) {
            memberships
                    .findByPageIdAndOrgId(pageId, orgId)
                    .ifPresent(
                            row -> {
                                if (!newDocIds.contains(row.getLogicalDocumentId())) {
                                    affected.add(row.getLogicalDocumentId());
                                }
                                memberships.delete(row);
                            });
        }
        memberships.flush();

        // Insert the assignments with fresh per-document ordinals.
        Map<UUID, Integer> nextOrdinal = new HashMap<>();
        for (Map.Entry<UUID, UUID> entry : assign.entrySet()) {
            UUID target = entry.getValue();
            int ord = nextOrdinal.computeIfAbsent(target, this::nextMemberOrdinal);
            memberships.save(new LogicalDocumentPage(target, entry.getKey(), ord));
            nextOrdinal.put(target, ord + 1);
            if (!newDocIds.contains(target)) {
                affected.add(target);
            }
        }
        try {
            memberships.flush();
        } catch (DataIntegrityViolationException conflict) {
            // The unique(page_id) index caught a page landing in two documents (e.g. a concurrent
            // regroup). Reject the whole call — nothing persists.
            throw DomainException.conflict(
                    ErrorCode.CONFLICT, Map.of("reason", "DUPLICATE_PAGE_MEMBERSHIP"));
        }
        return affected;
    }

    private void deleteRequestedDocuments(UUID packageId, UUID orgId, RegroupRequest req) {
        for (UUID docId : nullToEmpty(req.deletedDocumentIds())) {
            LogicalDocument doc =
                    documents
                            .findByIdAndOrgId(docId, orgId)
                            .filter(d -> packageId.equals(d.getPackageId()))
                            .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
            if (!memberships.findByLogicalDocumentIdOrderByOrdinal(docId).isEmpty()) {
                // A deleted document must end empty (design §6.4).
                throw DomainException.conflict(
                        ErrorCode.CONFLICT,
                        Map.of("reason", "DELETED_DOCUMENT_NOT_EMPTY", "documentId", docId.toString()));
            }
            documents.delete(doc);
        }
        documents.flush();
    }

    private void validateResultingGrouping(UUID packageId, RegroupRequest req) {
        Set<UUID> deleted = new HashSet<>(nullToEmpty(req.deletedDocumentIds()));
        for (LogicalDocument doc : documents.findByPackageIdOrderByOrdinal(packageId)) {
            if (deleted.contains(doc.getId())) {
                continue;
            }
            if (memberships.findByLogicalDocumentIdOrderByOrdinal(doc.getId()).isEmpty()) {
                // No non-deleted document may be left with zero pages (design §6.4).
                throw DomainException.conflict(
                        ErrorCode.CONFLICT,
                        Map.of("reason", "EMPTY_DOCUMENT", "documentId", doc.getId().toString()));
            }
        }
        // Unique page membership is enforced by the DB unique(page_id) index — surfaced at flush as
        // DataIntegrityViolationException and mapped to 409 in applyMembership.
    }

    private void markAffectedHumanShaped(UUID orgId, Set<UUID> affectedExisting) {
        for (UUID docId : affectedExisting) {
            documents
                    .findByIdAndOrgId(docId, orgId)
                    .ifPresent(
                            doc -> {
                                // Type stays the reviewer's/machine's; confidence nulls, IN_REVIEW.
                                doc.humanRegroup(doc.getDocumentTypeCode());
                                documents.save(doc);
                            });
        }
    }

    private UUID resolveTarget(String toDocumentId, Map<String, UUID> tempIds) {
        UUID resolved = tempIds.get(toDocumentId);
        if (resolved != null) {
            return resolved;
        }
        try {
            return UUID.fromString(toDocumentId);
        } catch (IllegalArgumentException notAUuid) {
            // A tempId with no matching newDocument, or garbage — an unknown target is 404.
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }
    }

    private void putAssignment(Map<UUID, UUID> assign, UUID pageId, UUID target) {
        if (assign.putIfAbsent(pageId, target) != null) {
            // The same page assigned twice in one request (design §6.3).
            throw DomainException.conflict(
                    ErrorCode.CONFLICT, Map.of("reason", "PAGE_IN_TWO_DOCUMENTS"));
        }
    }

    private void requireKnownType(UUID orgId, String code) {
        if (code == null
                || code.isBlank()
                || documentTypes.findActiveVisibleTo(orgId).stream()
                        .noneMatch(type -> type.getCode().equals(code))) {
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "UNKNOWN_DOCUMENT_TYPE"));
        }
    }

    private int nextDocumentOrdinal(UUID packageId) {
        return documents.findByPackageIdOrderByOrdinal(packageId).stream()
                        .mapToInt(LogicalDocument::getOrdinal)
                        .max()
                        .orElse(-1)
                + 1;
    }

    private int nextMemberOrdinal(UUID documentId) {
        return memberships.findByLogicalDocumentIdOrderByOrdinal(documentId).stream()
                        .mapToInt(LogicalDocumentPage::getOrdinal)
                        .max()
                        .orElse(-1)
                + 1;
    }

    private static String safeIntent(String intent) {
        return intent == null || intent.isBlank() ? "UNSPECIFIED" : intent;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
