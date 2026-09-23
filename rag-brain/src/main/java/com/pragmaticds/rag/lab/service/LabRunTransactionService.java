package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabDiscussionMessage;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunDocument;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.domain.LabRunReviewSnapshot;
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabRunDocumentRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.repository.LabRunReviewSnapshotRepository;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Every database transaction a Lab run or discussion exchange needs, and nothing else.
 *
 * <p><b>Why these are separated from the orchestrator at all.</b> A Lab run spans a model call that
 * can take tens of seconds. Holding a database transaction across it would pin a connection and a
 * row lock for the duration, so the transaction boundaries are deliberately short and explicit:
 * <em>claim</em>, then release; run the analyzer with no transaction open; then <em>terminal
 * write</em>. Each of those is one method here, each {@code REQUIRES_NEW}, so an orchestrator that
 * happens to be inside some other transaction cannot accidentally widen them.
 *
 * <p><b>Exactly-once terminal transition is enforced twice, on purpose.</b> Java checks the status
 * under a pessimistic row lock, and V34's {@code lab_run_guard()} trigger refuses any update to a
 * row that is no longer {@code PROCESSING}. The Java check gives a clean code; the trigger is what
 * makes the claim true even for a caller that never came through here.
 *
 * <p><b>Ciphertext is produced here and only here.</b> Callers hand this service plaintext bytes
 * and never see a {@link LabPayloadCipher.SealedPayload}; the associated data binds each record to
 * its own brain/run/record identity, so ciphertext copied into another row cannot be opened.
 */
/**
 * Shared by the Lab prototype and the instance control plane, so it exists when EITHER is on.
 *
 * <p>It was gated on the Lab alone when the Lab was its only caller. Instance runs now seal and
 * open the same payloads, and a deployment running instances with the prototype off would have
 * failed to start rather than merely lacking a feature — which is what
 * {@code InstanceAdminControllerMvcIT} and {@code LabIdempotencyServiceTxIT} pin by booting in
 * exactly that configuration.
 */
@Service
@ConditionalOnExpression(
        "${ragbrain.lab.enabled:false} or ${ragbrain.instances.enabled:false}")
public class LabRunTransactionService {

    private static final Logger log = LoggerFactory.getLogger(LabRunTransactionService.class);

    /**
     * The empty price list historical and prototype runs point at. A run that was never costed
     * against a versioned catalog has no price, and saying so beats inventing one.
     */
    public static final UUID LEGACY_PRICING_VERSION =
            UUID.fromString("00000000-0000-4000-8000-00000000f001");

    private final LabRunRepository runs;
    private final LabRunGroupRepository groups;
    private final LabRunDocumentRepository runDocuments;
    private final LabRunReviewSnapshotRepository reviewSnapshots;
    private final LabRunPayloadRepository payloads;
    private final LabDiscussionExchangeRepository exchanges;
    private final LabDiscussionMessageRepository messages;
    private final LabPayloadCipher cipher;
    private final InstanceControlMetrics metrics;
    private final TransactionTemplate isolated;

