package com.pragmaticds.rag.lab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.dto.ChatRequest;
import com.pragmaticds.rag.dto.ChatResponse;
import com.pragmaticds.rag.lab.analyze.ParsedAnalysisInput;
import com.pragmaticds.rag.lab.analyze.ParsedIncomeAnalysisService;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabDiscussionMessage;
import com.pragmaticds.rag.lab.domain.LabDocumentRegistration;
import com.pragmaticds.rag.lab.domain.LabEnginePackageBinding;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunDocument;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.domain.LabRunReviewSnapshot;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.engine.LabContractException;
import com.pragmaticds.rag.lab.engine.ReviewSnapshot;
import com.pragmaticds.rag.lab.engine.ReviewedFields;
import com.pragmaticds.rag.lab.engine.ReviewedValueOverlay;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabDocumentRegistrationRepository;
import com.pragmaticds.rag.lab.repository.LabEnginePackageBindingRepository;
import com.pragmaticds.rag.lab.repository.LabRunDocumentRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.repository.LabRunReviewSnapshotRepository;
import com.pragmaticds.rag.lab.security.LabCryptoException;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.lab.web.LabDtos;
import com.pragmaticds.rag.repository.AnalysisRunRepository;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisRunRecorder;
import com.pragmaticds.rag.service.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Income Lab prototype's orchestrator: the one place Tasks 1-5 are joined.
 *
 * <p>It owns no persistence, no HTTP, no cryptography, and no analysis of its own. What it owns is
 * <b>order</b> — which refusal happens before which side effect — and that is the whole safety
 * argument of the prototype:
 *
 * <ol>
 *   <li>The retention window and the idempotency key are checked before <em>anything</em>, so a
 *       misconfigured deployment or a careless client never reaches Document Engine.
 *   <li>Brain/instance-scoped registration is resolved before any engine read, so a leaked package
 *       UUID from another brain fails closed rather than fetching that brain's parse.
 *   <li>Verification, parsing, and the stored release's compatibility predicate all run before the
 *       Lab run row is claimed, so an unusable parse costs zero provider attempts and leaves no
 *       {@code PROCESSING} row to recover.
 *   <li>The database transaction is released before the analyzer executes, and the terminal write
 *       is a separate short transaction — a model call never holds a row lock.
 * </ol>
 *
 * <p><b>Idempotency is a short circuit, never a retry.</b> A replayed run key returns the stored row
 * and starts no analyzer execution; a replayed message key returns the stored pair and makes no
 * model call. Nothing here retries a provider on the client's behalf: after a crash the recovery
 * job marks the run {@code INTERRUPTED}, the same key keeps returning that interrupted run, and
 * only an operator with a NEW key starts a fresh attempt.
 *
 * <p><b>No raw-document fallback exists to reach for.</b> This class holds a
 * {@link DocumentEngineClient} that cannot return document bytes and a
 * {@link ParsedIncomeAnalysisService} that cannot accept them. An incompatible or stale parse is a
 * stop, not a degraded mode.
 *
 * <p><b>Every failure is payload-free.</b> Collaborator taxonomies propagate unwrapped for the Lab
 * exception handler to map; anything else is converted to a code here. No message, cause, provider
 * response, engine URI, filename, or parsed value travels out of this class.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.lab", name = "enabled", havingValue = "true")
public class IncomeLabService {

    private static final Logger log = LoggerFactory.getLogger(IncomeLabService.class);

    /** How long a claimed run may hold its processing lease before recovery may interrupt it. */
    public static final Duration RUN_LEASE = Duration.ofMinutes(10);

    /** How long a claimed discussion exchange may hold its lease. */
    public static final Duration EXCHANGE_LEASE = Duration.ofMinutes(5);

    /**
     * The direct-call bounds {@code ChatRequest}'s bean validation would have applied on the
     * controller path. The Lab calls {@link ChatService} directly, so {@code @Valid} never runs —
     * these are re-stated here rather than assumed, and each one fails the request closed instead
     * of quietly dropping facts, output, or history.
     */
    public static final int MAX_QUESTION_CHARS = 4_000;
    public static final int MAX_CONTEXT_CHARS = 16_000;
    public static final int MAX_TRANSCRIPT_TURNS = 12;
    public static final int MAX_TURN_CHARS = 4_000;

    /** The engine's accepted idempotency-key alphabet, re-checked before any work at all. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~:@+-]{1,200}");

    /** Bounded by {@code lab_run.failure_code} / {@code lab_discussion_exchange.failure_code}. */
    private static final int FAILURE_CODE_MAX = 64;

    /** The generic terminal code for a failure with no taxonomy of its own. */
    private static final String LAB_RUN_FAILED = "LAB_RUN_FAILED";

    /**
     * Constructed, not injected — the predicate is a pure function over declarative data, and Task
     * 1 deliberately shipped {@link IncomeEnvelopeCompatibility} unannotated. Injecting it would
     * add a context-startup failure mode for something with no configuration and no state. The
     * vocabulary it evaluates comes from the run's STORED release, never from these defaults.
     */
    private static final IncomeEnvelopeCompatibility COMPATIBILITY =
            new IncomeEnvelopeCompatibility();

    /** Stateless and declarative, like {@link #COMPATIBILITY}: constructed rather than injected. */
    private static final ReviewedValueOverlay OVERLAY = new ReviewedValueOverlay();

    private final DocumentEngineClient engine;
    private final IncomeLabReleaseService releases;
    private final ParsedIncomeAnalysisService analysis;
    private final LabRunTransactionService transactions;
    private final LabRetentionService retention;
    private final LabAuditService audit;
    private final LabPayloadCipher cipher;
    private final ChatService chat;
    private final ObjectMapper mapper;
    private final LabDocumentRegistrationRepository registrations;
    private final LabEnginePackageBindingRepository packageBindings;
    private final LabRunRepository runs;
    private final LabRunDocumentRepository runDocuments;
    private final LabRunPayloadRepository payloads;
    private final LabDiscussionExchangeRepository exchanges;
    private final LabDiscussionMessageRepository messages;
    private final AnalysisRunRepository analysisRuns;
    private final LabRunReviewSnapshotRepository reviewSnapshots;

    public IncomeLabService(DocumentEngineClient engine,
                            IncomeLabReleaseService releases,
                            ParsedIncomeAnalysisService analysis,
                            LabRunTransactionService transactions,
                            LabRetentionService retention,
                            LabAuditService audit,
                            LabPayloadCipher cipher,
                            ChatService chat,
                            ObjectMapper mapper,
                            LabDocumentRegistrationRepository registrations,
                            LabEnginePackageBindingRepository packageBindings,
                            LabRunRepository runs,
                            LabRunDocumentRepository runDocuments,
                            LabRunPayloadRepository payloads,
                            LabDiscussionExchangeRepository exchanges,
                            LabDiscussionMessageRepository messages,
                            AnalysisRunRepository analysisRuns,
                            LabRunReviewSnapshotRepository reviewSnapshots) {
        this.engine = engine;
        this.releases = releases;
        this.analysis = analysis;
        this.transactions = transactions;
        this.retention = retention;
        this.audit = audit;
        this.cipher = cipher;
        this.chat = chat;
        this.mapper = mapper;
        this.registrations = registrations;
        this.packageBindings = packageBindings;
        this.runs = runs;
        this.runDocuments = runDocuments;
        this.payloads = payloads;
        this.exchanges = exchanges;
        this.messages = messages;
        this.analysisRuns = analysisRuns;
        this.reviewSnapshots = reviewSnapshots;
    }

