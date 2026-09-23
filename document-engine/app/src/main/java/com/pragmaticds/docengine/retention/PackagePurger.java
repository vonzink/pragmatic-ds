package com.pragmaticds.docengine.retention;

import com.pragmaticds.docengine.ai.AiInterpretationLedger;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.repo.BoundaryProposalRepository;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.extraction.repo.FieldEvidenceRepository;
import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.LayoutElementRepository;
import com.pragmaticds.docengine.parsing.repo.LayoutElementSpanRepository;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.ParserOutputRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permanently deletes ONE due package: every DB row in FK-safe order, then every blob, then a
 * PII-free {@code PACKAGE_PURGED} audit event carrying COUNTS only. The caller ({@link
 * RetentionPurgeJob}) has already bound this package's org as the tenant AND a SYSTEM principal, so
 * every tenant-scoped delete here stays inside that org and the audit is attributed to the system
 * sweep.
 *
 * <p>Blob keys are enumerated from the rows BEFORE the rows are deleted (once the rows are gone
 * their keys are unrecoverable). {@code parser_output} is not package-scoped in the schema, so its
 * keys come from a join through source_file and page. Immutable engine-result descriptors are
 * loaded oldest-to-newest for deterministic enumeration, then deleted newest-to-oldest because
 * their predecessor foreign key is non-cascading.
 *
 * <h2>FK-safe delete order (children → root)</h2>
 *
 * V7 makes {@code extracted_field→logical_document}, {@code field_evidence→extracted_field/page},
 * and {@code logical_document_page→page} ON DELETE CASCADE; everything else is a plain FK deleted
 * explicitly. {@code classification_result.subject_id} is polymorphic with no FK, so it is deleted
 * by subject id and MUST precede the pages/logical documents it points at. The order below never
 * deletes a parent before a child that plain-FK-references it.
 *
 * <p>{@code review_decision.subject_id} and {@code ai_interpretation.subject_id} are likewise
 * polymorphic with NO foreign key (V8), so a package purge would otherwise leave reviewer-typed or
 * AI-proposed values (potential PII) orphaned forever. Their subject ids (the package itself ∪ page
 * ∪ logical_document ∪ extracted_field — a package-wide REGROUP decision is keyed on the package
 * id) are captured before those rows are deleted, and the matching rows are deleted in this same
 * transaction. {@code audit_event} is the immutable trail and is never deleted.
 *
 * <p><strong>Prototype durability boundary:</strong> database rows and the audit event are one
 * transaction, but physical blob deletes cannot roll back. A later delete failure leaves any
 * earlier physical deletes absent while database work rolls back; the scheduled sweep retries and
 * the local adapter's delete-if-present behavior makes that safe. Production borrower use still
 * requires the deferred durable deletion outbox, reconciliation/janitor, and distributed claim.
 */
@Service
public class PackagePurger {

    private final DocumentPackageRepository packages;
    private final SourceFileRepository sourceFiles;
    private final PageRepository pages;
    private final TextSpanRepository textSpans;
    private final LayoutElementRepository layoutElements;
    private final LayoutElementSpanRepository layoutElementSpans;
    private final ParserOutputRepository parserOutputs;
    private final LogicalDocumentRepository logicalDocuments;
    private final LogicalDocumentPageRepository logicalDocumentPages;
    private final ClassificationResultRepository classificationResults;
    private final BoundaryProposalRepository boundaryProposals;
    private final ExtractedFieldRepository extractedFields;
    private final FieldEvidenceRepository fieldEvidence;
    private final ReviewDecisionRepository reviewDecisions;
    private final AiInterpretationLedger aiInterpretations;
    private final EngineResultRepository engineResults;
    private final ProcessingJobRepository jobs;
    private final ProcessingStageRepository stages;
    private final BlobStoragePort storage;
    private final AuditService audit;

    public PackagePurger(
            DocumentPackageRepository packages,
            SourceFileRepository sourceFiles,
            PageRepository pages,
            TextSpanRepository textSpans,
            LayoutElementRepository layoutElements,
            LayoutElementSpanRepository layoutElementSpans,
            ParserOutputRepository parserOutputs,
            LogicalDocumentRepository logicalDocuments,
            LogicalDocumentPageRepository logicalDocumentPages,
            ClassificationResultRepository classificationResults,
            BoundaryProposalRepository boundaryProposals,
            ExtractedFieldRepository extractedFields,
            FieldEvidenceRepository fieldEvidence,
            ReviewDecisionRepository reviewDecisions,
            AiInterpretationLedger aiInterpretations,
            EngineResultRepository engineResults,
            ProcessingJobRepository jobs,
            ProcessingStageRepository stages,
            BlobStoragePort storage,
            AuditService audit) {
        this.packages = packages;
        this.sourceFiles = sourceFiles;
        this.pages = pages;
        this.textSpans = textSpans;
        this.layoutElements = layoutElements;
        this.layoutElementSpans = layoutElementSpans;
        this.parserOutputs = parserOutputs;
        this.logicalDocuments = logicalDocuments;
        this.logicalDocumentPages = logicalDocumentPages;
        this.classificationResults = classificationResults;
        this.boundaryProposals = boundaryProposals;
        this.extractedFields = extractedFields;
        this.fieldEvidence = fieldEvidence;
        this.reviewDecisions = reviewDecisions;
        this.aiInterpretations = aiInterpretations;
        this.engineResults = engineResults;
        this.jobs = jobs;
        this.stages = stages;
        this.storage = storage;
        this.audit = audit;
    }

