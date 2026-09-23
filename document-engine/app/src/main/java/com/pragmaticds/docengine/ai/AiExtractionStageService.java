package com.pragmaticds.docengine.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.extract.ConfidenceBreakdown;
import com.pragmaticds.docengine.extraction.domain.FieldEvidence;
import com.pragmaticds.docengine.extraction.overlay.FieldSnapshot;
import com.pragmaticds.docengine.extraction.overlay.ReviewCarryForward;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.extraction.repo.FieldEvidenceRepository;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.LayoutElementRepository;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.ai.AiDocumentType;
import com.pragmaticds.docengine.platform.ai.AiExtractionPort;
import com.pragmaticds.docengine.platform.ai.AiExtractionRequest;
import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiExtractionStatus;
import com.pragmaticds.docengine.platform.ai.AiSecondPass;
import com.pragmaticds.docengine.platform.ai.AiStructuredExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Check;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Confidence;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Txn;
import com.pragmaticds.docengine.platform.ai.BankStatementExtractionSchema;
import com.pragmaticds.docengine.platform.ai.PaystubExtraction;
import com.pragmaticds.docengine.platform.ai.PaystubExtractionSchema;
import com.pragmaticds.docengine.platform.ai.W2Extraction;
import com.pragmaticds.docengine.platform.ai.W2ExtractionSchema;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional AI enrichment over persisted text/layout and deterministic field rows, per document
 * type. Everything type-specific — which documents are eligible, the output schema, how a typed
 * extraction becomes field values, whether arithmetic reconciliation exists, the ledger's prompt
 * versions — lives in a {@link TypeProfile}; the persist pipeline (anchoring gates, precedence,
 * review reasons, the handwriting ceiling) is one shared path every type earns identically.
 */