    // ================================================================ instances

    /** The Lab's instances, their pinned releases, and any drift the live pack has introduced. */
    public LabDtos.InstancesResponse instances(UUID brainId) {
        retention.requireConfigured();
        IncomeLabReleaseService.InstanceState state = releases.resolveInstance(brainId);
        if (state.driftDetected()) {
            audit.record(brainId, LabAuditService.RELEASE_DRIFT, LabAuditEvent.Status.SUCCEEDED,
                    LabAuditEvent.SubjectType.RELEASE, state.candidateReleaseId(), null,
                    Map.of("productionReleaseNumber", state.productionReleaseNumber(),
                            "driftDetected", true));
        }
        audit.record(brainId, LabAuditService.INSTANCE_READ, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.INSTANCE, state.productionReleaseId(), null,
                Map.of("instances", 1));
        return new LabDtos.InstancesResponse(List.of(LabDtos.Instance.from(state)),
                LabDtos.Prototype.current());
    }

    // ================================================================ registration

    /**
     * Registers exactly one original with Document Engine and binds it to this brain and instance.
     *
     * <p>Idempotency is the engine's, not a reimplementation of it: the caller's key is forwarded
     * byte-for-byte, so the same key with the same content returns the same package and job, while
     * the same key with different content fails there rather than here. This method's own job is
     * the binding — and it fails closed when the returned package is already bound to a different
     * brain or instance, which is what makes a leaked package UUID useless across brains.
     */
    public LabDtos.RegistrationResponse registerDocument(UUID brainId, String instanceSlug,
                                                         DocumentEngineClient.EngineUpload upload,
                                                         String idempotencyKey) {
        retention.requireConfigured();
        requireIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(upload, "upload");

        DocumentEngineClient.UploadRegistration accepted = engine.register(upload, idempotencyKey);

        Optional<LabDocumentRegistration> existing =
                registrations.findFirstByEnginePackageIdOrderByRegisteredAtAsc(accepted.packageId());
        if (existing.isPresent()) {
            LabDocumentRegistration bound = existing.get();
            if (!bound.getBrainId().equals(brainId)
                    || !bound.getInstanceSlug().equals(instanceSlug)) {
                audit.record(brainId, LabAuditService.REGISTRATION_CREATE,
                        LabAuditEvent.Status.DENIED, LabAuditEvent.SubjectType.REGISTRATION,
                        bound.getId(), LabRequestException.Code.REGISTRATION_CONFLICT.name(),
                        Map.of("crossScope", true));
                throw new LabRequestException(LabRequestException.Code.REGISTRATION_CONFLICT);
            }
            return registrationResponse(bound, accepted, false);
        }

        // V38 makes package ownership explicit: a registration carries a composite foreign key
        // into the binding, so the brain must claim the package before it can register against it.
        // A fresh upload always produces a fresh package id, so this is an insert in practice; the
        // existence check keeps a retried upload of an already-bound package from failing.
        if (packageBindings.findById(accepted.packageId()).isEmpty()) {
            packageBindings.save(new LabEnginePackageBinding(accepted.packageId(), brainId));
        }

        LabDocumentRegistration created = new LabDocumentRegistration();
        created.setBrainId(brainId);
        created.setInstanceSlug(instanceSlug);
        created.setEnginePackageId(accepted.packageId());
        created.setEngineJobId(accepted.jobId());
        created.setEngineSourceId(firstSourceId(accepted));
        LabDocumentRegistration saved = registrations.save(created);

        audit.record(brainId, LabAuditService.REGISTRATION_CREATE, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.REGISTRATION, saved.getId(), null,
                Map.of("sources", accepted.sources().size(),
                        "duplicates", accepted.duplicateShaPrefixes().size()));
        return registrationResponse(saved, accepted, true);
    }

    // ================================================================ document status

    /**
     * One registered package's processing state, read from the exact engine job.
     *
     * <p>{@code COMPLETED} is analyzable. {@code HUMAN_REVIEW_REQUIRED} is <em>also</em> analyzable
     * — the prototype's whole point is looking at what the parser produced — but it carries a
     * prominent warning rather than being quietly treated as clean. Everything else is blocked:
     * failed, still running, absent, or belonging to another package.
     */
    public LabDtos.DocumentStatusResponse documentStatus(UUID brainId, UUID packageId, UUID jobId) {
        retention.requireConfigured();
        LabDocumentRegistration registration =
                requireRegistration(brainId, IncomeLabReleaseService.INCOME_INSTANCE_SLUG,
                        packageId);
        if (jobId != null && !jobId.equals(registration.getEngineJobId())) {
            throw new LabRequestException(LabRequestException.Code.JOB_IDENTITY_MISMATCH);
        }

        DocumentEngineClient.JobSnapshot snapshot = engine.job(registration.getEngineJobId());
        if (!packageId.equals(snapshot.packageId())) {
            audit.record(brainId, LabAuditService.DOCUMENT_STATUS_READ, LabAuditEvent.Status.DENIED,
                    LabAuditEvent.SubjectType.REGISTRATION, registration.getId(),
                    LabRequestException.Code.JOB_IDENTITY_MISMATCH.name(), Map.of());
            throw new LabRequestException(LabRequestException.Code.JOB_IDENTITY_MISMATCH);
        }

        boolean completed = "COMPLETED".equals(snapshot.status());
        boolean reviewRequired = "HUMAN_REVIEW_REQUIRED".equals(snapshot.status());
        List<String> warnings = new ArrayList<>();
        if (reviewRequired) {
            warnings.add("HUMAN_REVIEW_REQUIRED");
        }

        List<LabDtos.Revision> revisions = engine.revisionHistory(packageId).stream()
                .map(IncomeLabService::revisionOf)
                .toList();

        audit.record(brainId, LabAuditService.DOCUMENT_STATUS_READ, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.REGISTRATION, registration.getId(), null,
                Map.of("revisions", revisions.size(), "analyzable", completed || reviewRequired));

        return new LabDtos.DocumentStatusResponse(registration.getId(), packageId,
                registration.getEngineJobId(), snapshot.status(), snapshot.currentStage(),
                completed || reviewRequired, warnings, revisions, LabDtos.Prototype.current());
    }

    // ================================================================ envelope view