    /** Rows and blobs removed by one package purge — the numbers the audit records. */
    public record PurgeCounts(int rows, int blobs) {
        static final PurgeCounts NONE = new PurgeCounts(0, 0);
    }

    /**
     * Purges the package if it is genuinely tombstoned in this org; a race that already un-deleted
     * or already purged it is a no-op. A failure rolls back database rows and audit together; blob
     * deletion is external and follows the explicitly documented prototype boundary above.
     */
    @Transactional
    public PurgeCounts purge(UUID packageId, UUID orgId) {
        DocumentPackage pkg = packages.findByIdAndOrgId(packageId, orgId).orElse(null);
        if (pkg == null || pkg.getDeletedAt() == null) {
            // Never purge a live package: the tombstone is the licence to delete.
            return PurgeCounts.NONE;
        }

        // (a) Enumerate ALL blob keys from the rows BEFORE deleting the rows — afterwards the keys
        // are gone. Dedup so the blob count is blobs, not delete calls.
        Set<String> blobKeys = new LinkedHashSet<>();
        for (SourceFile file : sourceFiles.findByPackageIdOrderByOrdinal(packageId)) {
            blobKeys.add(file.getStorageKeyOriginal());
            if (file.getStorageKeyNormalized() != null) {
                blobKeys.add(file.getStorageKeyNormalized());
            }
        }
        // review_decision.subject_id is polymorphic with NO foreign key (V8), so nothing cascades:
        // collect the package's subject ids (page ∪ logical_document ∪ extracted_field) NOW, while
        // the id-bearing rows still exist, so the reviewer-typed values (potential PII) can be
        // deleted below rather than orphaned forever.
        Set<UUID> decisionSubjectIds = new LinkedHashSet<>();
        // A package-wide REGROUP decision is keyed on the PACKAGE id itself (RegroupService:
        // subject_type LOGICAL_DOCUMENT, subject_id = packageId), not on any field/document/page —
        // so the page ∪ document ∪ field enumeration below would miss it and leave the reviewer's
        // grouping delta orphaned. Seed the set with the package id so that decision is purged too.
        decisionSubjectIds.add(packageId);
        for (Page page : pages.findByPackageIdOrderByPackagePageIndex(packageId)) {
            decisionSubjectIds.add(page.getId());
            if (page.getRenderStorageKey() != null) {
                blobKeys.add(page.getRenderStorageKey());
            }
        }
        for (LogicalDocument document : logicalDocuments.findByPackageIdOrderByOrdinal(packageId)) {
            decisionSubjectIds.add(document.getId());
        }
        decisionSubjectIds.addAll(extractedFields.findIdsByPackageIdAndOrgId(packageId, orgId));
        blobKeys.addAll(parserOutputs.findPayloadKeysByPackageIdAndOrgId(packageId, orgId));
        List<EngineResult> resultHistory =
                engineResults.findByPackageIdAndOrgIdOrderByRevisionAsc(packageId, orgId);
        for (EngineResult result : resultHistory) {
            blobKeys.add(result.getEnvelopeStorageKey());
        }

        // (b) Delete DB rows in FK-safe order (children → root). review_decision has no FK to any
        // of these, so it is deleted by the subject ids captured above (audit_event is the
        // immutable trail and is deliberately NOT deleted).
        int rows = 0;
        // engine_result has plain FKs to job/package and a plain self-FK to its predecessor. Flush
        // each newest-to-oldest removal so neither Hibernate batching nor a later refactor can
        // delete revision N-1 while revision N still references it.
        for (int index = resultHistory.size() - 1; index >= 0; index--) {
            engineResults.delete(resultHistory.get(index));
            engineResults.flush();
            rows++;
        }
        if (!decisionSubjectIds.isEmpty()) {
            rows += reviewDecisions.deleteBySubjectIdInAndOrgId(decisionSubjectIds, orgId);
            rows += aiInterpretations.deleteBySubjectIdsAndOrgId(decisionSubjectIds, orgId);
        }
        rows += parserOutputs.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += fieldEvidence.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += extractedFields.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += layoutElementSpans.deleteByPackageId(packageId, orgId);
        rows += textSpans.deleteByPackageId(packageId, orgId);
        rows += layoutElements.deleteByPackageId(packageId, orgId);
        // Before page and logical_document: subject_id is a bare (unconstrained) reference to them.
        rows += classificationResults.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += boundaryProposals.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += logicalDocumentPages.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += pages.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += logicalDocuments.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += stages.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += jobs.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += sourceFiles.deleteByPackageIdAndOrgId(packageId, orgId);
        rows += packages.deleteByIdAndOrgId(packageId, orgId);

        // (c) Delete each deduped blob key. Absence is idempotent. A different storage failure is
        // propagated so DB/audit work rolls back, while already-completed physical deletes remain
        // absent and are safely retried by the next sweep (prototype contract; no atomic claim).
        int blobs = 0;
        for (String key : blobKeys) {
            storage.delete(key);
            blobs++;
        }

        // (d) Audit with COUNTS ONLY — never a filename, key, digest, or field value. blobs is the
        // number of deduped keys processed successfully, not proof each object was newly removed.
        audit.record(
                AuditEvent.ACTION_PACKAGE_PURGED,
                "DOCUMENT_PACKAGE",
                packageId,
                Map.of("rowsDeleted", rows, "blobsDeleted", blobs));

        return new PurgeCounts(rows, blobs);
    }
}