    public LabRunTransactionService(LabRunRepository runs,
                                    LabRunGroupRepository groups,
                                    LabRunDocumentRepository runDocuments,
                                    LabRunReviewSnapshotRepository reviewSnapshots,
                                    LabRunPayloadRepository payloads,
                                    LabDiscussionExchangeRepository exchanges,
                                    LabDiscussionMessageRepository messages,
                                    LabPayloadCipher cipher,
                                    InstanceControlMetrics metrics,
                                    PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.groups = groups;
        this.runDocuments = runDocuments;
        this.reviewSnapshots = reviewSnapshots;
        this.payloads = payloads;
        this.exchanges = exchanges;
        this.messages = messages;
        this.cipher = cipher;
        this.metrics = metrics;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ================================================================ run lifecycle

    /**
     * The immutable engine-result identity a run is pinned to, all of it copied from the verified
     * envelope the adapter already returned. Nothing here is derived, guessed, or hashed again.
     */
    public record RunDocumentFacts(
            UUID registrationId,
            UUID enginePackageId,
            int packageRevision,
            UUID processingJobId,
            int parseGeneration,
            String envelopeVersion,
            String canonicalizationVersion,
            String envelopeSha256,
            long envelopeSizeBytes,
            String sourceSetSha256,
            String reuseEligibility,
            int documentCount,
            int pageCount,
            ReviewSnapshotFacts reviewSnapshot) {

        /** Preserved for callers that pin no review snapshot; leaves it null. */
        public RunDocumentFacts(UUID registrationId, UUID enginePackageId, int packageRevision,
                UUID processingJobId, int parseGeneration, String envelopeVersion,
                String canonicalizationVersion, String envelopeSha256, long envelopeSizeBytes,
                String sourceSetSha256, String reuseEligibility, int documentCount,
                int pageCount) {
            this(registrationId, enginePackageId, packageRevision, processingJobId,
                    parseGeneration, envelopeVersion, canonicalizationVersion, envelopeSha256,
                    envelopeSizeBytes, sourceSetSha256, reuseEligibility, documentCount,
                    pageCount, null);
        }
    }

    /** Counts and a digest of the read-model snapshot a run consumed; never values. */
    public record ReviewSnapshotFacts(
            String fieldsSha256,
            int documentCount,
            int machineCount,
            int correctedCount,
            int rejectedCount,
            Map<UUID, String> schemaVersions) {

        public static ReviewSnapshotFacts of(com.pragmaticds.rag.lab.engine.ReviewSnapshot snapshot) {
            return new ReviewSnapshotFacts(snapshot.sha256(), snapshot.documentCount(),
                    snapshot.machineCount(), snapshot.correctedCount(), snapshot.rejectedCount(),
                    snapshot.schemaVersions());
        }
    }

    /**
     * A claimed run.
     *
     * @param fresh true when THIS caller inserted the row and therefore owns the analyzer
     *     execution; false when the row already existed, in which case the caller must return it
     *     and start nothing
     */
    public record RunClaim(LabRun run, boolean fresh) {}

    /**
     * Inserts a {@code PROCESSING} run and its pinned document identity, or adopts the row an
     * identical key already created.
     *
     * <p>The race is settled by {@code uq_lab_run_idempotency} rather than by a read-then-write:
     * two concurrent callers with one key both attempt the insert, exactly one wins, and the loser
     * re-reads the winner's row. {@code REQUIRES_NEW} is what makes that re-read possible — a
     * constraint violation inside the caller's own transaction would have marked it rollback-only.
     */
    public RunClaim claimRun(UUID brainId, String instanceSlug, String idempotencyKey,
                             UUID releaseId, UUID registrationId, Duration lease,
                             RunDocumentFacts facts) {
        Objects.requireNonNull(facts, "facts");
        try {
            return isolated.execute(status -> {
                // Every run is a member of exactly one group, including this one. V39 backfilled
                // history the same way, so there is one shape to reason about rather than
                // "grouped runs and also these other ones".
                LabRunGroup group = new LabRunGroup();
                group.setBrainId(brainId);
                group.setMode(LabRunGroup.Mode.INDEPENDENT);
                // Scoped by instance, matching uq_lab_run_idempotency. The group's own key is
                // unique per brain alone, so two instances sharing a caller key must not collide
                // here when their runs deliberately do not.
                group.setIdempotencyKey("prototype:" + instanceSlug + ":" + idempotencyKey);
                group.setRequestSha256(sha256Hex(brainId + "\u0000" + instanceSlug
                        + "\u0000" + idempotencyKey));
                group.setStatus(LabRunGroup.Status.PROCESSING);
                LabRunGroup savedGroup = groups.saveAndFlush(group);

                LabRun run = new LabRun();
                run.setBrainId(brainId);
                run.setInstanceSlug(instanceSlug);
                run.setIdempotencyKey(idempotencyKey);
                run.setReleaseId(releaseId);
                run.setRegistrationId(registrationId);
                run.setStatus(LabRun.Status.PROCESSING);
                run.setAttempt(1);
                run.setLeaseExpiresAt(OffsetDateTime.now().plus(lease));
                run.setRunGroupId(savedGroup.getId());
                run.setMemberIndex(0);
                // The prototype does not price its runs. Pointing at the empty legacy version
                // records that honestly; a fabricated price would make every cost report wrong
                // in a way nobody could see.
                run.setPricingVersionId(LEGACY_PRICING_VERSION);
                LabRun saved = runs.saveAndFlush(run);
                runDocuments.saveAndFlush(documentFor(saved.getId(), facts));
                if (facts.reviewSnapshot() != null) {
                    reviewSnapshots.saveAndFlush(snapshotFor(saved.getId(), facts.reviewSnapshot()));
                }
                return new RunClaim(saved, true);
            });
        } catch (DataIntegrityViolationException keyAlreadyClaimed) {
            LabRun existing = runs
                    .findByBrainIdAndInstanceSlugAndIdempotencyKey(brainId, instanceSlug,
                            idempotencyKey)
                    .orElseThrow(() -> keyAlreadyClaimed);
            log.info("Lab run key was claimed concurrently; adopting run {}", existing.getId());
            return new RunClaim(existing, false);
        }
    }

    /**
     * Moves a {@code PROCESSING} run to {@code SUCCEEDED}, pinning the analyzer row it shares an
     * identity with and sealing its terminal output — in ONE transaction, so a run can never be
     * successful without both.
     */
    public void completeRun(UUID runId, UUID brainId, UUID analysisRunId, byte[] plaintext) {
        completeRun(runId, brainId, analysisRunId, plaintext, null);
    }

    /**
     * The generalized form, which additionally seals the run's pinned execution provenance.
     *
     * <p>Both payloads are written in the same transaction as the status change for the same
     * reason the output already was: a run that recorded an answer without recording what produced
     * it would be unauditable, and one that recorded provenance for an answer it never stored would
     * be worse. Either both exist and the run is SUCCEEDED, or neither does and it is not.
     *
     * @param provenance canonical provenance bytes, or null for a run that records none
     */
    public void completeRun(UUID runId, UUID brainId, UUID analysisRunId, byte[] plaintext,
                            byte[] provenance) {
        Objects.requireNonNull(analysisRunId, "analysisRunId");
        Objects.requireNonNull(plaintext, "plaintext");
        isolated.executeWithoutResult(status -> {
            LabRun run = lockProcessing(runId, brainId);

            seal(runId, brainId, LabRunPayload.PayloadType.ANALYSIS_OUTPUT,
                    LabPayloadCipher.RecordType.ANALYSIS_OUTPUT, plaintext);
            if (provenance != null) {
                seal(runId, brainId, LabRunPayload.PayloadType.RUN_PROVENANCE,
                        LabPayloadCipher.RecordType.RUN_PROVENANCE, provenance);
            }

            run.setStatus(LabRun.Status.SUCCEEDED);
            run.setAnalysisRunId(analysisRunId);
            run.setFailureCode(null);
            run.setLeaseExpiresAt(null);
            run.setTerminalAt(OffsetDateTime.now());
            runs.saveAndFlush(run);
            rollUpSingleMemberGroup(run, LabRunGroup.Status.SUCCEEDED);
            metrics.runTerminal(run, modeOf(run));
        });
    }

    /**
     * Seals one payload for a run. The associated data binds it to this brain, run, record id, and
     * record type, so ciphertext moved to another row — or relabelled as another type — cannot be
     * opened.
     */
    private void seal(UUID runId, UUID brainId, LabRunPayload.PayloadType payloadType,
                      LabPayloadCipher.RecordType recordType, byte[] plaintext) {
        LabRunPayload payload = new LabRunPayload();
        payload.setId(UUID.randomUUID());
        payload.setRunId(runId);
        payload.setPayloadType(payloadType);
        LabPayloadCipher.SealedPayload sealed = cipher.seal(brainId, runId, payload.getId(),
                recordType, plaintext);
        payload.setCipherAlgorithm(sealed.algorithm());
        payload.setNonce(sealed.nonce());
        payload.setCiphertext(sealed.ciphertext());
        payloads.saveAndFlush(payload);
    }

    /** Moves a {@code PROCESSING} run to {@code FAILED} with a safe code and no payload at all. */
    public void failRun(UUID runId, UUID brainId, String failureCode) {
        isolated.executeWithoutResult(status -> {
            LabRun run = lockProcessing(runId, brainId);
            run.setStatus(LabRun.Status.FAILED);
            run.setFailureCode(failureCode);
            run.setLeaseExpiresAt(null);
            run.setTerminalAt(OffsetDateTime.now());
            runs.saveAndFlush(run);
            rollUpSingleMemberGroup(run, LabRunGroup.Status.FAILED);
            metrics.runTerminal(run, modeOf(run));
        });
    }

    /**
     * Marks an abandoned run {@code INTERRUPTED}. Called only by recovery, and deliberately does
     * not resume anything: the honest statement about a crashed run is that nobody knows whether
     * its provider attempt happened, so it is recorded as interrupted and left alone.
     */
    public boolean markRunInterrupted(UUID runId, String failureCode) {
        return Boolean.TRUE.equals(isolated.execute(status -> {
            Optional<LabRun> found = runs.findById(runId);
            if (found.isEmpty() || found.get().getStatus() != LabRun.Status.PROCESSING) {
                return false;
            }
            LabRun run = found.get();
            run.setStatus(LabRun.Status.INTERRUPTED);
            run.setFailureCode(failureCode);
            run.setLeaseExpiresAt(null);
            run.setTerminalAt(OffsetDateTime.now());
            runs.saveAndFlush(run);
            rollUpSingleMemberGroup(run, LabRunGroup.Status.FAILED);
            metrics.runTerminal(run, modeOf(run));
            return true;
        }));
    }

    // ================================================================ discussion lifecycle

    /** A claimed exchange; {@code fresh} has the same meaning it has for {@link RunClaim}. */
    public record ExchangeClaim(LabDiscussionExchange exchange, boolean fresh) {}

    /**
     * Claims the next exchange slot for a run, under the run's pessimistic lock.
     *
     * <p><b>The lock is taken HERE, not by the caller.</b> {@code SELECT … FOR UPDATE} requires an
     * active transaction, and a lock acquired in a transaction that immediately commits protects
     * nothing. So the whole decision — is the run still succeeded, has this key already claimed a
     * slot, is another exchange in flight, what is the next sequence number, insert — happens
     * inside one short transaction that holds the run row. The caller's earlier reads are a fast
     * fail; THIS is the authoritative one.
     *
     * <p>The two message ordinals are then arithmetic — {@code 2n-1} and {@code 2n} — so there is
     * no separate counter that could drift from the transcript's order, and
     * {@code uq_lab_exchange_sequence} is the backstop if two claims ever did overlap.
     */
    public ExchangeClaim claimExchange(UUID brainId, UUID runId, String idempotencyKey,
                                       Duration lease) {
        try {
            return isolated.execute(status -> {
                LabRun run = runs.lockByIdAndBrainId(runId, brainId)
                        .orElseThrow(() -> new IncomeLabService.LabRequestException(
                                IncomeLabService.LabRequestException.Code.RUN_NOT_FOUND));
                if (run.getStatus() != LabRun.Status.SUCCEEDED) {
                    throw new IncomeLabService.LabRequestException(
                            IncomeLabService.LabRequestException.Code.RUN_NOT_SUCCEEDED);
                }
                Optional<LabDiscussionExchange> claimed =
                        exchanges.findByRunIdAndIdempotencyKey(runId, idempotencyKey);
                if (claimed.isPresent()) {
                    return new ExchangeClaim(claimed.get(), false);
                }
                boolean inFlight = exchanges.findByRunIdOrderBySequenceNumberAsc(runId).stream()
                        .anyMatch(candidate -> candidate.getStatus() == LabRun.Status.PROCESSING
                                && candidate.getLeaseExpiresAt() != null
                                && !candidate.getLeaseExpiresAt().isBefore(OffsetDateTime.now()));
                if (inFlight) {
                    // A different key waits rather than taking the next ordinal out from under an
                    // exchange that is still running — that is what would reorder the transcript.
                    throw new IncomeLabService.LabRequestException(IncomeLabService
                            .LabRequestException.Code.DISCUSSION_EXCHANGE_IN_PROGRESS);
                }

                int next = exchanges.findFirstByRunIdOrderBySequenceNumberDesc(runId)
                        .map(LabDiscussionExchange::getSequenceNumber)
                        .orElse(0) + 1;
                LabDiscussionExchange exchange = new LabDiscussionExchange();
                exchange.setRunId(runId);
                exchange.setIdempotencyKey(idempotencyKey);
                exchange.setSequenceNumber(next);
                exchange.setUserOrdinal(2 * next - 1);
                exchange.setAssistantOrdinal(2 * next);
                exchange.setStatus(LabRun.Status.PROCESSING);
                exchange.setLeaseExpiresAt(OffsetDateTime.now().plus(lease));
                return new ExchangeClaim(exchanges.saveAndFlush(exchange), true);
            });
        } catch (DataIntegrityViolationException alreadyClaimed) {
            LabDiscussionExchange existing = exchanges
                    .findByRunIdAndIdempotencyKey(runId, idempotencyKey)
                    .orElseThrow(() -> alreadyClaimed);
            return new ExchangeClaim(existing, false);
        }
    }

    /** Seals both message bodies and marks the exchange {@code SUCCEEDED} in one transaction. */
    public void completeExchange(UUID brainId, UUID runId, UUID exchangeId, byte[] userBody,
                                 byte[] assistantBody) {
        isolated.executeWithoutResult(status -> {
            LabDiscussionExchange exchange = exchanges.findById(exchangeId)
                    .filter(candidate -> candidate.getRunId().equals(runId))
                    .orElseThrow(() -> new IncomeLabService.LabRequestException(
                            IncomeLabService.LabRequestException.Code.DISCUSSION_EXCHANGE_ABSENT));

            messages.saveAndFlush(sealMessage(brainId, runId, exchangeId,
                    LabDiscussionMessage.Role.USER, exchange.getUserOrdinal(),
                    LabPayloadCipher.RecordType.DISCUSSION_USER, userBody));
            messages.saveAndFlush(sealMessage(brainId, runId, exchangeId,
                    LabDiscussionMessage.Role.ASSISTANT, exchange.getAssistantOrdinal(),
                    LabPayloadCipher.RecordType.DISCUSSION_ASSISTANT, assistantBody));

            exchange.setStatus(LabRun.Status.SUCCEEDED);
            exchange.setFailureCode(null);
            exchange.setLeaseExpiresAt(null);
            exchange.setTerminalAt(OffsetDateTime.now());
            exchanges.saveAndFlush(exchange);
        });
    }

    /** Marks an exchange {@code FAILED} with a safe code, storing neither body. */
    public void failExchange(UUID exchangeId, String failureCode) {
        isolated.executeWithoutResult(status -> exchanges.findById(exchangeId)
                .filter(candidate -> candidate.getStatus() == LabRun.Status.PROCESSING)
                .ifPresent(exchange -> {
                    exchange.setStatus(LabRun.Status.FAILED);
                    exchange.setFailureCode(failureCode);
                    exchange.setLeaseExpiresAt(null);
                    exchange.setTerminalAt(OffsetDateTime.now());
                    exchanges.saveAndFlush(exchange);
                }));
    }

    /** Marks an abandoned exchange {@code INTERRUPTED} without replaying the model. */
    public boolean markExchangeInterrupted(UUID exchangeId, String failureCode) {
        return Boolean.TRUE.equals(isolated.execute(status -> {
            Optional<LabDiscussionExchange> found = exchanges.findById(exchangeId);
            if (found.isEmpty() || found.get().getStatus() != LabRun.Status.PROCESSING) {
                return false;
            }
            LabDiscussionExchange exchange = found.get();
            exchange.setStatus(LabRun.Status.INTERRUPTED);
            exchange.setFailureCode(failureCode);
            exchange.setLeaseExpiresAt(null);
            exchange.setTerminalAt(OffsetDateTime.now());
            exchanges.saveAndFlush(exchange);
            return true;
        }));
    }

    // ================================================================ helpers

    private LabRun lockProcessing(UUID runId, UUID brainId) {
        LabRun run = runs.lockByIdAndBrainId(runId, brainId)
                .orElseThrow(() -> new IncomeLabService.LabRequestException(
                        IncomeLabService.LabRequestException.Code.RUN_NOT_FOUND));
        if (run.getStatus() != LabRun.Status.PROCESSING) {
            // The trigger would refuse this too; the Java check is what turns it into a code.
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.RUN_ALREADY_TERMINAL);
        }
        return run;
    }