    /**
     * The verified parse of one exact revision, authorized through the same registration.
     *
     * <p>Backend-credentialed by construction: the engine credential lives in the adapter's
     * configuration and has no member on any record returned from here. The compatibility decision
     * shown alongside the data is the STORED release's, not the shipped defaults — so what the
     * browser sees about acceptability matches what a run would actually do.
     */
    public LabDtos.EnvelopeResponse envelope(UUID brainId, UUID packageId, Integer revision) {
        retention.requireConfigured();
        LabDocumentRegistration registration =
                requireRegistration(brainId, IncomeLabReleaseService.INCOME_INSTANCE_SLUG,
                        packageId);

        DocumentEngineClient.VerifiedEnvelope verified = fetchRevision(packageId, revision);
        LabReleaseManifest manifest = releases.resolveForRun(brainId).manifest();
        ReviewedInput reviewed = overlayIfPinned(manifest, verified);
        IncomeEnvelopeCompatibility.Decision decision =
                COMPATIBILITY.evaluate(reviewed.verified().envelope(), policyOf(manifest));

        // Fail closed: a parsed-value read that cannot be attributed does not happen.
        auditRequired(brainId, LabAuditService.ENVELOPE_READ, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.ENVELOPE, registration.getId(), null,
                Map.of("revision", reviewed.verified().revision(),
                        "documents", reviewed.verified().envelope().documents().size(),
                        "pages", reviewed.verified().envelope().pages().size(),
                        "compatible", decision.compatible(),
                        "warnings", decision.warnings().size()));

        return LabDtos.EnvelopeResponse.of(registration.getId(), reviewed.verified().envelope(),
                reviewed.verified().revision(), reviewed.verified().artifact().sha256(),
                reviewed.verified().artifact().byteCount(), decision);
    }

    // ================================================================ runs

    /**
     * Starts, or replays, one Lab run.
     *
     * <p>The sequence below is the contract. Everything before {@code claimRun} is free to fail
     * without leaving a trace; everything after it is bounded by the lease and ends in exactly one
     * terminal transition.
     */
    public LabDtos.RunResponse startRun(UUID brainId, String instanceSlug,
                                        LabDtos.RunRequest request, String idempotencyKey) {
        retention.requireConfigured();
        requireIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(request, "request");

        Optional<LabRun> replay = runs.findByBrainIdAndInstanceSlugAndIdempotencyKey(
                brainId, instanceSlug, idempotencyKey);
        if (replay.isPresent()) {
            // The whole point of the key: zero engine reads, zero analyzer executions, zero tokens.
            audit.record(brainId, LabAuditService.RUN_READ, LabAuditEvent.Status.SUCCEEDED,
                    LabAuditEvent.SubjectType.RUN, replay.get().getId(), null,
                    Map.of("replayed", true));
            return runView(brainId, replay.get(), true);
        }

        LabDocumentRegistration registration =
                requireRegistration(brainId, instanceSlug, request.packageId());
        IncomeLabReleaseService.ResolvedRelease release = releases.resolveForRun(brainId);

        DocumentEngineClient.VerifiedEnvelope verified =
                fetchRevision(request.packageId(), request.revision());
        DocumentEngineClient.RevisionDescriptor descriptor =
                requireDescriptor(request.packageId(), verified);

        ReviewedInput reviewed = overlayIfPinned(release.manifest(), verified);

        IncomeEnvelopeCompatibility.Decision decision = COMPATIBILITY.evaluate(
                reviewed.verified().envelope(), policyOf(release.manifest()));
        if (!decision.compatible()) {
            // Before the claim on purpose: an unusable parse leaves no PROCESSING row behind.
            audit.record(brainId, LabAuditService.RUN_START, LabAuditEvent.Status.DENIED,
                    LabAuditEvent.SubjectType.RUN, null,
                    decision.rejection().name(), Map.of("warnings", decision.warnings().size()));
            throw new ParsedIncomeAnalysisService.ParsedAnalysisException(
                    ParsedIncomeAnalysisService.ParsedAnalysisException.Code
                            .PARSED_ENVELOPE_INCOMPATIBLE,
                    decision.rejection().name());
        }

        UUID analysisRunId = UUID.randomUUID();
        String correlationId = LabAuditService.correlationIdOrNew();
        LabRunTransactionService.RunClaim claim = transactions.claimRun(brainId, instanceSlug,
                idempotencyKey, release.releaseId(), registration.getId(), RUN_LEASE,
                factsOf(registration.getId(), reviewed.verified(), reviewed.snapshot()));
        if (!claim.fresh()) {
            // A concurrent caller with the same key won; this one starts nothing.
            return runView(brainId, claim.run(), true);
        }
        LabRun run = claim.run();
        audit.record(brainId, LabAuditService.RUN_START, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.RUN, run.getId(), null,
                Map.of("revision", reviewed.verified().revision(), "warnings",
                        decision.warnings().size(),
                        "machineFields",
                        reviewed.snapshot() == null ? 0 : reviewed.snapshot().machineCount(),
                        "correctedFields",
                        reviewed.snapshot() == null ? 0 : reviewed.snapshot().correctedCount(),
                        "rejectedFields",
                        reviewed.snapshot() == null ? 0 : reviewed.snapshot().rejectedCount()));

        return execute(brainId, run, registration, release, reviewed.verified(), descriptor,
                analysisRunId, correlationId,
                reviewed.snapshot() == null ? null : reviewed.snapshot().sha256());
    }

    /**
     * One bounded analyzer execution for a claimed run, with no database transaction open.
     *
     * <p>The existing Income v2 contract may make at most two provider attempts (one call plus its
     * single corrective retry); that accounting is preserved exactly as the analyzer reports it and
     * is not re-derived here.
     */
    private LabDtos.RunResponse execute(UUID brainId, LabRun run,
                                        LabDocumentRegistration registration,
                                        IncomeLabReleaseService.ResolvedRelease release,
                                        DocumentEngineClient.VerifiedEnvelope verified,
                                        DocumentEngineClient.RevisionDescriptor descriptor,
                                        UUID analysisRunId, String correlationId,
                                        String reviewSnapshotSha256) {
        try {
            ParsedIncomeAnalysisService.ParsedRunOutcome outcome = analysis.analyze(
                    new ParsedAnalysisInput(brainId, analysisRunId, verified.envelope().packageId(),
                            descriptor, verified, correlationId, reviewSnapshotSha256));

            if (!analysisRuns.existsById(analysisRunId)) {
                // A parsed success is not returned until the exact caller-allocated analyzer row
                // exists. Without it the Lab run would claim a manifest that is not there.
                throw new AnalysisRunRecorder.RecorderException(
                        AnalysisRunRecorder.RecorderException.Code.ANALYSIS_RUN_ABSENT_AFTER_WRITE);
            }

            LabDtos.RunAnalysis analysisView = analysisView(outcome);
            transactions.completeRun(run.getId(), brainId, analysisRunId,
                    encode(new StoredAnalysis(analysisView, release.releaseNumber(),
                            release.manifestSha256())));

            audit.record(brainId, LabAuditService.RUN_TERMINAL, LabAuditEvent.Status.SUCCEEDED,
                    LabAuditEvent.SubjectType.RUN, run.getId(), null,
                    Map.of("providerAttempts", outcome.provenance().providerAttempts(),
                            "inputTokens", outcome.result().inputTokens(),
                            "outputTokens", outcome.result().outputTokens()));

            return new LabDtos.RunResponse(run.getId(), run.getInstanceSlug(), "SUCCEEDED", null,
                    release.releaseId(), release.releaseNumber(), release.manifestSha256(),
                    analysisRunId, registration.getId(), sourceOf(verified), analysisView,
                    reviewSnapshots.findByRunId(run.getId())
                            .map(LabDtos.RunReviewSnapshot::from).orElse(null),
                    run.getCreatedAt(), OffsetDateTime.now(), false, LabDtos.Prototype.current());
        } catch (RuntimeException failure) {
            String code = failureCodeOf(failure);
            log.error("Parsed Lab run {} failed ({}) [{}]", run.getId(), code, correlationId);
            transactions.failRun(run.getId(), brainId, code);
            audit.record(brainId, LabAuditService.RUN_TERMINAL, LabAuditEvent.Status.FAILED,
                    LabAuditEvent.SubjectType.RUN, run.getId(), code, Map.of("payloadStored", false));
            throw failure;
        }
    }