@Service
public class AiExtractionStageService implements AiBehaviorIdentity {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionStageService.class);

    private static final String BANK_STATEMENT = "BANK_STATEMENT";
    private static final String PAYSTUB = "PAYSTUB";
    private static final String W2 = "W2";
    private static final String EXTRACTOR_VERSION = "ai/phase4";
    /**
     * 1.1.0 since the prompt learned the ONLINE ACTIVITY PRINT-OUT genre. Bumped rather than
     * edited under the old number for the reason V10 gives for rule packs: {@code
     * ai_interpretation.prompt_version} names the prompt that produced every stored interpretation,
     * so leaving two different prompts sharing one version would make that record a lie.
     */
    private static final String PROMPT_VERSION = "bank-statement/1.1.1";

    /** Ledger marker for Phase G rows, so the reviewer-queue delta (G3) can be measured per pass. */
    private static final String SECOND_PASS_PROMPT_VERSION = "bank-statement/1.1.1+second-pass";
    private static final String OUTPUT_SCHEMA_VERSION = "bank-statement/1.0.0";
    // 1.1.0: the stub's own total deductions, year-to-date net, and the earnings/deduction
    // tables joined the schema so PaystubReconciler has an identity to close. The version moves
    // because the ledger must be able to say which instruction produced a given reading.
    private static final String PAYSTUB_PROMPT_VERSION = "paystub/1.1.0";
    private static final String PAYSTUB_SECOND_PASS_PROMPT_VERSION = "paystub/1.1.0+second-pass";
    private static final String PAYSTUB_OUTPUT_SCHEMA_VERSION = "paystub/1.1.0";
    // 1.0.0: the W-2 dialect — nine of w2@1.3.0's ten fields (never employeeSsn), no reconciler.
    private static final String W2_PROMPT_VERSION = "w2/1.0.0";
    private static final String W2_SECOND_PASS_PROMPT_VERSION = "w2/1.0.0+second-pass";
    private static final String W2_OUTPUT_SCHEMA_VERSION = "w2/1.0.0";
    private static final Set<String> RECONCILED_SUMMARY_FIELDS =
            Set.of(
                    "beginningBalance",
                    "endingBalance",
                    "totalDeposits",
                    "totalWithdrawals");

    /**
     * The persisted paystub fields a closed {@code gross - deductions = net} actually vouches for:
     * exactly the identity's three terms. {@code currentTotalDeductions} joined when paystub@1.5.0
     * (V53) gave it a field row; the YTD totals and the earnings lines are persisted under the
     * same version but are NOT here — the current-period identity says nothing about them, and
     * gating them on it would assert more than was proved.
     */
    private static final Set<String> RECONCILED_PAYSTUB_FIELDS =
            Set.of("currentGrossPay", "netPay", "currentTotalDeductions");

    /**
     * The earnings-line ROW group's cap, equal to the {@code maxRows} every {@code earning*} field
     * declares in paystub@1.5.0 (V53). The key space is the loader's two-digit row ordinal, so the
     * cap can never exceed {@code ExtractionSchemaLoader.MAX_ROWS_CEILING}; forty bounds a table
     * no real stub approaches. Lines past the cap are dropped and logged, never re-keyed.
     */
    public static final int PAYSTUB_EARNINGS_MAX_ROWS = 40;

    /** The paystub earnings-line row group, for {@link TypeProfile#rowGroupOf}. */
    private static final String PAYSTUB_EARNINGS_GROUP = "earnings";

    private static final String PAYSTUB_EARNINGS_FIELD_PREFIX = "earning";

    private final boolean enabled;
    private final boolean paystubEnabled;
    private final boolean w2Enabled;
    private final boolean pageImagesEnabled;
    private final BigDecimal pageImageOcrFloor;
    private final int maxPageImages;
    private final int maxPages;
    private final int maxInputCharacters;
    private final AiExtractionPort ai;
    private final AiSecondPass secondPass;
    private final boolean secondPassEnabled;
    private final BigDecimal handwritingSpanConfidenceFloor;
    private final BlobStoragePort blobs;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository documentPages;
    private final PageRepository pages;
    private final TextSpanRepository spans;
    private final LayoutElementRepository layout;
    private final ExtractedFieldRepository fields;
    private final FieldEvidenceRepository evidence;
    private final ExtractionSchemaLoader schemas;
    private final ReviewCarryForward reviewCarryForward;
    private final AiInterpretationLedger interpretations;
    private final ProcessingJobRepository jobs;
    private final ObjectMapper mapper;
    private final BankStatementExtractionSchema outputSchema = new BankStatementExtractionSchema();
    private final PaystubExtractionSchema paystubOutputSchema = new PaystubExtractionSchema();
    private final W2ExtractionSchema w2OutputSchema = new W2ExtractionSchema();
    private final AiEvidenceAnchor evidenceAnchor = new AiEvidenceAnchor();
    private final AiRowAnchorResolver rowAnchorResolver = new AiRowAnchorResolver();
    private final BankStatementReconciler reconciler = new BankStatementReconciler();
    private final PaystubReconciler paystubReconciler = new PaystubReconciler();
    private final Map<String, TypeProfile> profiles = new LinkedHashMap<>();

    public AiExtractionStageService(
            @Value("${docengine.ai.enabled:false}") boolean enabled,
            @Value("${docengine.ai.paystub.enabled:false}") boolean paystubEnabled,
            @Value("${docengine.ai.w2.enabled:false}") boolean w2Enabled,
            @Value("${docengine.ai.max-pages:100}") int maxPages,
            @Value("${docengine.ai.max-input-characters:500000}") int maxInputCharacters,
            @Value("${docengine.ai.page-images.enabled:false}") boolean pageImagesEnabled,
            @Value("${docengine.ai.page-images.ocr-confidence-floor:0.60}")
                    BigDecimal pageImageOcrFloor,
            @Value("${docengine.ai.page-images.max-per-document:8}") int maxPageImages,
            @Value("${docengine.ai.second-pass.enabled:false}") boolean secondPassEnabled,
            @Value("${docengine.ai.handwriting.span-confidence-floor:0.60}")
                    BigDecimal handwritingSpanConfidenceFloor,
            AiExtractionPort ai,
            AiSecondPass secondPass,
            BlobStoragePort blobs,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository documentPages,
            PageRepository pages,
            TextSpanRepository spans,
            LayoutElementRepository layout,
            ExtractedFieldRepository fields,
            FieldEvidenceRepository evidence,
            ExtractionSchemaLoader schemas,
            ReviewCarryForward reviewCarryForward,
            AiInterpretationLedger interpretations,
            ProcessingJobRepository jobs,
            ObjectMapper mapper) {
        this.enabled = enabled;
        this.paystubEnabled = paystubEnabled;
        this.w2Enabled = w2Enabled;
        if (maxPages < 1 || maxInputCharacters < 1) {
            throw new IllegalArgumentException("AI input limits must be positive");
        }
        this.maxPages = maxPages;
        this.maxInputCharacters = maxInputCharacters;
        this.pageImagesEnabled = pageImagesEnabled;
        this.pageImageOcrFloor = pageImageOcrFloor;
        this.maxPageImages = maxPageImages;
        this.ai = ai;
        this.secondPass = secondPass;
        this.secondPassEnabled = secondPassEnabled;
        this.handwritingSpanConfidenceFloor = handwritingSpanConfidenceFloor;
        this.blobs = blobs;
        this.documents = documents;
        this.documentPages = documentPages;
        this.pages = pages;
        this.spans = spans;
        this.layout = layout;
        this.fields = fields;
        this.evidence = evidence;
        this.schemas = schemas;
        this.reviewCarryForward = reviewCarryForward;
        this.interpretations = interpretations;
        this.jobs = jobs;
        this.mapper = mapper;
        profiles.put(BANK_STATEMENT, new BankStatementTypeProfile());
        profiles.put(PAYSTUB, new PaystubTypeProfile());
        profiles.put(W2, new W2TypeProfile());
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Switched off collapses to one entry, and that is precise rather than lazy.</b> With
     * {@code docengine.ai.enabled} false {@link #extractForPackage} returns {@code
     * skipped("AI_DISABLED")} before reading a document, a budget or an adapter, so "off" is a
     * COMPLETE description of what this seam contributed to the parse. Listing the inert knobs
     * anyway would invalidate every stored fingerprint in an AI-off deployment the day someone
     * edits a model name that nothing reads.
     *
     * <p><b>Switched on lists everything, exercised or not</b> — every dialect even when only one
     * type is in the package, every gate (paystub, W-2), every budget. That is the same rule the
     * fingerprint already applies to classification packs and extraction schemas, which are
     * listed for the whole org rather than for the types a given upload happens to contain.
     *
     * <p>The dialects ride as a SHA-256 of each cached prefix, which is the system instructions,
     * the few-shot example and the output schema together. Hashing the content rather than naming
     * the prompt version is deliberate: it still moves when someone edits a prompt and forgets to
     * bump {@code PROMPT_VERSION}, which is precisely the mistake a version label cannot catch.
     *
     * <p>What is EXCLUDED, with reasons. Timeouts and {@code max-output-tokens} change failure
     * modes, not successful output — a run that times out or overruns its token budget fails its
     * stage and never finalizes, so it can never be a reuse source (the same rationale the
     * composer already gives for render batching). Credentials, {@code base-url} and the Vertex
     * project and region select WHERE a model runs and WHO may call it, not WHICH model answers;
     * that is {@code provider/model}, reported by the adapter itself.
     */
    @Override
    public Optional<Map<String, String>> aiBehaviorIdentity() {
        if (!enabled) {
            return Optional.of(Map.of("enabled", "false"));
        }
        Optional<String> firstPass = ai.behaviorIdentity();
        if (firstPass.isEmpty()) {
            // Configured to enrich, but the adapter that would answer is the fail-closed stub.
            // No fingerprint at all — see AiExtractionPort.behaviorIdentity.
            log.info(
                    "behavior fingerprint withheld: AI extraction is enabled but its adapter"
                            + " declares no identity, so this run cannot be described");
            return Optional.empty();
        }
        Map<String, String> identity = new java.util.TreeMap<>();
        identity.put("enabled", "true");
        identity.put("paystubEnabled", Boolean.toString(paystubEnabled));
        identity.put("w2Enabled", Boolean.toString(w2Enabled));
        identity.put("firstPass", firstPass.get());
        identity.put("maxPages", Integer.toString(maxPages));
        identity.put("maxInputCharacters", Integer.toString(maxInputCharacters));
        identity.put("pageImagesEnabled", Boolean.toString(pageImagesEnabled));
        identity.put("pageImageOcrFloor", pageImageOcrFloor.toPlainString());
        identity.put("maxPageImages", Integer.toString(maxPageImages));
        identity.put(
                "handwritingSpanConfidenceFloor",
                handwritingSpanConfidenceFloor.toPlainString());
        if (secondPassEnabled) {
            Optional<String> secondPassIdentity = secondPass.port().behaviorIdentity();
            if (secondPassIdentity.isEmpty()) {
                log.info(
                        "behavior fingerprint withheld: the AI second pass is enabled but its"
                                + " adapter declares no identity");
                return Optional.empty();
            }
            identity.put("secondPass", secondPassIdentity.get());
        } else {
            identity.put("secondPass", "disabled");
        }
        for (com.pragmaticds.docengine.platform.ai.AiExtractionDialect dialect :
                com.pragmaticds.docengine.platform.ai.AiExtractionDialects.all()) {
            identity.put(
                    "dialect." + dialect.type().name(),
                    Digests.sha256Hex(dialect.cachedPrefix()));
        }
        return Optional.of(Map.copyOf(identity));
    }

    /**
     * The profile serving a document type, or null when the type is not AI-extractable here.
     * PAYSTUB rides its own gate: paystub text is a compliance surface the bank-statement
     * sign-off never covered, so it stays dark until {@code docengine.ai.paystub.enabled} opens
     * it — with the flag off, eligibility is byte-identical to the bank-statement-only engine.
     */
    private TypeProfile profileFor(String documentTypeCode) {
        if (PAYSTUB.equals(documentTypeCode) && !paystubEnabled) {
            return null;
        }
        // W2 rides its own gate for the same reason PAYSTUB does: W-2 content (employer, wages,
        // withholding) is a compliance surface no earlier sign-off covered.
        if (W2.equals(documentTypeCode) && !w2Enabled) {
            return null;
        }
        return profiles.get(documentTypeCode);
    }

    /**
     * Enriches every eligible logical document in the package, <b>each on its own</b>. A document
     * whose input exceeds the configured limit, or whose provider call comes back anything but a
     * well-shaped OK, is recorded in the ledger as {@code ERROR} with its reason and costs exactly
     * that one document: its siblings are still called, persisted and recorded {@code APPLIED} as
     * if it were not there. (Issue #58 — the previous all-or-nothing contract let one oversized
     * bank statement silently discard the extraction of every other document in the upload, and
     * presented as "the AI did not work on these documents".)
     *
     * <p>The stage result follows from what was persisted, never from what merely failed:
     *
     * <ul>
     *   <li>at least one document applied → {@link StageResult.Status#APPLIED}, with {@code
     *       documents} = every eligible document, {@code documentsFailed} counting the ones that
     *       did not, and {@code failureReason} naming the first failure (null when none);
     *   <li>every document failed → {@link StageResult.Status#ERROR} carrying the first failure's
     *       reason, retryable when ANY failure was {@code provider_transient}. No fields are
     *       written; each attempt's ERROR ledger rows stay (the ledger records every billed call),
     *       so a retried package accumulates one ERROR row per document per attempt.
     * </ul>
     *
     * <p>A mixed outcome is deliberately NOT retryable at stage level even when one of its
     * failures was transient: a stage retry would re-call the provider for the siblings that
     * already applied, inside the same transaction, for nothing. The failed document's ledger row
     * and the stage detail's {@code failureReason} make it findable, and the recovery is the
     * per-document re-run — {@code POST /v1/documents/{id}/ai-extract}, {@link
     * #extractForDocument} (issue #66) — which re-calls the provider for that one document and
     * leaves its siblings alone.
     */
    @Transactional
    public StageResult extractForPackage(UUID packageId) {
        if (!enabled) {
            return StageResult.skipped("AI_DISABLED");
        }
        List<LogicalDocument> eligible =
                documents.findByPackageIdOrderByOrdinal(packageId).stream()
                        .filter(document -> profileFor(document.getDocumentTypeCode()) != null)
                        .toList();
        if (eligible.isEmpty()) {
            return StageResult.skipped("DOCUMENT_TYPE_NOT_ALLOWED");
        }

        List<DocumentOutcome> completed = new ArrayList<>();
        List<String> failureReasons = new ArrayList<>();
        StringBuilder digest = new StringBuilder();
        for (LogicalDocument document : eligible) {
            DocumentOutcome outcome =
                    extractOne(document, profileFor(document.getDocumentTypeCode()), digest);
            if (outcome.failed()) {
                failureReasons.add(outcome.failureReason());
            } else {
                completed.add(outcome);
            }
        }
        if (completed.isEmpty()) {
            // Nothing was persisted: extractOne persists only on a well-shaped OK.
            return StageResult.error(
                    eligible.size(),
                    failureReasons.get(0),
                    failureReasons.contains("provider_transient"));
        }

        PersistCounts counts = PersistCounts.NONE;
        for (DocumentOutcome outcome : completed) {
            counts = counts.plus(outcome.counts());
        }

        // ── Phase G: the confidence-triggered second pass ───────────────────
        // AFTER the first pass has done everything it can, and ONLY for documents that still
        // carry a missing or review-flagged field — the selector's narrowness is what keeps a
        // Pro-tier read at pennies per package. The results land through the SAME persist():
        // a deterministic FOUND is never overwritten (agreement or a conflict suggestion), and a
        // still-missing field accepts the stronger reader's anchored value. A second-pass
        // failure is logged and skipped, never fatal — the first pass already applied.
        for (DocumentOutcome outcome : completed) {
            counts = counts.plus(secondPassFor(outcome.result(), digest));
        }
        return StageResult.applied(
                Digests.sha256Hex(digest.toString()),
                eligible.size(),
                counts.inserted(),
                counts.conflicts(),
                failureReasons.size(),
                failureReasons.isEmpty() ? null : failureReasons.get(0));
    }

    /**
     * Re-runs AI extraction for ONE logical document — the recovery path the {@link
     * #extractForPackage} javadoc points at (issue #66). It re-calls the provider for this
     * document only, runs the same first pass, persist and second pass the package path would,
     * and records a fresh ledger row ({@code APPLIED} or {@code ERROR}). It never touches the
     * document's siblings: no sibling is loaded, called, persisted or re-recorded.
     *
     * <p>Persisting goes through the same {@link #persist} as the stage, so its invariants hold
     * here too: a deterministic FOUND is never overwritten (it is flagged when the re-read
     * disagrees), and a reviewer's correction survives — the corrected row is either left in place
     * or its decision is carried onto the replacement, exactly as #53 requires.
     *
     * <p>Answers a typed {@code NOT_ELIGIBLE} — nothing called, nothing recorded — when the
     * package has no job (fail closed), the AI stage is disabled, the document's type has no
     * profile (mirrors {@link #profileFor}), or the document has no deterministic rows to enrich.
     * Throws 404 for a document this org cannot see and 409 while a pipeline is running against
     * the package (see {@link #lockSettledJob}).
     */
    @Transactional
    public DocumentRerunResult extractForDocument(UUID documentId) {
        UUID orgId = TenantContext.require();
        LogicalDocument document =
                documents
                        .findByIdAndOrgId(documentId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        if (lockSettledJob(document.getPackageId(), orgId).isEmpty()) {
            // Fail closed. Every package a deployment holds reached its documents through a
            // job (the upload seam creates one before anything runs), so a jobless package is
            // a fixture's shape — and without the row there is nothing to lock against.
            return DocumentRerunResult.notEligible(documentId, "NO_JOB");
        }
        if (!enabled) {
            return DocumentRerunResult.notEligible(documentId, "AI_DISABLED");
        }
        TypeProfile profile = profileFor(document.getDocumentTypeCode());
        if (profile == null) {
            return DocumentRerunResult.notEligible(documentId, "DOCUMENT_TYPE_NOT_ALLOWED");
        }
        if (fields.findCurrentOccurrencesByLogicalDocumentId(documentId).isEmpty()) {
            // persist() would throw on this; a re-run before EXTRACTING ever ran on the
            // document is a request that cannot be honoured, not a failure to report.
            return DocumentRerunResult.notEligible(documentId, "NO_DETERMINISTIC_FIELDS");
        }

        StringBuilder digest = new StringBuilder();
        DocumentOutcome outcome = extractOne(document, profile, digest);
        if (outcome.failed()) {
            return DocumentRerunResult.error(documentId, outcome.failureReason());
        }
        PersistCounts counts = outcome.counts().plus(secondPassFor(outcome.result(), digest));
        log.info(
                "AI extraction re-run document={} status=APPLIED fieldsInserted={} conflicts={}",
                documentId,
                counts.inserted(),
                counts.conflicts());
        return DocumentRerunResult.applied(documentId, counts.inserted(), counts.conflicts());
    }

    /**
     * The re-run's concurrency guard: the package's job row — the same row the run, resume and
     * regroup re-extract claims update — locked for this transaction with NO wait ({@link
     * ProcessingJobRepository#lockByPackageIdAndOrgId}), then refused unless the job has
     * settled. Two ways to a 409, both meaning "a pipeline is running against this package":
     *
     * <ul>
     *   <li>the row is already locked ({@code status: PROCESSING}) — {@code StageRunner} holds it
     *       for the whole of a running stage, so waiting would park the reviewer's request for
     *       as long as the stage runs (minutes under OCR) before saying the same thing;
     *   <li>the row is free but the status is a pipeline stage ({@code status: <stage>}) — a
     *       run between stages, whose next EXTRACTING or AI_EXTRACTION would race this re-run's
     *       rows.
     * </ul>
     *
     * <p>The predicate is {@code HUMAN_REVIEW_REQUIRED | COMPLETED | FAILED} — a settled job is
     * not being processed — and it is deliberately WIDER than {@code claimForReExtract}'s, which
     * admits only HUMAN_REVIEW_REQUIRED: that claim flips the job back to UPLOADED and guards
     * against ABA on attempt/generation, semantics the re-run does not have. A FAILED job is
     * settled too: the failure may itself be the AI stage, and the re-run is one way back.
     *
     * <p>A claim that arrives while this lock is held blocks until the re-run commits (claims are
     * plain UPDATEs, they wait); one that already committed is what the status check sees.
     *
     * @return the settled job, or empty when the package has none (the caller fails closed)
     */
    private Optional<ProcessingJob> lockSettledJob(UUID packageId, UUID orgId) {
        Optional<ProcessingJob> job;
        try {
            job = jobs.lockByPackageIdAndOrgId(packageId, orgId);
        } catch (PessimisticLockingFailureException
                | jakarta.persistence.LockTimeoutException
                | jakarta.persistence.PessimisticLockException locked) {
            // NOWAIT: Postgres 55P03 (lock_not_available). Spring translates Hibernate's wrapper
            // to CannotAcquireLockException / PessimisticLockingFailureException at the
            // repository proxy; the raw JPA types are caught for the case where it does not.
            throw DomainException.conflict(ErrorCode.CONFLICT, Map.of("status", "PROCESSING"));
        }
        if (job.isPresent() && !settled(job.get().getStatus())) {
            throw DomainException.conflict(
                    ErrorCode.CONFLICT, Map.of("status", job.get().getStatus().name()));
        }
        return job;
    }

    private static boolean settled(ProcessingStatus status) {
        return status == ProcessingStatus.HUMAN_REVIEW_REQUIRED || status.isTerminal();
    }

    /**
     * The per-document core both entry points share: assemble the request, call the provider,
     * and — on a well-shaped OK — persist through {@link #persist} and record the {@code APPLIED}
     * ledger row; on anything else record the {@code ERROR} row and report the reason. Exactly
     * one ledger row per call, and nothing persisted unless it was recorded {@code APPLIED}.
     */
    private DocumentOutcome extractOne(
            LogicalDocument document, TypeProfile profile, StringBuilder digest) {
        AiExtractionRequest request;
        try {
            request = assembleRequest(document, profile);
        } catch (InputLimitExceeded rejected) {
            // No provider call was made, so there is no provider result to record — the
            // ledger row still names the reason, because a reviewer reading "ERROR" with
            // nothing after it has been told nothing.
            recordFailure(document, profile, null, 0L, "INPUT_LIMIT_EXCEEDED");
            return DocumentOutcome.failed("INPUT_LIMIT_EXCEEDED");
        }
        long started = System.nanoTime();
        AiExtractionResult result = ai.extract(request);
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        if (result == null
                || result.status() != AiExtractionStatus.OK
                || !(result.extraction() instanceof AiStructuredExtraction extraction)
                || extraction.documentType() != profile.aiType()) {
            String reason =
                    result == null || result.reason() == null || result.reason().isBlank()
                            ? "PROVIDER_RESULT_NOT_OK"
                            : result.reason();
            recordFailure(document, profile, result, latencyMs, reason);
            return DocumentOutcome.failed(reason);
        }
        DocumentResult completed =
                new DocumentResult(document, profile, extraction, result, latencyMs);
        PersistResult persisted = persist(document, profile, extraction, digest);
        interpretations.record(
                document.getId(),
                result,
                profile.promptVersion(),
                providerCallJson(completed, persisted, "APPLIED"));
        return DocumentOutcome.completed(completed, persisted.counts());
    }

    /**
     * Phase G for one document that completed its first pass: nothing unless the second pass is
     * enabled and {@link #needsSecondPass} says the document still carries a missing or
     * review-flagged field. A second-pass failure is logged, recorded {@code ERROR} and skipped,
     * never fatal — the first pass already applied.
     */
    private PersistCounts secondPassFor(DocumentResult result, StringBuilder digest) {
        if (!secondPassEnabled || !needsSecondPass(result.document())) {
            return PersistCounts.NONE;
        }
        long started = System.nanoTime();
        AiExtractionResult secondResult =
                secondPass.port().extract(assembleRequest(result.document(), result.profile()));
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        if (secondResult == null
                || secondResult.status() != AiExtractionStatus.OK
                || !(secondResult.extraction() instanceof AiStructuredExtraction secondExtraction)
                || secondExtraction.documentType() != result.profile().aiType()) {
            log.warn("AI second pass document={} status=ERROR", result.document().getId());
            interpretations.record(
                    result.document().getId(),
                    secondResult,
                    result.profile().secondPassPromptVersion(),
                    providerCallJson(
                            new DocumentResult(
                                    result.document(),
                                    result.profile(),
                                    null,
                                    secondResult,
                                    latencyMs),
                            null,
                            "ERROR"));
            return PersistCounts.NONE;
        }
        DocumentResult secondDocument =
                new DocumentResult(
                        result.document(),
                        result.profile(),
                        secondExtraction,
                        secondResult,
                        latencyMs);
        PersistResult persisted =
                persist(result.document(), result.profile(), secondExtraction, digest);
        interpretations.record(
                result.document().getId(),
                secondResult,
                result.profile().secondPassPromptVersion(),
                providerCallJson(secondDocument, persisted, "APPLIED"));
        return persisted.counts();
    }

    /**
     * One document's first-pass failure: a WARN naming the document and the reason (never page
     * text), and an {@code ERROR} ledger row. {@code providerResult} is null when the failure
     * happened before any call was made.
     */
    private void recordFailure(
            LogicalDocument document,
            TypeProfile profile,
            AiExtractionResult providerResult,
            long latencyMs,
            String reason) {
        log.warn(
                "AI extraction document={} status=ERROR reason={}", document.getId(), reason);
        interpretations.record(
                document.getId(),
                providerResult,
                profile.promptVersion(),
                providerCallJson(
                        new DocumentResult(document, profile, null, providerResult, latencyMs),
                        null,
                        "ERROR",
                        reason));
    }

    /**
     * The G1 selector: does this document STILL carry a field worth a stronger read? TRUE when
     * any current occurrence is missing (method NONE — persisted as a result, not an absence) or
     * review-flagged (MANUAL_REVIEW_REQUIRED — the reviewer queue's own definition of shaky).
     * Measured AFTER the first pass persisted, so a field the cheap reader already filled and
     * anchored does not re-bill on the expensive one.
     */
    private boolean needsSecondPass(LogicalDocument document) {
        return fields.findCurrentOccurrencesByLogicalDocumentId(document.getId()).stream()
                .anyMatch(
                        field ->
                                "NONE".equals(field.getExtractionMethod())
                                        || ExtractedField.VALIDATION_MANUAL_REVIEW_REQUIRED.equals(
                                                field.getValidationStatus()));
    }

    private AiExtractionRequest assembleRequest(LogicalDocument document, TypeProfile profile) {
        StringBuilder text = new StringBuilder();
        StringBuilder tables = new StringBuilder();
        List<LogicalDocumentPage> links =
                documentPages.findByLogicalDocumentIdOrderByOrdinal(document.getId());
        if (links.size() > maxPages) {
            throw new InputLimitExceeded();
        }
        List<com.pragmaticds.docengine.platform.ai.AiExtractionRequest.PageImage> pageImages =
                new ArrayList<>();
        for (LogicalDocumentPage link : links) {
            Page page =
                    pages.findByIdAndOrgId(link.getPageId(), TenantContext.require()).orElseThrow();
            // Phase F trigger rule (F2): pixels ride along ONLY for a page whose OCR failed its
            // floor — never unconditionally. NATIVE pages have no OCR values and never qualify;
            // the cap bounds the payload on a document whose every scan is bad. The render blob
            // is the engine's own page image — the same pixels a reviewer sees.
            if (pageImagesEnabled
                    && pageImages.size() < maxPageImages
                    && ocrFloorFailed(page)
                    && page.getRenderStorageKey() != null) {
                pageImages.add(
                        new com.pragmaticds.docengine.platform.ai.AiExtractionRequest.PageImage(
                                page.getPackagePageIndex(),
                                blobs.get(page.getRenderStorageKey())));
            }
            int printedPage = page.getPackagePageIndex() + 1;
            text.append("PAGE ").append(printedPage).append('\n');
            enforceInputLimit(text, tables);
            for (TextSpan span : spans.findByPageIdOrderBySourceAscOrdinalAsc(page.getId())) {
                text.append(span.getText()).append('\n');
                enforceInputLimit(text, tables);
            }
            tables.append("PAGE ").append(printedPage).append('\n');
            enforceInputLimit(text, tables);
            for (LayoutElement element : layout.findByPageIdOrderByOrdinal(page.getId())) {
                tables.append(element.getElementType())
                        .append(' ')
                        .append(element.getOrdinal());
                if (element.getAttributes() != null) {
                    tables.append(' ').append(element.getAttributes());
                }
                if (element.getText() != null) {
                    tables.append(' ').append(element.getText());
                }
                tables.append('\n');
                enforceInputLimit(text, tables);
            }
        }
        return new AiExtractionRequest(
                profile.aiType(),
                text.toString(),
                tables.toString(),
                profile.outputSchemaJson(),
                List.copyOf(pageImages));
    }

    /**
     * Did OCR fail this page's floor? TRUE when both engines tripped every gate (the dedicated
     * OCR_LOW_CONFIDENCE flag) or the winning engine's median confidence sits below the
     * configured floor. A page with no OCR at all — NATIVE text — never qualifies: its text layer
     * is trusted and pixels would add nothing but tokens.
     */
    private boolean ocrFloorFailed(Page page) {
        if ("OCR_LOW_CONFIDENCE".equals(page.getOcrFallbackReason())) {
            return true;
        }
        return page.getOcrConfidenceMedian() != null
                && page.getOcrConfidenceMedian().compareTo(pageImageOcrFloor) < 0;
    }

    private void enforceInputLimit(StringBuilder text, StringBuilder tables) {
        if ((long) text.length() + tables.length() > maxInputCharacters) {
            throw new InputLimitExceeded();
        }
    }

    private PersistResult persist(
            LogicalDocument document,
            TypeProfile profile,
            AiStructuredExtraction extraction,
            StringBuilder digest) {
        List<ExtractedField> current =
                fields.findCurrentOccurrencesByLogicalDocumentId(document.getId());
        if (current.isEmpty()) {
            throw new IllegalStateException("AI extraction requires deterministic field rows");
        }
        ExtractionSchemaLoader.SchemaIdentity schema =
                schemas.activeSchemaIdentityFor(profile.typeCode())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                profile.typeCode() + " schema is not active"));
        if (schema.id() == null) {
            throw new IllegalStateException(
                    profile.typeCode() + " schema has no persisted identity");
        }
        Map<String, ExtractedField> byCoordinate = new HashMap<>();
        current.forEach(
                field ->
                        byCoordinate.put(
                                coordinate(field.getFieldName(), field.getGroupKey()), field));

        ReconciliationView reconciliation = profile.reconcile(extraction);
        Map<Integer, List<TextSpan>> evidencePages = evidencePages(document);
        List<AiValue> values = profile.values(extraction);
        Map<String, AiEvidenceAnchor.Match> anchors = new HashMap<>();
        for (AiValue value : values) {
            List<TextSpan> pageSpans =
                    value.page() == null
                            ? List.of()
                            : evidencePages.getOrDefault(value.page(), List.of());
            anchors.put(
                    coordinate(value.fieldName(), value.groupKey()),
                    evidenceAnchor.match(value.printedText(), pageSpans));
        }
        // Row-scoped disambiguation runs BEFORE the derived-field pass, so a field that inherits
        // its sibling's anchor inherits the resolved one. A repeating group defeats the string
        // matcher on its own — three identical checks paid the same day tie every cell — and this
        // is where the row's own geometry breaks the tie. It can only promote AMBIGUOUS to
        // MATCHED; see AiRowAnchorResolver for what it refuses to do.
        anchors.putAll(
                rowAnchorResolver.resolve(
                        values.stream()
                                .map(
                                        value ->
                                                new AiRowAnchorResolver.Member(
                                                        coordinate(
                                                                value.fieldName(),
                                                                value.groupKey()),
                                                        profile.rowGroupOf(value.fieldName()),
                                                        value.groupKey()))
                                .toList(),
                        anchors));

        for (AiValue value : values) {
            if (value.derivedFromField() != null) {
                anchors.put(
                        coordinate(value.fieldName(), value.groupKey()),
                        anchors.getOrDefault(
                                coordinate(value.derivedFromField(), value.groupKey()),
                                AiEvidenceAnchor.Match.unanchored()));
            }
        }

        int inserted = 0;
        int conflicts = 0;
        List<Map<String, Object>> conflictSuggestions = new ArrayList<>();
        for (AiValue value : values) {
            if (!value.present()) {
                if (value.missingOccurrence()) {
                    persistMissingOccurrence(document, schema, value, byCoordinate, digest);
                }
                continue;
            }
            String coordinate = coordinate(value.fieldName(), value.groupKey());
            AiEvidenceAnchor.Match anchor =
                    anchors.getOrDefault(coordinate, AiEvidenceAnchor.Match.unanchored());
            ExtractedField existing = byCoordinate.get(coordinate);
            if (existing != null && !"NONE".equals(existing.getExtractionMethod())) {
                if (!sameValue(existing, value)) {
                    existing.markAiConflict();
                    fields.save(existing);
                    conflicts++;
                    conflictSuggestions.add(
                            conflictSuggestion(existing, value, anchor, reconciliation));
                }
                digest.append(document.getId())
                        .append('/')
                        .append(coordinate)
                        .append(sameValue(existing, value) ? "/AGREEMENT\u001E" : "/CONFLICT\u001E");
                continue;
            }
            // This is the SECOND path in the engine that replaces a field row, and therefore the
            // second place a human's correction can be orphaned: the id changes, and a
            // review_decision naming the old id stops resolving. Snapshot before the delete, carry
            // after the insert — same rule, same component, as the EXTRACTING stage's wholesale
            // replacement. A field the deterministic reader missed (method NONE) and a human filled
            // in by hand is exactly the case the AI reader now overwrites.
            List<FieldSnapshot> replaced = List.of();
            if (existing != null) {
                replaced = List.of(FieldSnapshot.of(existing));
                fields.delete(existing);
                fields.flush();
            }
            ExtractedField persisted =
                    fields.save(
                            toField(
                                    document.getId(),
                                    schema,
                                    profile,
                                    value,
                                    anchor,
                                    reconciliation));
            reviewCarryForward.apply(replaced, List.of(persisted));
            persistEvidence(persisted, value, anchor);
            byCoordinate.put(coordinate, persisted);
            inserted++;
            digest.append(document.getId()).append('/').append(coordinate).append("/AI\u001E");
            if (value.groupKey() != null) {
                retireRegionNotReadPlaceholder(document, persisted, byCoordinate, digest);
            }
        }
        return new PersistResult(
                new PersistCounts(inserted, conflicts),
                reconciliation,
                List.copyOf(conflictSuggestions));
    }

    /**
     * The empty cell of a row the model read: written exactly as the deterministic engine writes a
     * located row's blank cell — method NONE, zero confidence, no components, manual review — so
     * the read model stays rectangular across the group. Never counted as an insertion (nothing
     * was read) and never written over a row that already states the coordinate: a deterministic
     * FOUND row is a reading this "empty" cannot contradict, and a deterministic MISSING row
     * already says it. The row's existence does retire the field's region-not-read placeholder.
     */
    private void persistMissingOccurrence(
            LogicalDocument document,
            ExtractionSchemaLoader.SchemaIdentity schema,
            AiValue value,
            Map<String, ExtractedField> byCoordinate,
            StringBuilder digest) {
        String coordinate = coordinate(value.fieldName(), value.groupKey());
        if (!byCoordinate.containsKey(coordinate)) {
            ExtractedField persisted =
                    fields.save(
                            new ExtractedField(
                                    document.getId(),
                                    schema.id(),
                                    value.fieldName(),
                                    value.dataType(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    "NONE",
                                    EXTRACTOR_VERSION + "/" + schema.version(),
                                    BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP),
                                    null,
                                    ExtractedField.VALIDATION_MANUAL_REVIEW_REQUIRED,
                                    ExtractedField.REVIEW_NOT_REVIEWED,
                                    value.sensitive(),
                                    value.groupKey()));
            byCoordinate.put(coordinate, persisted);
            digest.append(document.getId()).append('/').append(coordinate).append("/AI_EMPTY\u001E");
            if (value.groupKey() != null) {
                retireRegionNotReadPlaceholder(document, persisted, byCoordinate, digest);
            }
        }
    }

    /**
     * A declared ROW-group field whose table the deterministic reader could not locate persists
     * ONE occurrence with a null key and method NONE — the read model's "region not read" claim,
     * which the Markdown renders as a manual-review callout. The moment the AI reader has written
     * a KEYED occurrence of that field, the claim is false: the table was read. Leaving the
     * placeholder beside the rows would tell a reviewer "this table was never read" next to the
     * table, so an UNREVIEWED placeholder is retired here.
     *
     * <p>A REVIEWED placeholder is a different thing: a real row a human has already confirmed,
     * corrected or rejected by hand — exactly the NONE-row-a-human-filled-in case the precedence
     * comment in {@link #persist} names — and deleting it would destroy that decision silently,
     * because {@code ReviewCarryForward.apply(before, List.of())} has nothing to rebind onto and
     * returns 0 without a word. So it is KEPT, and the keyed row that now sits beside it goes back
     * in front of a reviewer ({@link ExtractedField#markCarriedReviewNeedsRecheck}): the human's
     * call was made against a table the machine has since read differently. Logged with both ids
     * so the decision is findable.
     *
     * <p>Only the NULL-keyed, method-NONE row is touched. A keyed MISSING row the deterministic
     * reader emitted for a row it located but could not read is an honest per-row absence claim
     * and stays. An ungrouped field never reaches here.
     *
     * @param keyedRow the occurrence just written under a row key for the placeholder's field
     */
    private void retireRegionNotReadPlaceholder(
            LogicalDocument document,
            ExtractedField keyedRow,
            Map<String, ExtractedField> byCoordinate,
            StringBuilder digest) {
        String placeholderCoordinate = coordinate(keyedRow.getFieldName(), null);
        ExtractedField placeholder = byCoordinate.get(placeholderCoordinate);
        if (placeholder == null
                || placeholder.getGroupKey() != null
                || !"NONE".equals(placeholder.getExtractionMethod())) {
            return;
        }
        if (!ExtractedField.REVIEW_NOT_REVIEWED.equals(placeholder.getReviewStatus())) {
            keyedRow.markCarriedReviewNeedsRecheck();
            fields.save(keyedRow);
            log.info(
                    "reviewed region-not-read placeholder kept, keyed row sent for recheck:"
                            + " document={} field={} placeholderId={} placeholderReview={}"
                            + " keyedRowId={} groupKey={}",
                    document.getId(),
                    keyedRow.getFieldName(),
                    placeholder.getId(),
                    placeholder.getReviewStatus(),
                    keyedRow.getId(),
                    keyedRow.getGroupKey());
            digest.append(document.getId())
                    .append('/')
                    .append(coordinate(keyedRow.getFieldName(), keyedRow.getGroupKey()))
                    .append("/RECHECK_REVIEWED_PLACEHOLDER\u001E");
            return;
        }
        fields.delete(placeholder);
        fields.flush();
        byCoordinate.remove(placeholderCoordinate);
        digest.append(document.getId())
                .append('/')
                .append(placeholderCoordinate)
                .append("/REGION_READ_BY_AI\u001E");
    }

    private ExtractedField toField(
            UUID documentId,
            ExtractionSchemaLoader.SchemaIdentity schema,
            TypeProfile profile,
            AiValue value,
            AiEvidenceAnchor.Match anchor,
            ReconciliationView reconciliation) {
        // THE contract, not a local variant: spanConfidence x anchorStrength x normalizerCertainty,
        // computed by the same ConfidenceBreakdown the deterministic extractors use. This path used
        // to store the model's OWN self-report as the score and record the other two components
        // beside it without ever multiplying them in, so a value with zero span confidence and zero
        // anchor strength still reported 0.90 -- the panel read "90% confidence" next to
        // "Unanchored" on 22 fields of one statement. Sharing the class is the point: two
        // implementations of one formula is how they drifted apart in the first place.
        ConfidenceBreakdown breakdown = breakdown(value, anchor);
        BigDecimal confidence = breakdown.overall();
        return new ExtractedField(
                documentId,
                schema.id(),
                value.fieldName(),
                value.dataType(),
                value.printedText(),
                value.printedText(),
                value.normalizedText(),
                value.normalizedNumber(),
                value.normalizedDate(),
                evidenceJson(value, anchor, reconciliation),
                "AI",
                EXTRACTOR_VERSION + "/" + schema.version(),
                confidence,
                confidenceJson(value.confidence(), breakdown, value.page(), anchor, reconciliation),
                validationFor(value, anchor, reconciliation, profile),
                ExtractedField.REVIEW_NOT_REVIEWED,
                value.sensitive(),
                value.groupKey());
    }

    private Map<Integer, List<TextSpan>> evidencePages(LogicalDocument document) {
        Map<Integer, List<TextSpan>> result = new HashMap<>();
        for (LogicalDocumentPage link :
                documentPages.findByLogicalDocumentIdOrderByOrdinal(document.getId())) {
            Page page =
                    pages.findByIdAndOrgId(link.getPageId(), TenantContext.require()).orElseThrow();
            result.put(
                    page.getPackagePageIndex() + 1,
                    spans.findByPageIdOrderBySourceAscOrdinalAsc(page.getId()));
        }
        return Map.copyOf(result);
    }

    private void persistEvidence(
            ExtractedField field, AiValue value, AiEvidenceAnchor.Match anchor) {
        if (anchor.status() != AiEvidenceAnchor.Status.MATCHED) {
            return;
        }
        int ordinal = 0;
        for (TextSpan span : anchor.spans()) {
            evidence.save(
                    new FieldEvidence(
                            field.getId(),
                            span.getPageId(),
                            null,
                            span.getId(),
                            span.getX(),
                            span.getY(),
                            span.getWidth(),
                            span.getHeight(),
                            value.derivedFromField() != null
                                    ? FieldEvidence.ROLE_CONTEXT
                                    : FieldEvidence.ROLE_VALUE,
                            ordinal++));
        }
    }

    private List<AiValue> valuesOf(BankStatementExtraction extraction) {
        List<AiValue> values = new ArrayList<>();
        BankStatementExtraction.Summary summary = extraction.summary();
        add(values, "bankName", summary.bankName(), false);
        add(values, "accountHolderName", summary.accountHolderName(), false);
        add(values, "accountHolderAddress", summary.accountHolderAddress(), true);
        add(values, "accountNumber", summary.accountNumber(), true);
        add(values, "statementPeriodStart", summary.statementPeriodStart(), false);
        add(values, "statementPeriodEnd", summary.statementPeriodEnd(), false);
        add(values, "beginningBalance", summary.beginningBalance(), false);
        add(values, "endingBalance", summary.endingBalance(), false);
        add(values, "totalDeposits", summary.totalDeposits(), false);
        add(values, "totalWithdrawals", summary.totalWithdrawals(), false);

        int ordinal = 0;
        for (Txn transaction : extraction.transactions()) {
            String key = "%06d".formatted(++ordinal);
            add(values, "transactionDate", transaction.date(), false, key, transaction.page());
            add(
                    values,
                    "transactionDescription",
                    transaction.description(),
                    false,
                    key,
                    transaction.page());
            add(
                    values,
                    "transactionAmount",
                    transaction.amount(),
                    false,
                    key,
                    transaction.page());
            add(
                    values,
                    "transactionBalance",
                    transaction.balance(),
                    false,
                    key,
                    transaction.page());
            values.add(
                    new AiValue(
                            "transactionDirection",
                            "ENUM",
                            transaction.direction() == null ? null : transaction.direction().name(),
                            transaction.direction() == null ? null : transaction.direction().name(),
                            null,
                            null,
                            transaction.page(),
                            Confidence.MEDIUM,
                            false,
                            key,
                            null,
                            "transactionAmount"));
        }

        Set<String> usedCheckKeys = new HashSet<>();
        ordinal = 0;
        for (Check check : extraction.checks()) {
            ordinal++;
            String printedKey =
                    check.checkNumber() == null
                            ? null
                            : firstNonBlank(
                                    check.checkNumber().value(), check.checkNumber().text());
            String key = printedKey == null ? "%06d".formatted(ordinal) : printedKey;
            if (!usedCheckKeys.add(key)) {
                key = key + "-" + "%06d".formatted(ordinal);
                usedCheckKeys.add(key);
            }
            add(values, "checkNumber", check.checkNumber(), false, key, check.page());
            add(values, "checkDatePaid", check.datePaid(), false, key, check.page());
            add(values, "checkAmount", check.amount(), false, key, check.page());
        }
        return values;
    }

    private static void add(List<AiValue> values, String name, TextCell cell, boolean sensitive) {
        add(values, name, cell, sensitive, null, null);
    }

    private static void add(
            List<AiValue> values,
            String name,
            TextCell cell,
            boolean sensitive,
            String key,
            Integer fallbackPage) {
        if (cell != null) {
            values.add(
                    new AiValue(
                            name,
                            "STRING",
                            cell.text(),
                            cell.value(),
                            null,
                            null,
                            cell.page() == null ? fallbackPage : cell.page(),
                            cell.confidence(),
                            sensitive,
                            key,
                            cell.handwritten()));
        }
    }

    private static void add(List<AiValue> values, String name, DateCell cell, boolean sensitive) {
        add(values, name, cell, sensitive, null, null);
    }

    private static void add(
            List<AiValue> values,
            String name,
            DateCell cell,
            boolean sensitive,
            String key,
            Integer fallbackPage) {
        if (cell != null) {
            values.add(
                    new AiValue(
                            name,
                            "DATE",
                            cell.text(),
                            null,
                            null,
                            cell.value(),
                            cell.page() == null ? fallbackPage : cell.page(),
                            cell.confidence(),
                            sensitive,
                            key,
                            cell.handwritten()));
        }
    }

    private static void add(List<AiValue> values, String name, MoneyCell cell, boolean sensitive) {
        add(values, name, cell, sensitive, null, null);
    }

    private static void add(
            List<AiValue> values,
            String name,
            MoneyCell cell,
            boolean sensitive,
            String key,
            Integer fallbackPage) {
        if (cell != null) {
            values.add(
                    new AiValue(
                            name,
                            "MONEY",
                            cell.text(),
                            null,
                            cell.value(),
                            null,
                            cell.page() == null ? fallbackPage : cell.page(),
                            cell.confidence(),
                            sensitive,
                            key,
                            cell.handwritten()));
        }
    }

    private static boolean sameValue(ExtractedField existing, AiValue incoming) {
        return switch (incoming.dataType()) {
            case "DATE" -> Objects.equals(existing.getNormalizedDate(), incoming.normalizedDate());
            case "MONEY", "NUMBER" ->
                    numbersEqual(existing.getNormalizedNumber(), incoming.normalizedNumber());
            default -> Objects.equals(existing.getNormalizedText(), incoming.normalizedText());
        };
    }

    private static boolean numbersEqual(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private String evidenceJson(
            AiValue value, AiEvidenceAnchor.Match anchor, ReconciliationView reconciliation) {
        Map<String, Object> aiEvidence =
                compactMap("page", value.page(), "text", value.printedText());
        aiEvidence.put("anchorStatus", anchor.status().name());
        // Provenance, not a score: UNIQUE means the printed text occurs once on the cited page,
        // ROW_SCOPED means it occurs several times and the row's geometry chose one. A reviewer
        // auditing a value is entitled to know which of the two vouches for it.
        aiEvidence.put("anchorResolution", anchor.resolution().name());
        // The WHY behind a Phase H review flag, in the evidence a reviewer opens: the queue must
        // read "verify this handwritten amount", never an unexplained flag.
        List<String> reviewReasons = reviewReasons(value, anchor);
        if (!reviewReasons.isEmpty()) {
            aiEvidence.put("reviewReasons", reviewReasons);
        }
        if (value.derivedFromField() != null) {
            aiEvidence.put("derivedFrom", value.derivedFromField());
        }
        return json(
                Map.of(
                        "aiEvidence", aiEvidence,
                        "reconciliation", reconciliation.map()));
    }

    /** The three components a value's score was actually built from — never recomputed here. */
    private ConfidenceBreakdown breakdown(AiValue value, AiEvidenceAnchor.Match anchor) {
        return new ConfidenceBreakdown(
                spanConfidence(anchor),
                anchor.status() == AiEvidenceAnchor.Status.MATCHED
                        ? BigDecimal.ONE
                        : BigDecimal.ZERO,
                score(value.confidence()));
    }

    private String confidenceJson(
            Confidence label,
            ConfidenceBreakdown breakdown,
            Integer page,
            AiEvidenceAnchor.Match anchor,
            ReconciliationView reconciliation) {
        Map<String, Object> components = new LinkedHashMap<>();
        // Emitted FROM the breakdown that produced the persisted score, so the audit trail and the
        // number can never disagree the way they did before.
        components.put("spanConfidence", breakdown.spanConfidence());
        components.put("anchorStrength", breakdown.anchorStrength());
        components.put("normalizerCertainty", breakdown.normalizerCertainty());
        components.put("modelSelfReported", label == null ? "LOW" : label.name());
        components.put("anchorStatus", anchor.status().name());
        components.put("reconciliationStatus", reconciliation.statusName());
        if (page != null) {
            components.put("sourcePage", page);
        }
        return json(components);
    }

    /**
     * The MINIMUM of the anchored spans' confidences, per {@link ConfidenceBreakdown}: "one shaky
     * word taints the whole value". This path averaged them instead, which let a garbage OCR glyph
     * hide inside a long description — seven clean spans at 1.0 and one at 0.2 averaged to 0.9 and
     * read as trustworthy. On a native text layer every span is 1.0, so the two agree there and only
     * OCR pages move, always downward.
     *
     * <p>A span with no recorded confidence is not evidence of a good read, so it floors the value.
     */
    static BigDecimal spanConfidence(AiEvidenceAnchor.Match anchor) {
        if (anchor.status() != AiEvidenceAnchor.Status.MATCHED || anchor.spans().isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal weakest = null;
        for (TextSpan span : anchor.spans()) {
            BigDecimal spanConfidence =
                    span == null || span.getConfidence() == null
                            ? BigDecimal.ZERO
                            : span.getConfidence();
            weakest = weakest == null ? spanConfidence : weakest.min(spanConfidence);
        }
        return weakest == null
                ? BigDecimal.ZERO
                : weakest.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private String validationFor(
            AiValue value,
            AiEvidenceAnchor.Match anchor,
            ReconciliationView reconciliation,
            TypeProfile profile) {
        // Phase H (owner decision: flag, don't bend the math). The three confidence components
        // stay honest measurements; ASSERTION is controlled here, at validationStatus, by an OR
        // of one structural rule and two mechanical ones. Any review reason wins over every
        // other status a value might have earned — including a reconciled VALID, because a
        // reconciliation over a misread handwritten amount reconciles the misreading.
        if (!reviewReasons(value, anchor).isEmpty()) {
            return ExtractedField.VALIDATION_MANUAL_REVIEW_REQUIRED;
        }
        // VALID is earned only by a type's arithmetic proof. A type with no reconciliation
        // gates nothing: its anchored values stay NOT_VALIDATED — anchoring locates a value,
        // it does not vouch for its correctness the way a closing ledger equation does.
        if (profile.reconciliationGated(value.fieldName())) {
            return reconciliation.reconciled()
                    ? ExtractedField.VALIDATION_VALID
                    : ExtractedField.VALIDATION_MANUAL_REVIEW_REQUIRED;
        }
        return ExtractedField.VALIDATION_NOT_VALIDATED;
    }

    /**
     * WHY a value is review-flagged (Phase H) — surfaced so the queue reads "verify this
     * handwritten amount", never an unexplained flag:
     *
     * <ul>
     *   <li>{@code UNANCHORED} — structural: the quoted text matched no persisted span (or
     *       matched ambiguously), so nothing on the page vouches for it;
     *   <li>{@code HANDWRITTEN} — the model itself tagged the printed characters handwritten;
     *   <li>{@code WEAK_ANCHOR} — mechanical: the value is anchored ONLY to OCR spans that all
     *       sit below the span-confidence floor — a match against garbage glyphs is not proof,
     *       and this is exactly the hole a handwriting misread slips through.
     * </ul>
     *
     * <p>No fourth confidence component, no normalizerCertainty cap: the components keep
     * reporting what they measured, and the ceiling lives entirely in what the engine is willing
     * to ASSERT.
     */
    private List<String> reviewReasons(AiValue value, AiEvidenceAnchor.Match anchor) {
        List<String> reasons = new ArrayList<>();
        if (anchor.status() != AiEvidenceAnchor.Status.MATCHED) {
            reasons.add("UNANCHORED");
        }
        if (value.modelTaggedHandwritten()) {
            reasons.add("HANDWRITTEN");
        }
        if (anchor.status() == AiEvidenceAnchor.Status.MATCHED && weakOcrAnchor(anchor)) {
            reasons.add("WEAK_ANCHOR");
        }
        return reasons;
    }

    /** Every anchored span is OCR-sourced AND below the floor — no trustworthy glyph vouches. */
    private boolean weakOcrAnchor(AiEvidenceAnchor.Match anchor) {
        if (anchor.spans().isEmpty()) {
            return false;
        }
        for (TextSpan span : anchor.spans()) {
            if (span.getSource() != com.pragmaticds.docengine.parsing.domain.SpanSource.OCR) {
                return false;
            }
            BigDecimal confidence = span.getConfidence();
            if (confidence != null
                    && confidence.compareTo(handwritingSpanConfidenceFloor) >= 0) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> conflictSuggestion(
            ExtractedField existing,
            AiValue proposed,
            AiEvidenceAnchor.Match anchor,
            ReconciliationView reconciliation) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("fieldId", existing.getId());
        suggestion.put("fieldName", proposed.fieldName());
        if (proposed.groupKey() != null) {
            suggestion.put("groupKey", proposed.groupKey());
        }
        suggestion.put("dataType", proposed.dataType());
        suggestion.put("suggestedText", proposed.printedText());
        suggestion.put("normalizedText", proposed.normalizedText());
        suggestion.put("normalizedNumber", proposed.normalizedNumber());
        suggestion.put("normalizedDate", proposed.normalizedDate());
        suggestion.put("page", proposed.page());
        suggestion.put("anchorStatus", anchor.status().name());
        suggestion.put("evidence", anchorEvidence(anchor));
        suggestion.put("reconciliationStatus", reconciliation.statusName());
        return suggestion;
    }

    private static List<Map<String, Object>> anchorEvidence(AiEvidenceAnchor.Match anchor) {
        if (anchor.status() != AiEvidenceAnchor.Status.MATCHED) {
            return List.of();
        }
        return anchor.spans().stream()
                .map(
                        span ->
                                Map.<String, Object>of(
                                        "spanId", span.getId(),
                                        "pageId", span.getPageId(),
                                        "x", span.getX(),
                                        "y", span.getY(),
                                        "width", span.getWidth(),
                                        "height", span.getHeight()))
                .toList();
    }

    private String providerCallJson(
            DocumentResult call, PersistResult persisted, String applicationStatus) {
        return providerCallJson(call, persisted, applicationStatus, null);
    }

    /**
     * @param failureReason the engine's own reason for an ERROR row, used when the provider
     *     result carries none (or there was no provider call at all — an input-limit rejection)
     */
    private String providerCallJson(
            DocumentResult call,
            PersistResult persisted,
            String applicationStatus,
            String failureReason) {
        AiExtractionResult providerResult = call.providerResult();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("kind", "PROVIDER_CALL");
        record.put("applicationStatus", applicationStatus);
        record.put(
                "providerStatus",
                providerResult == null || providerResult.status() == null
                        ? "UNKNOWN"
                        : providerResult.status().name());
        if (providerResult != null && providerResult.reason() != null) {
            record.put("reason", providerResult.reason());
        } else if (failureReason != null) {
            record.put("reason", failureReason);
        }
        record.put("latencyMs", call.latencyMs());
        record.put("outputSchemaVersion", call.profile().outputSchemaVersion());
        record.put("costStatus", "NOT_REPORTED_BY_PROVIDER");
        if (providerResult != null && providerResult.tokenCounts() != null) {
            record.put(
                    "cacheReadInputTokens",
                    providerResult.tokenCounts().cacheReadInputTokens());
            record.put(
                    "cacheWriteInputTokens",
                    providerResult.tokenCounts().cacheWriteInputTokens());
        }
        if (persisted != null) {
            record.put("fieldsInserted", persisted.counts().inserted());
            record.put("conflictCount", persisted.counts().conflicts());
            record.put("reconciliation", persisted.reconciliation().map());
            record.put("conflicts", persisted.conflictSuggestions());
        } else {
            record.put("conflictCount", 0);
            record.put("conflicts", List.of());
        }
        return json(record);
    }

    private static Map<String, Object> reconciliationMap(
            BankStatementReconciler.Result reconciliation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", reconciliation.status().name());
        result.put("ledgerEquation", reconciliation.ledgerEquation().name());
        result.put("partition", reconciliation.partition().name());
        result.put("runningBalances", reconciliation.runningBalances().name());
        return result;
    }

    /**
     * The paystub breakdown a reviewer opens, embedded verbatim in every participating field's
     * evidence. Every check is named, including the ones that could not be run: a reviewer must be
     * able to tell "the deductions table did not sum" from "no deductions table was read".
     */
    private static Map<String, Object> reconciliationMap(PaystubReconciler.Result reconciliation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", reconciliation.status().name());
        result.put("currentNetIdentity", reconciliation.currentNetIdentity().name());
        result.put("ytdNetIdentity", reconciliation.ytdNetIdentity().name());
        result.put("ytdCoversCurrent", reconciliation.ytdCoversCurrent().name());
        result.put("earningsPartition", reconciliation.earningsPartition().name());
        result.put("deductionsPartition", reconciliation.deductionsPartition().name());
        result.put("earningsLineProducts", reconciliation.earningsLineProducts().name());
        result.put("periodCoherence", reconciliation.periodCoherence().name());
        return result;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI metadata serialization failed", e);
        }
    }

    private static Map<String, Object> compactMap(
            String firstKey, Object first, String secondKey, Object second) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (first != null) {
            result.put(firstKey, first);
        }
        if (second != null) {
            result.put(secondKey, second);
        }
        return result;
    }

    private static BigDecimal score(Confidence confidence) {
        return switch (confidence == null ? Confidence.LOW : confidence) {
            case HIGH -> new BigDecimal("0.9000");
            case MEDIUM -> new BigDecimal("0.6000");
            case LOW -> new BigDecimal("0.3000");
        };
    }

    private static String coordinate(String name, String key) {
        return name + "#" + (key == null ? "" : key);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private record DocumentResult(
            LogicalDocument document,
            TypeProfile profile,
            AiStructuredExtraction extraction,
            AiExtractionResult providerResult,
            long latencyMs) {}

    private static final class InputLimitExceeded extends RuntimeException {
        private InputLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private record PersistCounts(int inserted, int conflicts) {
        static final PersistCounts NONE = new PersistCounts(0, 0);

        PersistCounts plus(PersistCounts other) {
            return new PersistCounts(inserted + other.inserted, conflicts + other.conflicts);
        }
    }

    /**
     * One document's first-pass outcome from {@link #extractOne}: either persisted and recorded
     * {@code APPLIED} (with its counts, and the result the second pass needs), or recorded {@code
     * ERROR} with the reason.
     */
    private record DocumentOutcome(
            DocumentResult result, PersistCounts counts, String failureReason) {

        static DocumentOutcome completed(DocumentResult result, PersistCounts counts) {
            return new DocumentOutcome(result, counts, null);
        }

        static DocumentOutcome failed(String reason) {
            return new DocumentOutcome(null, PersistCounts.NONE, reason);
        }

        boolean failed() {
            return failureReason != null;
        }
    }

    private record PersistResult(
            PersistCounts counts,
            ReconciliationView reconciliation,
            List<Map<String, Object>> conflictSuggestions) {}

    /**
     * The type-neutral view of a reconciliation outcome the persist pipeline consumes: what to
     * report ({@code statusName}, {@code map} — embedded verbatim in evidence and the ledger)
     * and what it proves ({@code reconciled}). A type with no arithmetic check reports
     * {@code NOT_APPLICABLE} and proves nothing.
     */
    private record ReconciliationView(
            String statusName, Map<String, Object> map, boolean reconciled) {}

    /**
     * @param derivedFromField when non-null, this value was NOT read off the page — it was
     *     derived from the named sibling field in the same group (transactionDirection from
     *     transactionAmount's sign column). It inherits that field's anchor and its evidence
     *     spans carry ROLE_CONTEXT, because quoting it verbatim is impossible by construction.
     */
    private record AiValue(
            String fieldName,
            String dataType,
            String printedText,
            String normalizedText,
            BigDecimal normalizedNumber,
            LocalDate normalizedDate,
            Integer page,
            Confidence confidence,
            boolean sensitive,
            String groupKey,
            Boolean handwritten,
            String derivedFromField,
            boolean missingOccurrence) {

        /** A value that may or may not have been read, derived from a sibling or from nothing. */
        AiValue(
                String fieldName,
                String dataType,
                String printedText,
                String normalizedText,
                BigDecimal normalizedNumber,
                LocalDate normalizedDate,
                Integer page,
                Confidence confidence,
                boolean sensitive,
                String groupKey,
                Boolean handwritten,
                String derivedFromField) {
            this(
                    fieldName,
                    dataType,
                    printedText,
                    normalizedText,
                    normalizedNumber,
                    normalizedDate,
                    page,
                    confidence,
                    sensitive,
                    groupKey,
                    handwritten,
                    derivedFromField,
                    false);
        }

        /** Pre-seam shape: a value read directly off the page, derived from nothing. */
        AiValue(
                String fieldName,
                String dataType,
                String printedText,
                String normalizedText,
                BigDecimal normalizedNumber,
                LocalDate normalizedDate,
                Integer page,
                Confidence confidence,
                boolean sensitive,
                String groupKey,
                Boolean handwritten) {
            this(
                    fieldName,
                    dataType,
                    printedText,
                    normalizedText,
                    normalizedNumber,
                    normalizedDate,
                    page,
                    confidence,
                    sensitive,
                    groupKey,
                    handwritten,
                    null);
        }

        /**
         * A cell of a ROW the model READ that it left empty — the AI counterpart of the
         * deterministic engine's keyed {@code FieldOutcome.missing(field, key)} (design D5): the
         * row exists, so its coordinate is written as an explicit MISSING rather than skipped.
         * Skipping it would leave the group ragged, and a ragged group is not one table — the
         * Markdown partitions clusters by exact key sequence, so a line without hours would
         * split the earnings table into "Earning" and "Earning Hours". No value is ever
         * invented for it: missing over wrong still holds, {@link #present()} is false, and the
         * persist path writes method NONE with zero confidence.
         */
        static AiValue missingOccurrence(String fieldName, String dataType, String groupKey) {
            return new AiValue(
                    fieldName,
                    dataType,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    groupKey,
                    null,
                    null,
                    true);
        }

        boolean present() {
            return normalizedText != null || normalizedNumber != null || normalizedDate != null;
        }

        boolean modelTaggedHandwritten() {
            return Boolean.TRUE.equals(handwritten);
        }
    }

    /**
     * Everything type-specific the shared persist pipeline needs for one document type. The
     * pipeline itself — anchoring, precedence, review reasons, the handwriting ceiling, evidence
     * and ledger shapes — is identical for every type; a profile only answers what to extract,
     * how its typed extraction becomes field values, and whether an arithmetic proof exists.
     */
    private interface TypeProfile {
        String typeCode();

        AiDocumentType aiType();

        String promptVersion();

        String secondPassPromptVersion();

        String outputSchemaVersion();

        String outputSchemaJson();

        List<AiValue> values(AiStructuredExtraction extraction);

        ReconciliationView reconcile(AiStructuredExtraction extraction);

        /** Is this field's validation earned (or lost) by the type's reconciliation? */
        boolean reconciliationGated(String fieldName);

        /**
         * Which repeating group this field's occurrences belong to, or null when the field is not
         * in one. Row keys are unique only WITHIN a group — a check with no printed number and the
         * first transaction are both {@code 000001} — so row-scoped anchoring needs the group to
         * partition on. The knowledge is type-specific, so it lives here rather than in the
         * resolver.
         */
        String rowGroupOf(String fieldName);
    }

    private final class BankStatementTypeProfile implements TypeProfile {
        @Override
        public String typeCode() {
            return BANK_STATEMENT;
        }

        @Override
        public AiDocumentType aiType() {
            return AiDocumentType.BANK_STATEMENT;
        }

        @Override
        public String promptVersion() {
            return PROMPT_VERSION;
        }

        @Override
        public String secondPassPromptVersion() {
            return SECOND_PASS_PROMPT_VERSION;
        }

        @Override
        public String outputSchemaVersion() {
            return OUTPUT_SCHEMA_VERSION;
        }

        @Override
        public String outputSchemaJson() {
            return outputSchema.schemaJson();
        }

        @Override
        public List<AiValue> values(AiStructuredExtraction extraction) {
            return valuesOf((BankStatementExtraction) extraction);
        }

        @Override
        public ReconciliationView reconcile(AiStructuredExtraction extraction) {
            BankStatementReconciler.Result result =
                    reconciler.reconcile((BankStatementExtraction) extraction);
            return new ReconciliationView(
                    result.status().name(),
                    reconciliationMap(result),
                    result.status() == BankStatementReconciler.Status.RECONCILED);
        }

        @Override
        public boolean reconciliationGated(String fieldName) {
            return fieldName.startsWith("transaction")
                    || RECONCILED_SUMMARY_FIELDS.contains(fieldName);
        }

        @Override
        public String rowGroupOf(String fieldName) {
            if (fieldName.startsWith("transaction")) {
                return "transactions";
            }
            if (fieldName.startsWith("check")) {
                return "checks";
            }
            return null; // summary fields stand alone
        }
    }

    private final class PaystubTypeProfile implements TypeProfile {
        @Override
        public String typeCode() {
            return PAYSTUB;
        }

        @Override
        public AiDocumentType aiType() {
            return AiDocumentType.PAYSTUB;
        }

        @Override
        public String promptVersion() {
            return PAYSTUB_PROMPT_VERSION;
        }

        @Override
        public String secondPassPromptVersion() {
            return PAYSTUB_SECOND_PASS_PROMPT_VERSION;
        }

        @Override
        public String outputSchemaVersion() {
            return PAYSTUB_OUTPUT_SCHEMA_VERSION;
        }

        @Override
        public String outputSchemaJson() {
            return paystubOutputSchema.schemaJson();
        }

        @Override
        public List<AiValue> values(AiStructuredExtraction extraction) {
            return paystubValuesOf((PaystubExtraction) extraction);
        }

        /**
         * A paystub DOES carry a closed equation once the stub's own total deductions (or a
         * complete deductions table) is read alongside gross and net — see {@link
         * PaystubReconciler}, which also honours the reason this used to return NOT_APPLICABLE:
         * gross minus a SINGLE extracted deduction still proves nothing, and never earns a pass.
         */
        @Override
        public ReconciliationView reconcile(AiStructuredExtraction extraction) {
            PaystubReconciler.Result result =
                    paystubReconciler.reconcile((PaystubExtraction) extraction);
            return new ReconciliationView(
                    result.status().name(), reconciliationMap(result), result.reconciled());
        }

        /**
         * Exactly the persisted values the current-period identity {@code gross - deductions =
         * net} contains, and not one field more. A closed identity says nothing about whose name
         * is on the stub, who issued it, when the period ran, or how much of the deductions was
         * federal — promoting any of those on the strength of this arithmetic would be asserting
         * more than was proved, which is the failure this whole class of check exists to prevent.
         *
         * <p>{@code federalWithholding} is deliberately absent: it is one named deduction inside
         * the total, and the identity holds whether or not that particular row was read correctly.
         */
        @Override
        public boolean reconciliationGated(String fieldName) {
            return RECONCILED_PAYSTUB_FIELDS.contains(fieldName);
        }

        @Override
        public String rowGroupOf(String fieldName) {
            // The earnings lines are paystub@1.5.0's one ROW group; every other field is a single
            // occurrence with a null groupKey, which the resolver skips. Deduction rows are still
            // evidence only — give them their own answer here when they land.
            return fieldName.startsWith(PAYSTUB_EARNINGS_FIELD_PREFIX)
                    ? PAYSTUB_EARNINGS_GROUP
                    : null;
        }
    }

    /**
     * W-2: no arithmetic identity ties its boxes together (Box 1 is legitimately below Boxes 3
     * and 5), so there is nothing to reconcile and nothing is promoted. An AI value is trusted
     * only by page-text corroboration or a human review.
     */
    private final class W2TypeProfile implements TypeProfile {
        @Override
        public String typeCode() {
            return W2;
        }

        @Override
        public AiDocumentType aiType() {
            return AiDocumentType.W2;
        }

        @Override
        public String promptVersion() {
            return W2_PROMPT_VERSION;
        }

        @Override
        public String secondPassPromptVersion() {
            return W2_SECOND_PASS_PROMPT_VERSION;
        }

        @Override
        public String outputSchemaVersion() {
            return W2_OUTPUT_SCHEMA_VERSION;
        }

        @Override
        public String outputSchemaJson() {
            return w2OutputSchema.schemaJson();
        }

        @Override
        public List<AiValue> values(AiStructuredExtraction extraction) {
            return w2ValuesOf((W2Extraction) extraction);
        }

        @Override
        public ReconciliationView reconcile(AiStructuredExtraction extraction) {
            return new ReconciliationView(
                    "NOT_APPLICABLE", Map.of("status", "NOT_APPLICABLE"), false);
        }

        @Override
        public boolean reconciliationGated(String fieldName) {
            return false;
        }

        @Override
        public String rowGroupOf(String fieldName) {
            return null;
        }
    }

    /**
     * Field names mirror the seeded PAYSTUB extraction schema — the landing coordinates: the ten
     * V7 scalars, then paystub@1.5.0's (V53) three closing totals and one {@code earning*}
     * occurrence per earnings line under the ROW group's two-digit ordinal key ({@code 01}, {@code
     * 02}, … in printed order — the key space the loader documents and the read model sorts by).
     * A null cell emits nothing, on every field: missing over wrong.
     */
    private List<AiValue> paystubValuesOf(PaystubExtraction extraction) {
        List<AiValue> values = new ArrayList<>();
        add(values, "borrowerName", extraction.borrowerName(), false);
        add(values, "employerName", extraction.employerName(), false);
        add(values, "payPeriodStart", extraction.payPeriodStart(), false);
        add(values, "payPeriodEnd", extraction.payPeriodEnd(), false);
        add(values, "payDate", extraction.payDate(), false);
        BankStatementExtraction.TextCell frequency = extraction.payFrequency();
        if (frequency != null) {
            // ENUM like the deterministic row: the parser already canonicalized value() with the
            // deterministic normalizer's own rule, so agreement/conflict compares like with like
            // — and an unrecognized printed wording arrives value-null, which present() drops:
            // missing over wrong.
            values.add(
                    new AiValue(
                            "payFrequency",
                            "ENUM",
                            frequency.text(),
                            frequency.value(),
                            null,
                            null,
                            frequency.page(),
                            frequency.confidence(),
                            false,
                            null,
                            frequency.handwritten()));
        }
        add(values, "currentGrossPay", extraction.currentGrossPay(), false);
        add(values, "ytdGrossPay", extraction.ytdGrossPay(), false);
        add(values, "netPay", extraction.netPay(), false);
        add(values, "federalWithholding", extraction.federalWithholding(), false);
        add(values, "currentTotalDeductions", extraction.currentTotalDeductions(), false);
        add(values, "ytdTotalDeductions", extraction.ytdTotalDeductions(), false);
        add(values, "ytdNetPay", extraction.ytdNetPay(), false);

        List<PaystubExtraction.EarningLine> earnings = extraction.earnings();
        if (earnings.size() > PAYSTUB_EARNINGS_MAX_ROWS) {
            // The cap is the schema's maxRows and the key format's ceiling; a line past it has no
            // coordinate to land on. Dropped and said so — never re-keyed, never silently.
            log.warn(
                    "paystub earnings lines exceed the row group cap, extra lines dropped:"
                            + " lines={} cap={}",
                    earnings.size(),
                    PAYSTUB_EARNINGS_MAX_ROWS);
            earnings = earnings.subList(0, PAYSTUB_EARNINGS_MAX_ROWS);
        }
        int ordinal = 0;
        for (PaystubExtraction.EarningLine line : earnings) {
            String key = "%02d".formatted(++ordinal);
            // A line's cells share the line's page; a cell without one rides on its siblings'.
            Integer page = firstPageOf(line);
            // A null cell of a line that EXISTS is an explicit missing occurrence, not silence
            // (AiValue.missingOccurrence): the row is real, the cell is empty, and the table
            // must stay one table. A scalar's null cell above still emits nothing.
            addOrMissing(values, "earningDescription", line.description(), key, page);
            addOrMissing(values, "earningHours", line.hours(), key, page);
            addOrMissing(values, "earningRate", line.rate(), key, page);
            addOrMissing(values, "earningCurrentAmount", line.currentAmount(), key, page);
            addOrMissing(values, "earningYtdAmount", line.ytdAmount(), key, page);
        }
        return values;
    }

    /**
     * Field names mirror the seeded W2 extraction schema (w2@1.3.0, V47). employeeSsn is absent
     * by construction — the dialect never reads it. A null cell emits nothing: missing over wrong.
     */
    private List<AiValue> w2ValuesOf(W2Extraction extraction) {
        List<AiValue> values = new ArrayList<>();
        add(values, "employeeName", extraction.employeeName(), false);
        add(values, "employerName", extraction.employerName(), false);
        add(values, "employerEin", extraction.employerEin(), false);
        add(values, "taxYear", extraction.taxYear(), false);
        add(values, "wagesTipsOtherComp", extraction.wagesTipsOtherComp(), false);
        add(values, "federalIncomeTaxWithheld", extraction.federalIncomeTaxWithheld(), false);
        add(values, "socialSecurityWages", extraction.socialSecurityWages(), false);
        add(values, "medicareWages", extraction.medicareWages(), false);
        add(values, "stateWages", extraction.stateWages(), false);
        return values;
    }

    /**
     * A line's cell with nothing to persist is the row's empty cell, whether the model omitted it
     * ({@code cell == null}) or the parser kept its printed text but dropped the value — {@code
     * money()} returns a NON-null cell with a null {@code value} when the text fails to
     * corroborate the number, and {@code text()} can do the same. Routing the second shape through
     * {@code add()} would emit a value {@link AiValue#present()} rejects, and the key would vanish
     * from that one field: the key sequence diverges and the renderer splits the table.
     */
    private static void addOrMissing(
            List<AiValue> values, String name, TextCell cell, String key, Integer page) {
        if (cell == null || cell.value() == null) {
            values.add(AiValue.missingOccurrence(name, "STRING", key));
            return;
        }
        add(values, name, cell, false, key, page);
    }

    private static void addOrMissing(
            List<AiValue> values, String name, MoneyCell cell, String key, Integer page) {
        if (cell == null || cell.value() == null) {
            values.add(AiValue.missingOccurrence(name, "MONEY", key));
            return;
        }
        add(values, name, cell, false, key, page);
    }

    private static Integer firstPageOf(PaystubExtraction.EarningLine line) {
        if (line.description() != null && line.description().page() != null) {
            return line.description().page();
        }
        for (MoneyCell cell :
                new MoneyCell[] {line.hours(), line.rate(), line.currentAmount(), line.ytdAmount()}) {
            if (cell != null && cell.page() != null) {
                return cell.page();
            }
        }
        return null;
    }

    /**
     * @param documents every eligible document, applied or not
     * @param documentsFailed how many of those were recorded ERROR rather than APPLIED — only ever
     *     non-zero on an APPLIED result (an ERROR result means all of them failed)
     * @param failureReason the first failure's reason, null when every document applied
     */
    public record StageResult(
            Status status,
            String reason,
            String digest,
            int documents,
            int fieldsInserted,
            int conflicts,
            boolean retryable,
            int documentsFailed,
            String failureReason) {
        public enum Status { APPLIED, SKIPPED, ERROR }

        static StageResult applied(String digest, int documents, int inserted, int conflicts) {
            return applied(digest, documents, inserted, conflicts, 0, null);
        }

        static StageResult applied(
                String digest,
                int documents,
                int inserted,
                int conflicts,
                int documentsFailed,
                String failureReason) {
            return new StageResult(
                    Status.APPLIED,
                    null,
                    digest,
                    documents,
                    inserted,
                    conflicts,
                    false,
                    documentsFailed,
                    failureReason);
        }

        static StageResult skipped(String reason) {
            return new StageResult(Status.SKIPPED, reason, null, 0, 0, 0, false, 0, null);
        }

        static StageResult error(int documents, String reason, boolean retryable) {
            return new StageResult(
                    Status.ERROR, reason, null, documents, 0, 0, retryable, documents, reason);
        }
    }
}