    private LabDiscussionMessage sealMessage(UUID brainId, UUID runId, UUID exchangeId,
                                             LabDiscussionMessage.Role role, int ordinal,
                                             LabPayloadCipher.RecordType type, byte[] body) {
        LabDiscussionMessage message = new LabDiscussionMessage();
        message.setId(UUID.randomUUID());
        message.setExchangeId(exchangeId);
        message.setRole(role);
        message.setOrdinal(ordinal);
        LabPayloadCipher.SealedPayload sealed =
                cipher.seal(brainId, runId, message.getId(), type, body);
        message.setCipherAlgorithm(sealed.algorithm());
        message.setNonce(sealed.nonce());
        message.setCiphertext(sealed.ciphertext());
        return message;
    }

    private static LabRunDocument documentFor(UUID runId, RunDocumentFacts facts) {
        LabRunDocument document = new LabRunDocument();
        document.setRunId(runId);
        document.setRegistrationId(facts.registrationId());
        document.setEnginePackageId(facts.enginePackageId());
        document.setPackageRevision(facts.packageRevision());
        document.setProcessingJobId(facts.processingJobId());
        document.setParseGeneration(facts.parseGeneration());
        document.setEnvelopeVersion(facts.envelopeVersion());
        document.setCanonicalizationVersion(facts.canonicalizationVersion());
        document.setEnvelopeSha256(facts.envelopeSha256());
        document.setEnvelopeSizeBytes(facts.envelopeSizeBytes());
        document.setSourceSetSha256(facts.sourceSetSha256());
        document.setReuseEligibility(facts.reuseEligibility());
        document.setDocumentCount(facts.documentCount());
        document.setPageCount(facts.pageCount());
        return document;
    }