    /** History from Lab tables only, newest first. Never decrypts a payload. */
    public LabDtos.RunHistoryResponse history(UUID brainId, String instanceSlug) {
        retention.requireConfigured();
        List<LabDtos.RunSummary> summaries =
                runs.findByBrainIdAndInstanceSlugOrderByCreatedAtDesc(brainId, instanceSlug).stream()
                        .map(this::summaryOf)
                        .toList();
        audit.record(brainId, LabAuditService.RUN_HISTORY_READ, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.RUN, null, null, Map.of("runs", summaries.size()));
        return new LabDtos.RunHistoryResponse(summaries, LabDtos.Prototype.current());
    }

    /** One authorized run's detail. This is the only read that decrypts a stored payload. */
    public LabDtos.RunResponse runDetail(UUID brainId, UUID runId) {
        retention.requireConfigured();
        LabRun run = runs.findByIdAndBrainId(runId, brainId)
                .orElseThrow(() -> new LabRequestException(LabRequestException.Code.RUN_NOT_FOUND));
        auditRequired(brainId, LabAuditService.RUN_READ, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.RUN, runId, null, Map.of("decrypted", true));
        return runView(brainId, run, false);
    }

    /** Authorized idempotent purge. Never deletes the Document Engine package. */
    public LabDtos.PurgeResponse purgeRun(UUID brainId, UUID runId) {
        retention.requireConfigured();
        LabRetentionService.PurgeOutcome outcome = retention.purge(brainId, runId);
        return new LabDtos.PurgeResponse(runId, outcome.deleted(), outcome.messagesDeleted(),
                outcome.exchangesDeleted(), outcome.payloadsDeleted(), outcome.documentsDeleted(),
                outcome.analysisRunDeleted(), true, LabDtos.Prototype.current());
    }

    // ================================================================ discussion

    /** One authorized run's discussion transcript, decrypted in ascending exchange order. */
    public LabDtos.DiscussionResponse discussion(UUID brainId, UUID runId) {
        retention.requireConfigured();
        runs.findByIdAndBrainId(runId, brainId)
                .orElseThrow(() -> new LabRequestException(LabRequestException.Code.RUN_NOT_FOUND));
        List<LabDiscussionExchange> transcript =
                exchanges.findByRunIdOrderBySequenceNumberAsc(runId);
        auditRequired(brainId, LabAuditService.DISCUSSION_READ, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.RUN, runId, null,
                Map.of("exchanges", transcript.size()));
        return new LabDtos.DiscussionResponse(runId, decryptTranscript(brainId, runId, transcript),
                false, null, LabDtos.Prototype.current());
    }

    /**
     * Appends one run-pinned discussion turn.
     *
     * <p>A discussion message is a CHILD inference, not a new analysis: it reuses this run's exact
     * inputs — the same engine revision, freshly fetched and re-verified, the same stored release,
     * and the run's own decrypted terminal output — and appends to the transcript. It never mutates
     * the original analysis. A question that would need different inputs or different behaviour
     * requires a NEW run, which is why nothing here accepts a package, a revision, or a release.
     */
    public LabDtos.DiscussionResponse postMessage(UUID brainId, UUID runId,
                                                  LabDtos.MessageRequest request,
                                                  String idempotencyKey) {
        retention.requireConfigured();
        requireIdempotencyKey(idempotencyKey);
        String question = request == null ? null : request.question();
        if (question == null || question.isBlank()) {
            throw new LabRequestException(LabRequestException.Code.DISCUSSION_QUESTION_REQUIRED);
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new LabRequestException(LabRequestException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                    Map.of("questionChars", question.length(), "maxChars", MAX_QUESTION_CHARS));
        }

        // A fast fail on plain reads. The AUTHORITATIVE versions of these same checks run inside
        // claimExchange, which holds the run's pessimistic lock — a lock cannot be taken here,
        // because holding one across the model call is exactly what this design refuses to do.
        LabRun run = runs.findByIdAndBrainId(runId, brainId)
                .orElseThrow(() -> new LabRequestException(LabRequestException.Code.RUN_NOT_FOUND));
        if (run.getStatus() != LabRun.Status.SUCCEEDED) {
            throw new LabRequestException(LabRequestException.Code.RUN_NOT_SUCCEEDED);
        }

        Optional<LabDiscussionExchange> replay =
                exchanges.findByRunIdAndIdempotencyKey(runId, idempotencyKey);
        if (replay.isPresent()) {
            return replayExchange(brainId, runId, replay.get());
        }

        List<LabDiscussionExchange> transcript =
                exchanges.findByRunIdOrderBySequenceNumberAsc(runId);
        requireNoExchangeInFlight(transcript);
        requireTranscriptWithinBounds(transcript);

        LabRunTransactionService.ExchangeClaim claim =
                transactions.claimExchange(brainId, runId, idempotencyKey, EXCHANGE_LEASE);
        if (!claim.fresh()) {
            return replayExchange(brainId, runId, claim.exchange());
        }
        LabDiscussionExchange exchange = claim.exchange();

        try {
            ComposedContext composed = composeContext(brainId, run, runId);
            List<ChatRequest.Turn> turns = decryptTurns(brainId, runId, transcript);
            ChatResponse answered = chat.answerSanitized(
                    new ChatRequest(question, composed.text(), turns), brainId,
                    LabAuditService.correlationIdOrNew());
            String body = answered.answer() == null ? "" : answered.answer();

            transactions.completeExchange(brainId, runId, exchange.getId(),
                    question.getBytes(StandardCharsets.UTF_8),
                    body.getBytes(StandardCharsets.UTF_8));

            audit.record(brainId, LabAuditService.DISCUSSION_WRITE, LabAuditEvent.Status.SUCCEEDED,
                    LabAuditEvent.SubjectType.EXCHANGE, exchange.getId(), null,
                    Map.of("sequence", exchange.getSequenceNumber(),
                            "priorExchanges", transcript.size()));

            List<LabDtos.DiscussionExchange> view =
                    new ArrayList<>(decryptTranscript(brainId, runId, transcript));
            view.add(new LabDtos.DiscussionExchange(exchange.getId(),
                    exchange.getSequenceNumber(), "SUCCEEDED", null,
                    List.of(new LabDtos.DiscussionMessage("USER", exchange.getUserOrdinal(),
                                    question, exchange.getCreatedAt()),
                            new LabDtos.DiscussionMessage("ASSISTANT",
                                    exchange.getAssistantOrdinal(), body, exchange.getCreatedAt())),
                    LabDiscussionExchange.PROTOTYPE_LIVE_DEPENDENCIES, exchange.getCreatedAt(),
                    OffsetDateTime.now()));
            return new LabDtos.DiscussionResponse(runId, view, false, composed.drift(),
                    LabDtos.Prototype.current());
        } catch (RuntimeException failure) {
            String code = failureCodeOf(failure);
            log.error("Lab discussion exchange {} failed ({})", exchange.getId(), code);
            transactions.failExchange(exchange.getId(), code);
            audit.record(brainId, LabAuditService.DISCUSSION_WRITE, LabAuditEvent.Status.FAILED,
                    LabAuditEvent.SubjectType.EXCHANGE, exchange.getId(), code,
                    Map.of("bodiesStored", false));
            throw failure;
        }
    }

