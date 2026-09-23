package com.pragmaticds.rag.lab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisCommand;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceRunOutcome;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.model.InstanceUsageService;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs one already-created {@code lab_run} to a terminal state.
 *
 * <p>This owns the outcome, not the dispatch: the run row already exists when execution starts,
 * and Phase 4 will own creating and scheduling groups of them. What happens here is narrow — do
 * the work, then move the run to exactly one terminal state, once.
 *
 * <p>Every failure becomes a stable code. A provider exception routinely quotes a request URI and
 * the provider's own response body, and a tool failure can carry borrower values, so neither a
 * message nor a cause is ever stored, logged, or returned; the code and the correlation id are the
 * whole disclosure. Failures arrive two ways and both end here: as an exception, or in-band as an
 * {@code ERROR} result — the shared analyzer reports provider and validation failures as a result
 * row rather than by throwing, because its legacy surface renders that row. Either way the run is
 * FAILED: sealing an error placeholder would consume the reservation and record a success that
 * never produced an envelope.
 *
 * <p>Interrupted runs are left alone by construction. completeRun and failRun both require the run
 * to still be PROCESSING, so a run the lease sweeper already marked INTERRUPTED cannot be revived
 * here — which is what makes an expired lease a no-replay boundary rather than a race.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceExecutionService {

    private static final Logger log = LoggerFactory.getLogger(InstanceExecutionService.class);

    /** The generic refusal for anything without its own vocabulary. */
    private static final String UNCLASSIFIED = "INSTANCE_RUN_FAILED";

    /** One run's terminal state, in codes only. */
    public record ExecutionOutcome(UUID runId, String status, String failureCode) {}

    private final ParsedInstanceAnalysisService analysis;
    private final LabRunTransactionService transactions;
    private final LabAuditService audit;
    private final LabManifestWriter writer;
    private final InstanceUsageService usage;
    private final ObjectMapper mapper;

    public InstanceExecutionService(ParsedInstanceAnalysisService analysis,
                                    LabRunTransactionService transactions,
                                    LabAuditService audit,
                                    LabManifestWriter writer,
                                    InstanceUsageService usage,
                                    ObjectMapper mapper) {
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Executes one run and seals its result.
     *
     * <p>The output and the provenance are sealed together with the status change, so a successful
     * run always has both or the run is not successful.
     */
    public ExecutionOutcome execute(UUID runId, InstanceAnalysisCommand command) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(command, "command");
        try {
            InstanceRunOutcome outcome = analysis.analyze(command);
            if (outcome.result().status() != AnalysisResult.Status.SUCCESS) {
                // On the lab-safe path the reason is already a ParsedFailureCode name; anything
                // else collapses rather than trusting a field that can carry free text. Tokens
                // travel when the error came after a billed call, so a validation failure at two
                // attempts — the most expensive run in the system — never reports as free.
                String code = vocabularyOf(outcome.result().reason());
                log.warn("Instance run {} did not complete ({}) [{}]",
                        runId, code, command.correlationId());
                return failed(runId, command.brainId(), code,
                        nullIfZero(outcome.result().inputTokens()),
                        nullIfZero(outcome.result().outputTokens()));
            }
            transactions.completeRun(runId, command.brainId(), command.analysisRunId(),
                    encode(outcome), outcome.provenance().canonicalBytes(writer));

            audit.record(command.brainId(), LabAuditService.RUN_TERMINAL,
                    LabAuditEvent.Status.SUCCEEDED, LabAuditEvent.SubjectType.RUN, runId, null,
                    Map.of("retrievedChunks", outcome.provenance().retrieved().size(),
                            "tools", outcome.provenance().tools().size(),
                            "inputTokens", outcome.result().inputTokens(),
                            "outputTokens", outcome.result().outputTokens(),
                            "fallbackUsed", outcome.provenance().model().fallbackUsed()));
            // The measured truth, recorded once against the PENDING usage row. The V33 result
            // carries primitive token counts where zero means "the provider did not say", so
            // zero maps to null — reporting a fabricated zero would claim the call was free.
            usage.report(runId, command.brainId(), new ModelEstimate.ProviderUsage(
                    nullIfZero(outcome.result().inputTokens()), null,
                    nullIfZero(outcome.result().outputTokens())));
            return new ExecutionOutcome(runId, "SUCCEEDED", null);
        } catch (RuntimeException failure) {
            String code = safeCode(failure);
            // Class name only. A provider message quotes the request URI and the provider's own
            // response body, and neither belongs in a log line.
            log.warn("Instance run {} failed ({}) [{}]",
                    runId, failure.getClass().getSimpleName(), command.correlationId());
            // An exception carries no trustworthy token counts, so none are reported.
            return failed(runId, command.brainId(), code, null, null);
        }
    }

    /**
     * Fails a run that never became executable — the dispatcher could not rebuild its pinned
     * command. Same terminal semantics as an execution failure: the code is the whole
     * disclosure, an audit row is written, and the usage row settles honestly UNAVAILABLE
     * rather than sitting PENDING forever on a terminal run.
     */
    public ExecutionOutcome failUnprepared(UUID runId, UUID brainId, String code) {
        return failed(runId, brainId, code, null, null);
    }

    /**
     * One failed run: no seal, the code as the whole disclosure, and the usage row honestly
     * terminal — real token counts when a billed call preceded the failure, otherwise all null,
     * which the usage service records as UNAVAILABLE rather than PENDING forever or a fabricated
     * zero.
     */
    private ExecutionOutcome failed(UUID runId, UUID brainId, String code,
                                    Long inputTokens, Long outputTokens) {
        try {
            transactions.failRun(runId, brainId, code);
        } catch (IncomeLabService.LabRequestException raced) {
            // The lease sweeper reached the row first: its honest terminal state is already
            // written and settled by the writer. Re-labelling it would replay a decision the
            // no-replay boundary exists to forbid, so nothing is audited or settled here.
            return new ExecutionOutcome(runId, "FAILED", code);
        }
        audit.record(brainId, LabAuditService.RUN_TERMINAL,
                LabAuditEvent.Status.FAILED, LabAuditEvent.SubjectType.RUN, runId, code,
                Map.of("failed", true));
        usage.report(runId, brainId,
                new ModelEstimate.ProviderUsage(inputTokens, null, outputTokens));
        return new ExecutionOutcome(runId, "FAILED", code);
    }

    /**
     * The stable code for one failure.
     *
     * <p>Each layer publishes its own value-free vocabulary, and this only selects between them.
     * Anything unrecognised collapses to one generic code rather than exposing a class name that
     * could hint at internal structure.
     */
    private static String safeCode(RuntimeException failure) {
        return switch (failure) {
            case ParsedInstanceAnalysisService.InstanceAnalysisException e -> e.code().name();
            case ParsedDataResolver.ParsedDataException e -> e.code().name();
            case InstanceToolRegistry.ToolException e -> e.code().name();
            case InstanceOutputSchemaRegistry.OutputSchemaException e -> e.code().name();
            case CorpusSnapshotService.SnapshotException e -> e.code().name();
            case ModelRouterService.SanitizedProviderException e -> e.code().name();
            default -> UNCLASSIFIED;
        };
    }

    private static Long nullIfZero(int tokens) {
        return tokens > 0 ? (long) tokens : null;
    }

    /** The failure-code vocabulary shape: uppercase words, never message text or identifiers. */
    private static final Pattern VOCABULARY = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    /**
     * An in-band result's reason, admitted only if it is vocabulary.
     *
     * <p>On the lab-safe path the reason is exactly a {@code ParsedFailureCode} name, but the
     * field's type is free text and other paths write messages into it — so the shape is checked
     * rather than the origin trusted, and anything else collapses to the generic code.
     */
    private static String vocabularyOf(String reason) {
        return reason != null && VOCABULARY.matcher(reason).matches() ? reason : UNCLASSIFIED;
    }

    /** The analyzer result as bytes for sealing. Never written anywhere but ciphertext. */
    private byte[] encode(InstanceRunOutcome outcome) {
        try {
            return mapper.writeValueAsBytes(outcome.result());
        } catch (JsonProcessingException unserializable) {
            // The cause carries the result's own content, so it is deliberately not chained.
            throw new IllegalStateException("analysis result could not be encoded for sealing");
        }
    }
}