    private static LabRunReviewSnapshot snapshotFor(UUID runId, ReviewSnapshotFacts facts) {
        LabRunReviewSnapshot row = new LabRunReviewSnapshot();
        row.setRunId(runId);
        row.setFieldsSha256(facts.fieldsSha256());
        row.setDocumentCount(facts.documentCount());
        row.setMachineCount(facts.machineCount());
        row.setCorrectedCount(facts.correctedCount());
        row.setRejectedCount(facts.rejectedCount());
        Map<String, String> versions = new java.util.LinkedHashMap<>();
        facts.schemaVersions().forEach((id, version) -> versions.put(id.toString(), version));
        row.setSchemaVersions(versions);
        return row;
    }

    /** Exposed for recovery's bounded scans. */
    List<LabRun> expiredRuns(OffsetDateTime now) {
        return runs.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
                LabRun.Status.PROCESSING, now);
    }

    /** Exposed for recovery's bounded scans. */
    List<LabDiscussionExchange> expiredExchanges(OffsetDateTime now) {
        return exchanges.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
                LabRun.Status.PROCESSING, now);
    }

    /** SHA-256 of one canonical string. Used only for group request identity, never for content. */
    private static String sha256Hex(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    /**
     * Moves a one-member group to the terminal state its only member just reached.
     *
     * <p>Deliberately a no-op for anything larger. Phase 4's dispatcher owns real rollup, where
     * mixed outcomes become PARTIAL and one member never decides for its siblings; this exists so
     * that a prototype run's group does not sit at PROCESSING forever after the run it contains
     * has finished. A group that already reached a terminal state is left alone — its guard would
     * refuse the write anyway, and losing a race here must not fail the run's own transaction.
     */
    /** The run's group mode, for a bounded metric tag; null tags as UNKNOWN, never fails. */
    private LabRunGroup.Mode modeOf(LabRun run) {
        if (run.getRunGroupId() == null) {
            return null;
        }
        return groups.findById(run.getRunGroupId())
                .map(LabRunGroup::getMode).orElse(null);
    }

    private void rollUpSingleMemberGroup(LabRun run, LabRunGroup.Status terminal) {
        if (runs.countByRunGroupId(run.getRunGroupId()) != 1) {
            return;
        }
        groups.findById(run.getRunGroupId()).ifPresent(group -> {
            if (group.getStatus() == LabRunGroup.Status.QUEUED
                    || group.getStatus() == LabRunGroup.Status.PROCESSING) {
                group.setStatus(terminal);
                group.setTerminalAt(OffsetDateTime.now());
                groups.saveAndFlush(group);
            }
        });
    }
}