    /** {@code composeContext}'s result: the rendered prompt text plus any reviewer-state drift. */
    private record ComposedContext(String text, LabDtos.ReviewDrift drift) {}

    /**
     * Rebuilds the discussion's context from the run's own pinned identities.
     *
     * <p>Deliberately re-fetched and re-verified rather than cached: the engine result is immutable,
     * so a fresh read of the same revision must produce the same bytes — and if it somehow does
     * not, the adapter's digest verification fails and the exchange stops rather than answering
     * from something that drifted.
     */
    private ComposedContext composeContext(UUID brainId, LabRun run, UUID runId) {
        LabRunDocument pinned = runDocuments.findByRunId(runId).stream().findFirst()
                .orElseThrow(() -> new LabRequestException(
                        LabRequestException.Code.RUN_SOURCE_ABSENT));
        DocumentEngineClient.VerifiedEnvelope verified =
                engine.envelopeRevision(pinned.getEnginePackageId(), pinned.getPackageRevision());
        if (!verified.artifact().sha256().equals(pinned.getEnvelopeSha256())) {
            throw new DocumentEngineFailure(DocumentEngineFailure.Code.ENGINE_DIGEST_MISMATCH);
        }

        // A run predates the read-model release exactly when it has no snapshot row: look that up
        // first, before touching the release manifest or calling the engine's read model at all.
        // An envelope-era run stays envelope-only forever — it never had reviewed values to drift
        // against, so there is nothing to overlay and nothing to compare.
        Optional<LabRunReviewSnapshot> pinnedSnapshot = reviewSnapshots.findByRunId(runId);
        ReviewedInput reviewed;
        LabDtos.ReviewDrift drift = null;
        if (pinnedSnapshot.isEmpty()) {
            reviewed = new ReviewedInput(verified, null);
        } else {
            LabReleaseManifest manifest = releases.resolveForRun(brainId).manifest();
            reviewed = overlayIfPinned(manifest, verified);
            if (reviewed.snapshot() != null) {
                ReviewSnapshot now = reviewed.snapshot();
                boolean changed = !pinnedSnapshot.get().getFieldsSha256().equals(now.sha256());
                drift = new LabDtos.ReviewDrift(changed,
                        pinnedSnapshot.get().getMachineCount(), now.machineCount(),
                        pinnedSnapshot.get().getCorrectedCount(), now.correctedCount(),
                        pinnedSnapshot.get().getRejectedCount(), now.rejectedCount());
            }
        }

        StringBuilder context = new StringBuilder();
        context.append("## Pinned run identity\n")
                .append("runId=").append(runId)
                .append(" releaseId=").append(run.getReleaseId())
                .append(" packageId=").append(pinned.getEnginePackageId())
                .append(" revision=").append(pinned.getPackageRevision())
                .append(" parseGeneration=").append(pinned.getParseGeneration())
                .append(" envelopeSha256=").append(pinned.getEnvelopeSha256())
                .append('\n')
                .append("This discussion is pinned to the run above. Corpus contents, model "
                        + "routing, and tool implementations remain live prototype dependencies "
                        + "(").append(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE)
                .append(").\n\n");

        if (drift != null && drift.changed()) {
            int moved = Math.abs(drift.machineNow() - drift.machineBefore())
                    + Math.abs(drift.correctedNow() - drift.correctedBefore())
                    + Math.abs(drift.rejectedNow() - drift.rejectedBefore());
            context.append("NOTE: reviewer changes were recorded in the engine after this run: ")
                    .append(moved / 2 + moved % 2).append(" fields changed status (machine ")
                    .append(drift.machineBefore()).append("->").append(drift.machineNow())
                    .append(", corrected ").append(drift.correctedBefore()).append("->")
                    .append(drift.correctedNow()).append(", rejected ")
                    .append(drift.rejectedBefore()).append("->").append(drift.rejectedNow())
                    .append("). The stored analysis was computed before those changes. Re-run to"
                            + " analyze the current values.\n\n");
        }

        context.append("## Parsed machine facts (immutable engine revision)\n");
        for (EngineResultEnvelope.LogicalDocument document
                : reviewed.verified().envelope().documents()) {
            context.append("- document ").append(document.ordinal()).append(' ')
                    .append(document.documentTypeCode()).append('\n');
            for (EngineResultEnvelope.FieldOccurrence field : document.fields()) {
                context.append("    ").append(field.name());
                if (field.groupKey() != null) {
                    context.append('[').append(field.groupKey()).append(']');
                }
                context.append(" status=").append(field.status().name())
                        .append(" reviewState=").append(field.reviewState().name());
                if (field.status() == EngineResultEnvelope.FieldStatus.FOUND) {
                    // Same gate as ParsedDocumentPromptRenderer: this context goes to a provider,
                    // and the envelope is unmasked. A sensitive value is named, never quoted.
                    context.append(" value=")
                            .append(field.sensitive() ? SENSITIVE_VALUE_REDACTED
                                    : normalizedTextOf(field));
                }
                context.append('\n');
            }
        }

        LabDtos.RunAnalysis stored = storedAnalysis(brainId, runId).analysis();
        context.append("\n## Prior analysis for this run\n")
                .append(stored.reportMarkdown() == null ? "" : stored.reportMarkdown())
                .append('\n');

        if (context.length() > MAX_CONTEXT_CHARS) {
            // Fail closed rather than truncating: a silently shortened context would answer from
            // an incomplete fact set while still looking like a pinned run's discussion.
            throw new LabRequestException(LabRequestException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                    Map.of("contextChars", context.length(), "maxChars", MAX_CONTEXT_CHARS));
        }
        return new ComposedContext(context.toString(), drift);
    }

    private LabDtos.DiscussionResponse replayExchange(UUID brainId, UUID runId,
                                                      LabDiscussionExchange exchange) {
        if (exchange.getStatus() == LabRun.Status.PROCESSING) {
            if (exchange.getLeaseExpiresAt() != null
                    && exchange.getLeaseExpiresAt().isBefore(OffsetDateTime.now())) {
                transactions.markExchangeInterrupted(exchange.getId(),
                        LabRunRecoveryService.LEASE_EXPIRED);
                throw new LabRequestException(
                        LabRequestException.Code.DISCUSSION_EXCHANGE_INTERRUPTED);
            }
            throw new LabRequestException(
                    LabRequestException.Code.DISCUSSION_EXCHANGE_IN_PROGRESS);
        }
        if (exchange.getStatus() == LabRun.Status.INTERRUPTED) {
            // Never replayed through the model: an explicit new key is required.
            throw new LabRequestException(LabRequestException.Code.DISCUSSION_EXCHANGE_INTERRUPTED);
        }
        List<LabDiscussionExchange> transcript =
                exchanges.findByRunIdOrderBySequenceNumberAsc(runId);
        return new LabDtos.DiscussionResponse(runId, decryptTranscript(brainId, runId, transcript),
                true, null, LabDtos.Prototype.current());
    }

