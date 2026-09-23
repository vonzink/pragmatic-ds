package com.pragmaticds.docengine.review;

import com.pragmaticds.docengine.classification.PageClassifier;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.pii.MaskableValue;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import com.pragmaticds.docengine.review.triage.TriageLabelJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document-level human decisions (MARK_REVIEWED, RECLASSIFY) and the read-side decision history.
 * Each write appends an append-only {@code review_decision}, mutates the sanctioned
 * {@code logical_document} columns in place, and audits — the same shape as
 * {@link FieldCorrectionService}, one level up.
 *
 * <p>{@link #labelClassification} is the exception that proves the shape: a TRIAGE label appends a
 * decision and mutates NOTHING, because it answers for a page run rather than for the document's
 * identity. See its javadoc.
 */
@Service
public class ReviewDecisionService {

    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository documentPages;
    private final PackageRefRepository packageRefs;
    private final ExtractedFieldRepository fields;
    private final ClassificationResultRepository classifications;
    private final ReviewDecisionRepository decisions;
    private final AuditService audit;

    public ReviewDecisionService(
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository documentPages,
            PackageRefRepository packageRefs,
            ExtractedFieldRepository fields,
            ClassificationResultRepository classifications,
            ReviewDecisionRepository decisions,
            AuditService audit) {
        this.documents = documents;
        this.documentPages = documentPages;
        this.packageRefs = packageRefs;
        this.fields = fields;
        this.classifications = classifications;
        this.decisions = decisions;
        this.audit = audit;
    }

    @Transactional
    public DocumentDecisionResult markReviewed(UUID documentId) {
        AuthPrincipal principal = requireHuman();
        LogicalDocument document = loadDocument(documentId, principal.orgId());

        String previousStatus = document.getReviewStatus();
        Instant now = Instant.now();
        ReviewDecision decision =
                decisions.save(
                        new ReviewDecision(
                                ReviewDecision.SUBJECT_LOGICAL_DOCUMENT,
                                documentId,
                                ReviewDecision.ACTION_MARK_REVIEWED,
                                ReviewJson.object("reviewStatus", previousStatus),
                                ReviewJson.object("reviewStatus", LogicalDocument.REVIEW_REVIEWED),
                                null,
                                principal.userId()));
        document.markReviewed(principal.userId(), now);
        documents.save(document);

        audit.record(
                AuditEvent.ACTION_DOCUMENT_REVIEWED,
                ReviewDecision.SUBJECT_LOGICAL_DOCUMENT,
                documentId,
                Map.of("previousStatus", previousStatus, "reviewStatus", LogicalDocument.REVIEW_REVIEWED));

        return new DocumentDecisionResult(
                documentId,
                document.getDocumentTypeCode(),
                document.getReviewStatus(),
                document.getReviewedBy(),
                document.getReviewedAt(),
                decisionView(decision, previousStatus, LogicalDocument.REVIEW_REVIEWED));
    }

    @Transactional
    public DocumentDecisionResult reclassify(UUID documentId, String documentTypeCode, String reason) {
        AuthPrincipal principal = requireHuman();
        if (documentTypeCode == null || documentTypeCode.isBlank()) {
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "DOCUMENT_TYPE_REQUIRED"));
        }
        LogicalDocument document = loadDocument(documentId, principal.orgId());

        String previousType = document.getDocumentTypeCode();
        ReviewDecision decision =
                decisions.save(
                        new ReviewDecision(
                                ReviewDecision.SUBJECT_LOGICAL_DOCUMENT,
                                documentId,
                                ReviewDecision.ACTION_RECLASSIFY,
                                ReviewJson.object("documentTypeCode", previousType),
                                ReviewJson.object("documentTypeCode", documentTypeCode),
                                reason,
                                principal.userId()));
        document.reclassify(documentTypeCode);
        documents.save(document);

        audit.record(
                AuditEvent.ACTION_DOCUMENT_RECLASSIFIED,
                ReviewDecision.SUBJECT_LOGICAL_DOCUMENT,
                documentId,
                Map.of("previousType", previousType, "newType", documentTypeCode));

        return new DocumentDecisionResult(
                documentId,
                document.getDocumentTypeCode(),
                document.getReviewStatus(),
                document.getReviewedBy(),
                document.getReviewedAt(),
                decisionView(decision, previousType, documentTypeCode));
    }

    /**
     * TRIAGE LABEL: a reviewer names the type of a PAGE RUN inside a document — the untyped stretch
     * the splitter absorbed, or the whole of a document the engine typed {@code UNKNOWN}.
     *
     * <p><b>It mutates nothing.</b> That is the entire point, and it is the one way this method
     * differs from {@link #reclassify}. The machine's account of these pages —
     * {@code classification_result} with its evidence, {@code logical_document.document_type_code},
     * the confidence the split computed — stays exactly as the engine wrote it, and the human's
     * answer lands beside it as an append-only {@code review_decision}. Overwriting the document's
     * type here would be actively wrong: an absorbed run is a MINORITY of a typed document's pages,
     * so "these three pages are a VOE" is not a claim about the eleven-page W-2 that swallowed
     * them, and re-splitting on the strength of one label is {@code RegroupService}'s job, which a
     * reviewer reaches deliberately rather than as a side effect of answering a queue.
     *
     * <p>Recorded as {@code subject_type = CLASSIFICATION} rather than {@code LOGICAL_DOCUMENT}:
     * the subject is the classification VERDICT over a run, not the document's identity, and the
     * distinction is what lets {@code UnknownTriageService} find labels without mistaking a
     * document-level reclassification for an answer to a triage item. Both are {@code RECLASSIFY} —
     * the human is making the same kind of call — and V8's CHECK constraints already permit the
     * pair, so this needs no migration.
     *
     * <p>What it does NOT do, deliberately: retrain anything, author a rule pack, or re-run
     * extraction. See the design doc §7 for how a corpus of these rows later feeds pack authoring.
     *
     * @param pageIds the run, which must be CURRENT members of this document — a label that names
     *     pages the document does not hold describes nothing and would sit in the audit trail
     *     forever claiming otherwise
     */
    @Transactional
    public DocumentDecisionResult labelClassification(
            UUID documentId, String documentTypeCode, List<UUID> pageIds, String reason) {
        AuthPrincipal principal = requireHuman();
        if (documentTypeCode == null || documentTypeCode.isBlank()) {
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "DOCUMENT_TYPE_REQUIRED"));
        }
        LogicalDocument document = loadDocument(documentId, principal.orgId());

        // Membership order, not request order: the same run labelled twice must produce byte-
        // identical page lists in the audit trail, and a reviewer reading the row wants the pages
        // in the order they appear in the document.
        Set<UUID> requested = new LinkedHashSet<>(pageIds);
        List<UUID> orderedPageIds =
                documentPages.findByLogicalDocumentIdOrderByOrdinal(documentId).stream()
                        .map(LogicalDocumentPage::getPageId)
                        .filter(requested::contains)
                        .toList();
        if (orderedPageIds.isEmpty() || orderedPageIds.size() != requested.size()) {
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "PAGE_NOT_IN_DOCUMENT"));
        }

        String previousType = pageTypeOf(orderedPageIds);
        ReviewDecision decision =
                decisions.save(
                        new ReviewDecision(
                                ReviewDecision.SUBJECT_CLASSIFICATION,
                                documentId,
                                ReviewDecision.ACTION_RECLASSIFY,
                                TriageLabelJson.previousValue(
                                        previousType,
                                        document.getDocumentTypeCode(),
                                        orderedPageIds),
                                TriageLabelJson.newValue(documentTypeCode, orderedPageIds),
                                reason,
                                principal.userId()));

        audit.record(
                AuditEvent.ACTION_DOCUMENT_RECLASSIFIED,
                ReviewDecision.SUBJECT_CLASSIFICATION,
                documentId,
                // Ids, codes and a count — never page content. "scope" is what tells an access
                // review that this reclassification changed no document type.
                Map.of(
                        "scope",
                        "PAGE_RUN",
                        "previousType",
                        previousType,
                        "newType",
                        documentTypeCode,
                        "pageCount",
                        String.valueOf(orderedPageIds.size())));

        return new DocumentDecisionResult(
                documentId,
                // UNCHANGED, and that is the contract: the caller sees that the document's type
                // survived the label, so a UI cannot quietly render the label as a retype.
                document.getDocumentTypeCode(),
                document.getReviewStatus(),
                document.getReviewedBy(),
                document.getReviewedAt(),
                decisionView(decision, previousType, documentTypeCode));
    }

    /**
     * What the MACHINE currently says about a run: its single classification type, or
     * {@code MIXED} when its pages disagree. A page with no current result reads
     * {@code UNKNOWN} — the same degradation {@code PackageSplitter.group} applies, because no
     * verdict carries strictly less information than a verdict of UNKNOWN.
     */
    private String pageTypeOf(List<UUID> pageIds) {
        Set<String> types = new LinkedHashSet<>();
        Map<UUID, String> byPage = new HashMap<>();
        for (ClassificationResult result :
                classifications.findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                        ClassificationResult.SUBJECT_PAGE, pageIds)) {
            byPage.put(result.getSubjectId(), result.getDocumentTypeCode());
        }
        for (UUID pageId : pageIds) {
            types.add(byPage.getOrDefault(pageId, PageClassifier.UNKNOWN));
        }
        return types.size() == 1 ? types.iterator().next() : "MIXED";
    }

    /**
     * The decision history for a document, its fields, its package, and its pages, newest first.
     *
     * <p>A package-wide REGROUP decision is keyed on the PACKAGE id and a page verdict on the PAGE
     * id (design §4.2-4.3), neither of which is the document id or one of its field ids. Surfacing
     * them for the document — so the strip is the whole decision trail the design promises, not just
     * the document- and field-keyed rows — means widening the subject set with the document's
     * package id and its current page ids.
     */
    public HistoryResult history(UUID documentId) {
        UUID orgId = AuthContext.require().orgId();
        LogicalDocument document = loadDocument(documentId, orgId);

        Map<UUID, String> fieldNameById = new LinkedHashMap<>();
        Map<UUID, String> groupKeyByFieldId = new HashMap<>();
        Map<UUID, Boolean> sensitiveByFieldId = new HashMap<>();
        for (ExtractedField field :
                fields.findCurrentOccurrencesByLogicalDocumentId(documentId)) {
            fieldNameById.put(field.getId(), field.getFieldName());
            // Spec 5a: a repeating field has one row per occurrence, so the NAME alone stops
            // labelling the decision — three corrected rental properties would render as three
            // identical "rentsReceived" rows, separable only by a subject id no reviewer reads.
            if (field.getGroupKey() != null) {
                groupKeyByFieldId.put(field.getId(), field.getGroupKey());
            }
            sensitiveByFieldId.put(field.getId(), field.isSensitive());
        }
        List<UUID> subjectIds = new ArrayList<>();
        subjectIds.add(documentId);
        subjectIds.addAll(fieldNameById.keySet());
        // The package-keyed REGROUP decision and the page-keyed OVERRIDE_VERDICT decisions.
        subjectIds.add(document.getPackageId());
        for (LogicalDocumentPage member :
                documentPages.findByLogicalDocumentIdOrderByOrdinal(documentId)) {
            subjectIds.add(member.getPageId());
        }

        List<HistoryEntry> entries = new ArrayList<>();
        for (ReviewDecision decision : decisions.findBySubjectIdInOrderByDecidedAtDesc(subjectIds)) {
            // A field decision inherits the field's sensitivity; every non-field decision —
            // document (reclassify, mark-reviewed), package (regroup), or page (verdict) — carries a
            // type/status/grouping/signal, ids and codes only, never PII, so it is never sensitive.
            boolean sensitive = sensitiveByFieldId.getOrDefault(decision.getSubjectId(), false);
            entries.add(
                    new HistoryEntry(
                            decision.getSubjectType(),
                            decision.getSubjectId(),
                            fieldNameById.get(decision.getSubjectId()),
                            groupKeyByFieldId.get(decision.getSubjectId()),
                            decision.getAction(),
                            MaskableValue.of(ReviewJson.soleValue(decision.getPreviousValue()), sensitive),
                            MaskableValue.of(ReviewJson.soleValue(decision.getNewValue()), sensitive),
                            decision.getReason(),
                            decision.getDecidedBy(),
                            decision.getDecidedAt()));
        }
        return new HistoryResult(documentId, entries);
    }

    private AuthPrincipal requireHuman() {
        AuthPrincipal principal = AuthContext.require();
        if (principal.userId() == null) {
            // decided_by is NOT NULL — only a human may make a document review decision.
            throw new DomainException(ErrorCode.FORBIDDEN_DOCUMENT, 403);
        }
        return principal;
    }

    private LogicalDocument loadDocument(UUID documentId, UUID orgId) {
        // The org-scoped load IS the access check: a cross-tenant document id is absent (404).
        LogicalDocument document =
                documents
                        .findByIdAndOrgId(documentId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        // A tombstoned package is unreadable AND unwritable: mark-reviewed, reclassify, and the
        // history strip all 404 on a soft-deleted package, exactly like a nonexistent one.
        packageRefs
                .findByIdAndOrgIdAndDeletedAtIsNull(document.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        return document;
    }

    private static DecisionView decisionView(
            ReviewDecision decision, String previousValue, String newValue) {
        // Document-level decisions carry a type/status, never a field value — so they are never
        // sensitive. The MaskableValue wrapping is the structural guard, not a live mask here.
        return new DecisionView(
                decision.getId(),
                decision.getAction(),
                MaskableValue.of(previousValue, false),
                MaskableValue.of(newValue, false),
                decision.getReason(),
                decision.getDecidedBy(),
                decision.getDecidedAt());
    }

    public record DocumentDecisionResult(
            UUID documentId,
            String documentTypeCode,
            String reviewStatus,
            UUID reviewedBy,
            Instant reviewedAt,
            DecisionView decision) {}

    public record DecisionView(
            UUID id,
            String action,
            MaskableValue previousValue,
            MaskableValue newValue,
            String reason,
            UUID decidedBy,
            Instant decidedAt) {}

    public record HistoryResult(UUID documentId, List<HistoryEntry> entries) {}

    /**
     * One row of the review-history strip.
     *
     * @param fieldName the field's name when the subject is a field, else null
     * @param previousValue original value (decoded from the single-key jsonb envelope)
     * @param newValue corrected value (decoded); null for CONFIRM/REJECT/MARK_REVIEWED
     */
    /**
     * @param fieldName the decided field's name, null for a document-, package- or page-keyed
     *     decision
     * @param groupKey the decided OCCURRENCE's repeating-group key (Spec 5a) — {@code "A"} for a
     *     Schedule E property column, the zero-padded row ordinal for an entity table — null both
     *     for a field that does not repeat and for every non-field decision. Without it the strip
     *     labels three corrected rental properties identically and a reviewer cannot tell which
     *     one a decision belongs to. A plain {@code String}: the key is a coordinate printed on
     *     the form, never borrower data, so the masking guard correctly does not match it.
     */
    public record HistoryEntry(
            String subjectType,
            UUID subjectId,
            String fieldName,
            String groupKey,
            String action,
            MaskableValue previousValue,
            MaskableValue newValue,
            String reason,
            UUID decidedBy,
            Instant decidedAt) {}
}