    private void requireNoExchangeInFlight(List<LabDiscussionExchange> transcript) {
        boolean inFlight = transcript.stream()
                .anyMatch(exchange -> exchange.getStatus() == LabRun.Status.PROCESSING
                        && exchange.getLeaseExpiresAt() != null
                        && !exchange.getLeaseExpiresAt().isBefore(OffsetDateTime.now()));
        if (inFlight) {
            // A different key must wait rather than claim the next ordinal out from under the
            // exchange that is still running — that is what would reorder the transcript.
            throw new LabRequestException(LabRequestException.Code.DISCUSSION_EXCHANGE_IN_PROGRESS);
        }
    }

    private void requireTranscriptWithinBounds(List<LabDiscussionExchange> transcript) {
        int turns = (int) transcript.stream()
                .filter(exchange -> exchange.getStatus() == LabRun.Status.SUCCEEDED)
                .count() * 2;
        if (turns > MAX_TRANSCRIPT_TURNS) {
            throw new LabRequestException(LabRequestException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                    Map.of("turns", turns, "maxTurns", MAX_TRANSCRIPT_TURNS));
        }
    }

    private List<ChatRequest.Turn> decryptTurns(UUID brainId, UUID runId,
                                                List<LabDiscussionExchange> transcript) {
        List<ChatRequest.Turn> turns = new ArrayList<>();
        for (LabDtos.DiscussionExchange exchange : decryptTranscript(brainId, runId, transcript)) {
            for (LabDtos.DiscussionMessage message : exchange.messages()) {
                if (message.body() != null && message.body().length() > MAX_TURN_CHARS) {
                    throw new LabRequestException(
                            LabRequestException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                            Map.of("turnChars", message.body().length(),
                                    "maxChars", MAX_TURN_CHARS));
                }
                turns.add(new ChatRequest.Turn(message.role().toLowerCase(java.util.Locale.ROOT),
                        message.body()));
            }
        }
        return turns;
    }

    private List<LabDtos.DiscussionExchange> decryptTranscript(
            UUID brainId, UUID runId, List<LabDiscussionExchange> transcript) {
        if (transcript.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LabDiscussionMessage>> byExchange = new LinkedHashMap<>();
        for (LabDiscussionMessage message : messages.findByExchangeIdInOrderByOrdinalAsc(
                transcript.stream().map(LabDiscussionExchange::getId).toList())) {
            byExchange.computeIfAbsent(message.getExchangeId(), key -> new ArrayList<>())
                    .add(message);
        }
        List<LabDtos.DiscussionExchange> view = new ArrayList<>(transcript.size());
        for (LabDiscussionExchange exchange : transcript) {
            List<LabDtos.DiscussionMessage> bodies =
                    byExchange.getOrDefault(exchange.getId(), List.of()).stream()
                            .map(message -> new LabDtos.DiscussionMessage(
                                    message.getRole().name(), message.getOrdinal(),
                                    open(brainId, runId, message), message.getCreatedAt()))
                            .toList();
            view.add(new LabDtos.DiscussionExchange(exchange.getId(), exchange.getSequenceNumber(),
                    exchange.getStatus().name(), exchange.getFailureCode(), bodies,
                    exchange.getPrototypeLimitations(), exchange.getCreatedAt(),
                    exchange.getTerminalAt()));
        }
        return view;
    }

    private String open(UUID brainId, UUID runId, LabDiscussionMessage message) {
        LabPayloadCipher.RecordType type = message.getRole() == LabDiscussionMessage.Role.USER
                ? LabPayloadCipher.RecordType.DISCUSSION_USER
                : LabPayloadCipher.RecordType.DISCUSSION_ASSISTANT;
        byte[] plaintext = cipher.open(brainId, runId, message.getId(), type,
                new LabPayloadCipher.SealedPayload(message.getNonce(), message.getCiphertext()));
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    // ================================================================ views

    private LabDtos.RunResponse runView(UUID brainId, LabRun run, boolean replayed) {
        LabDtos.RunSource source = runDocuments.findByRunId(run.getId()).stream()
                .findFirst().map(IncomeLabService::sourceOf).orElse(null);
        StoredAnalysis stored = run.getStatus() == LabRun.Status.SUCCEEDED
                ? storedAnalysis(brainId, run.getId())
                : null;
        return new LabDtos.RunResponse(run.getId(), run.getInstanceSlug(), run.getStatus().name(),
                run.getFailureCode(), run.getReleaseId(),
                stored == null ? 0 : stored.releaseNumber(),
                stored == null ? null : stored.releaseManifestSha256(),
                run.getAnalysisRunId(), run.getRegistrationId(), source,
                stored == null ? null : stored.analysis(),
                reviewSnapshots.findByRunId(run.getId())
                        .map(LabDtos.RunReviewSnapshot::from).orElse(null),
                run.getCreatedAt(), run.getTerminalAt(),
                replayed, LabDtos.Prototype.current());
    }

    private LabDtos.RunSummary summaryOf(LabRun run) {
        Optional<LabRunDocument> pinned = runDocuments.findByRunId(run.getId()).stream().findFirst();
        int exchangeCount = exchanges.findFirstByRunIdOrderBySequenceNumberDesc(run.getId())
                .map(LabDiscussionExchange::getSequenceNumber).orElse(0);
        return new LabDtos.RunSummary(run.getId(), run.getInstanceSlug(), run.getStatus().name(),
                run.getFailureCode(), run.getReleaseId(), run.getAnalysisRunId(),
                pinned.map(LabRunDocument::getEnginePackageId).orElse(null),
                pinned.map(LabRunDocument::getPackageRevision).orElse(null),
                run.getCreatedAt(), run.getTerminalAt(), exchangeCount,
                LabDtos.Prototype.current());
    }

    /** The encrypted terminal payload, decrypted. Reached only from an authorized detail read. */
    private StoredAnalysis storedAnalysis(UUID brainId, UUID runId) {
        LabRunPayload payload = payloads
                .findByRunIdAndPayloadType(runId, LabRunPayload.PayloadType.ANALYSIS_OUTPUT)
                .orElseThrow(() -> new LabRequestException(
                        LabRequestException.Code.RUN_PAYLOAD_ABSENT));
        byte[] plaintext = cipher.open(brainId, runId, payload.getId(),
                LabPayloadCipher.RecordType.ANALYSIS_OUTPUT,
                new LabPayloadCipher.SealedPayload(payload.getNonce(), payload.getCiphertext()));
        return decode(plaintext);
    }

    /** What one run's encrypted payload holds: its terminal analysis and the release it ran. */
    record StoredAnalysis(LabDtos.RunAnalysis analysis, int releaseNumber,
                          String releaseManifestSha256) {}

    private static LabDtos.RunAnalysis analysisView(
            ParsedIncomeAnalysisService.ParsedRunOutcome outcome) {
        AnalysisResult result = outcome.result();
        List<LabDtos.Citation> citations = result.citations() == null
                ? List.of()
                : result.citations().stream().map(LabDtos.Citation::from).toList();
        return new LabDtos.RunAnalysis(result.status().name(), result.reportMarkdown(),
                result.findingsJson(), citations,
                result.provider(), result.model(), result.inputTokens(), result.outputTokens(),
                result.costUsd(), outcome.provenance().providerAttempts(), result.reason());
    }

    private byte[] encode(StoredAnalysis stored) {
        try {
            return mapper.writeValueAsBytes(stored);
        } catch (JsonProcessingException unserializable) {
            // Class name only; the value being serialized is the analysis itself.
            log.error("Lab payload could not be serialized ({})",
                    unserializable.getClass().getSimpleName());
            throw new LabRequestException(LabRequestException.Code.RUN_PAYLOAD_UNREADABLE);
        }
    }

    private StoredAnalysis decode(byte[] plaintext) {
        try {
            return mapper.readValue(plaintext, StoredAnalysis.class);
        } catch (java.io.IOException unreadable) {
            log.error("Lab payload could not be deserialized ({})",
                    unreadable.getClass().getSimpleName());
            throw new LabRequestException(LabRequestException.Code.RUN_PAYLOAD_UNREADABLE);
        }
    }

    // ================================================================ guards and helpers

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || !IDEMPOTENCY_KEY.matcher(key.trim()).matches()) {
            throw new LabRequestException(LabRequestException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    /**
     * Brain- and instance-scoped registration lookup. A package registered to another brain or
     * another instance resolves to nothing here, so cross-scope substitution fails before any
     * engine read rather than after one.
     */
    private LabDocumentRegistration requireRegistration(UUID brainId, String instanceSlug,
                                                        UUID packageId) {
        if (packageId == null) {
            throw new LabRequestException(LabRequestException.Code.REGISTRATION_NOT_FOUND);
        }
        return registrations
                .findByEnginePackageIdAndBrainIdAndInstanceSlug(packageId, brainId, instanceSlug)
                .orElseThrow(() -> new LabRequestException(
                        LabRequestException.Code.REGISTRATION_NOT_FOUND));
    }

    private DocumentEngineClient.VerifiedEnvelope fetchRevision(UUID packageId, Integer revision) {
        return revision == null
                ? engine.currentEnvelope(packageId)
                : engine.envelopeRevision(packageId, revision);
    }

    /** What a run under this release analyzes: the pinned envelope, overlaid if the release reads reviewed values. */
    record ReviewedInput(DocumentEngineClient.VerifiedEnvelope verified, ReviewSnapshot snapshot) {}

    private ReviewedInput overlayIfPinned(LabReleaseManifest manifest,
                                          DocumentEngineClient.VerifiedEnvelope verified) {
        if (!manifest.pinned().engineContract().readsReviewedValues()) {
            return new ReviewedInput(verified, null);
        }
        Map<UUID, ReviewedFields> byDocument = new LinkedHashMap<>();
        for (EngineResultEnvelope.LogicalDocument document : verified.envelope().documents()) {
            byDocument.put(document.id(), engine.reviewedFields(document.id()));
        }
        ReviewedValueOverlay.Overlaid overlaid = OVERLAY.apply(verified.envelope(), byDocument);
        return new ReviewedInput(
                new DocumentEngineClient.VerifiedEnvelope(verified.artifact(), overlaid.envelope(),
                        verified.revision()),
                overlaid.snapshot());
    }

    private DocumentEngineClient.RevisionDescriptor requireDescriptor(
            UUID packageId, DocumentEngineClient.VerifiedEnvelope verified) {
        return engine.revisionHistory(packageId).stream()
                .filter(candidate -> candidate.revision() == verified.revision())
                .filter(candidate -> candidate.describes(verified))
                .findFirst()
                .orElseThrow(() -> new LabRequestException(
                        LabRequestException.Code.REVISION_NOT_FOUND));
    }

    private static IncomeEnvelopeCompatibility.Policy policyOf(LabReleaseManifest manifest) {
        LabReleaseManifest.EngineContract contract = manifest.pinned().engineContract();
        return IncomeEnvelopeCompatibility.Policy.of(contract.supportedEnvelopeVersions(),
                contract.supportedCanonicalizationVersions(), contract.supportedDocumentTypes(),
                contract.reviewWarningValidationStatuses());
    }

    private static LabRunTransactionService.RunDocumentFacts factsOf(
            UUID registrationId, DocumentEngineClient.VerifiedEnvelope verified,
            ReviewSnapshot snapshot) {
        EngineResultEnvelope envelope = verified.envelope();
        return new LabRunTransactionService.RunDocumentFacts(registrationId, envelope.packageId(),
                verified.revision(), envelope.generation().processingJobId(),
                envelope.generation().parseGeneration(), envelope.envelopeVersion(),
                envelope.canonicalizationVersion(), verified.artifact().sha256(),
                verified.artifact().byteCount(), envelope.generation().sourceSetSha256(),
                envelope.generation().reuseEligibility(), envelope.documents().size(),
                envelope.pages().size(),
                snapshot == null ? null : LabRunTransactionService.ReviewSnapshotFacts.of(snapshot));
    }

    private static LabDtos.RunSource sourceOf(DocumentEngineClient.VerifiedEnvelope verified) {
        EngineResultEnvelope envelope = verified.envelope();
        return new LabDtos.RunSource(envelope.packageId(), verified.revision(),
                envelope.generation().parseGeneration(), envelope.generation().processingJobId(),
                envelope.envelopeVersion(), envelope.canonicalizationVersion(),
                verified.artifact().sha256(), verified.artifact().byteCount(),
                envelope.generation().sourceSetSha256(), envelope.generation().reuseEligibility(),
                envelope.documents().size(), envelope.pages().size());
    }

    private static LabDtos.RunSource sourceOf(LabRunDocument document) {
        return new LabDtos.RunSource(document.getEnginePackageId(), document.getPackageRevision(),
                document.getParseGeneration(), document.getProcessingJobId(),
                document.getEnvelopeVersion(), document.getCanonicalizationVersion(),
                document.getEnvelopeSha256(), document.getEnvelopeSizeBytes(),
                document.getSourceSetSha256(), document.getReuseEligibility(),
                document.getDocumentCount(), document.getPageCount());
    }

    private static LabDtos.Revision revisionOf(DocumentEngineClient.RevisionDescriptor descriptor) {
        return new LabDtos.Revision(descriptor.revision(), descriptor.processingJobId(),
                descriptor.parseGeneration(), descriptor.envelopeSchemaVersion(),
                descriptor.envelopeSha256(), descriptor.envelopeSizeBytes(),
                descriptor.sourceSetSha256(), descriptor.reuseEligibility(),
                descriptor.createdAt());
    }

    private LabDtos.RegistrationResponse registrationResponse(
            LabDocumentRegistration stored, DocumentEngineClient.UploadRegistration accepted,
            boolean created) {
        return new LabDtos.RegistrationResponse(stored.getId(), stored.getEnginePackageId(),
                stored.getEngineJobId(), stored.getEngineSourceId(), accepted.sources().size(),
                accepted.duplicateShaPrefixes(), created, LabDtos.Prototype.current());
    }

    private static UUID firstSourceId(DocumentEngineClient.UploadRegistration accepted) {
        return accepted.sources().stream()
                .map(DocumentEngineClient.RegisteredSource::id)
                .findFirst()
                .orElseThrow(() -> new DocumentEngineFailure(
                        DocumentEngineFailure.Code.ENGINE_RESPONSE_MALFORMED));
    }

    /** Mirrors {@code ParsedDocumentPromptRenderer}'s marker so both prompts read the same. */
    private static final String SENSITIVE_VALUE_REDACTED = "<redacted:sensitive>";

    private static String normalizedTextOf(EngineResultEnvelope.FieldOccurrence field) {
        EngineResultEnvelope.NormalizedValue normalized = field.normalized();
        if (normalized == null) {
            return field.displayedText() == null ? "" : field.displayedText();
        }
        if (normalized.number() != null) {
            return normalized.number().toPlainString();
        }
        if (normalized.date() != null) {
            return normalized.date().toString();
        }
        if (normalized.text() != null) {
            return normalized.text();
        }
        return field.displayedText() == null ? "" : field.displayedText();
    }

    /**
     * A fail-closed audit write whose failure is normalized here as well as in
     * {@link LabAuditService}. Defense in depth: whatever the audit collaborator raises, what
     * leaves this class is a payload-free code.
     */
    private void auditRequired(UUID brainId, String action, LabAuditEvent.Status status,
                               LabAuditEvent.SubjectType subjectType, UUID subjectId,
                               String failureCode, Map<String, Object> counts) {
        try {
            audit.recordRequired(brainId, action, status, subjectType, subjectId, failureCode,
                    counts);
        } catch (LabRequestException alreadySafe) {
            throw alreadySafe;
        } catch (RuntimeException unwritable) {
            log.error("Lab sensitive read refused: audit could not be committed for {} ({})",
                    action, unwritable.getClass().getSimpleName());
            throw new LabRequestException(LabRequestException.Code.AUDIT_WRITE_FAILED);
        }
    }

    /**
     * The stable terminal code a failure is recorded under. Every branch reads a taxonomy's own
     * enum name; the fallback is a constant. No branch reads a message.
     */
    private static String failureCodeOf(RuntimeException failure) {
        String code = switch (failure) {
            case LabRequestException request -> request.code().name();
            case DocumentEngineFailure engine -> engine.code().name();
            case LabContractException contract -> contract.code().name();
            case ParsedIncomeAnalysisService.ParsedAnalysisException parsed -> parsed.code().name();
            case IncomeLabReleaseService.ReleaseException release -> release.code().name();
            case LabManifestWriter.ManifestException manifest -> manifest.code().name();
            case AnalysisRunRecorder.RecorderException recorder -> recorder.code().name();
            case LabCryptoException crypto -> crypto.code().name();
            case ModelRouterService.SanitizedProviderException provider -> provider.code().name();
            default -> LAB_RUN_FAILED;
        };
        return code.length() <= FAILURE_CODE_MAX ? code : code.substring(0, FAILURE_CODE_MAX);
    }

    // ================================================================ taxonomy

    /**
     * The orchestration layer's own payload-free failure taxonomy — the eighth and last one the Lab
     * exception handler maps.
     *
     * <p>Nested here for the same reason {@code ReleaseException} is nested in
     * {@link IncomeLabReleaseService} and {@code ParsedAnalysisException} in
     * {@link ParsedIncomeAnalysisService}: the taxonomy belongs to the collaborator that raises it,
     * and none of these codes means anything outside the Lab.
     *
     * <p>{@link #counts()} may carry numbers and booleans, because a client being told its context
     * is too large deserves to know by how much. It may never carry a string.
     */
    public static final class LabRequestException extends RuntimeException {

        /** Stable, value-free orchestration failure taxonomy. */
        public enum Code {
            /** No usable {@code Idempotency-Key} accompanied a mutating request. */
            IDEMPOTENCY_KEY_REQUIRED,
            /** The multipart request did not carry exactly one file. */
            UPLOAD_FILE_COUNT_INVALID,
            /** No registration binds this package to this brain and instance. */
            REGISTRATION_NOT_FOUND,
            /** The package is already registered to a different brain or instance. */
            REGISTRATION_CONFLICT,
            /** The named job is not this package's job. */
            JOB_IDENTITY_MISMATCH,
            /** No immutable revision descriptor matches the bytes that were fetched. */
            REVISION_NOT_FOUND,
            /** No such run for this brain. */
            RUN_NOT_FOUND,
            /** The run has no pinned engine-result identity, so its context cannot be rebuilt. */
            RUN_SOURCE_ABSENT,
            /** A succeeded run's encrypted payload is missing. */
            RUN_PAYLOAD_ABSENT,
            /** A decrypted payload did not deserialize into the stored analysis shape. */
            RUN_PAYLOAD_UNREADABLE,
            /** The run is still processing, so it cannot be discussed or purged. */
            RUN_IN_PROGRESS,
            /** The run did not succeed, so there is no analysis to discuss. */
            RUN_NOT_SUCCEEDED,
            /** A second terminal transition was attempted on an already-terminal run. */
            RUN_ALREADY_TERMINAL,
            /** A discussion request carried no question. */
            DISCUSSION_QUESTION_REQUIRED,
            /** Question, composed context, or transcript exceeded the direct-call bounds. */
            DISCUSSION_CONTEXT_TOO_LARGE,
            /** Another exchange for this run holds a live lease. */
            DISCUSSION_EXCHANGE_IN_PROGRESS,
            /** The exchange was abandoned; an explicit new key is required to ask again. */
            DISCUSSION_EXCHANGE_INTERRUPTED,
            /** The exchange being completed no longer exists for this run. */
            DISCUSSION_EXCHANGE_ABSENT,
            /** A sensitive read could not be audited, so it did not happen. */
            AUDIT_WRITE_FAILED,
            /** The Lab is enabled without a positive retention window. */
            RETENTION_NOT_CONFIGURED,
            /**
             * The run belongs to a run group with other members. Purging one member out of a
             * comparison or batch would mutilate the group's record; the group purge
             * ({@code InstanceRetentionService}) is the honest path.
             */
            RUN_IN_GROUP
        }

        private final Code code;
        private final Map<String, Object> counts;

        public LabRequestException(Code code) {
            this(code, Map.of());
        }

        public LabRequestException(Code code, Map<String, Object> counts) {
            // No cause and no writable message: a wrapped persistence or provider failure is
            // exactly the thing that would carry a row, a URI, or a body outward.
            super(Objects.requireNonNull(code, "code").name(), null, false, true);
            this.code = code;
            this.counts = counts == null ? Map.of() : Map.copyOf(counts);
        }

        public Code code() {
            return code;
        }

        /** Numbers and booleans only. */
        public Map<String, Object> counts() {
            return counts;
        }

        @Override
        public String toString() {
            return "LabRequestException[code=" + code + ", counts=" + counts.size() + "]";
        }
    }
}
