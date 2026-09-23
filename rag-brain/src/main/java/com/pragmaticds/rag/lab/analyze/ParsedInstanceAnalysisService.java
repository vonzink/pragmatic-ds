package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.findings.Finding;
import com.pragmaticds.rag.lab.findings.FindingAssembler;
import com.pragmaticds.rag.lab.findings.FindingJson;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.lab.parsed.RegistrationSubjectScopeService;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.analyze.RunManifest;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes one fully pinned instance run.
 *
 * <p>Order here is the contract, not a convenience. Compatibility is judged before anything is
 * retrieved; evidence is retrieved before any tool runs; tools run before the model is called; and
 * the output schema is resolved from the server-side allowlist before any of it. Every refusal
 * therefore happens before a token is billed, and a release that names a schema this build does not
 * ship never reaches a provider at all.
 *
 * <p>The service composes rather than reimplements: verification belongs to
 * {@link ParsedDataResolver}, freezing to {@link CorpusSnapshotService}, and prompt, schema,
 * citation, and recording handling to {@link AnalysisService}. What is added here is the pinning —
 * and the provenance that makes the pin checkable afterwards.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class ParsedInstanceAnalysisService {

    /**
     * Everything one pinned run executes against, already verified and frozen.
     *
     * <p>Deliberately carries no subject scope. The scope belongs to the loan, not the run, and is
     * resolved from the registration at analysis time exactly as loan facts are — a per-run copy
     * would be a second channel through which two members of one comparison group could differ.
     */
    public record InstanceAnalysisCommand(
            UUID brainId,
            String instanceSlug,
            UUID analysisRunId,
            ResolvedInstanceRelease release,
            ParsedDataResolver.VerifiedParsedInput parsedInput,
            CorpusSnapshotService.FrozenCorpusSnapshot corpusSnapshot,
            int topK,
            boolean rerank,
            String correlationId,
            String fixtureSubjectScope) {

        /**
         * {@code fixtureSubjectScope} is for evaluations only: a committed fixture came from no
         * registration, so a scenario that asserts a subject key supplies its scope here. A parse
         * with a registration always takes the registration's scope — a per-run one would let two
         * members of one comparison group differ in something other than their declared dimension.
         */
        public InstanceAnalysisCommand {
            Objects.requireNonNull(brainId, "brainId");
            Objects.requireNonNull(instanceSlug, "instanceSlug");
            Objects.requireNonNull(analysisRunId, "analysisRunId");
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(parsedInput, "parsedInput");
            Objects.requireNonNull(corpusSnapshot, "corpusSnapshot");
            Objects.requireNonNull(correlationId, "correlationId");
            if (fixtureSubjectScope != null && parsedInput.registrationId() != null) {
                throw new IllegalArgumentException(
                        "a registered parse takes its scope from the registration");
            }
            if (topK < 1 || topK > 100) {
                throw new IllegalArgumentException("topK must be between 1 and 100");
            }
        }

        public InstanceAnalysisCommand(UUID brainId, String instanceSlug, UUID analysisRunId,
                                       ResolvedInstanceRelease release,
                                       ParsedDataResolver.VerifiedParsedInput parsedInput,
                                       CorpusSnapshotService.FrozenCorpusSnapshot corpusSnapshot,
                                       int topK, boolean rerank, String correlationId) {
            this(brainId, instanceSlug, analysisRunId, release, parsedInput, corpusSnapshot, topK,
                    rerank, correlationId, null);
        }
    }

    /** The analyzer's result plus the record of everything that produced it. */
    public record InstanceRunOutcome(AnalysisResult result, InstanceRunProvenance provenance) {}

    /** Stable, value-free refusals. */
    public static final class InstanceAnalysisException extends RuntimeException {
        public enum Code {
            /** The release predates v2 and cannot describe a pinned parsed run. */
            INSTANCE_RELEASE_NOT_PINNABLE,
            /** The verified parse does not satisfy the release's parsed-data contract. */
            PARSE_INCOMPATIBLE,
            /**
             * Two of the release's rules resolved to one finding subject.
             *
             * <p>A configuration error in the release, in the manner of
             * {@code TOOL_DUPLICATE_CONTRACT}: two rules sharing a subject would share a waiver,
             * so waiving either would silently waive the other. Raised before the provider call,
             * because a release that can never assemble coherently must not cost a billed run to
             * find out.
             */
            FINDING_DUPLICATE_SUBJECT
        }

        private final Code code;

        public InstanceAnalysisException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    private final RetrievalService retrieval;
    private final ParsedDocumentPromptRenderer renderer;
    private final InstanceToolRegistry tools;
    private final InstanceOutputSchemaRegistry schemas;
    private final AnalysisService analysisService;
    private final RegistrationLoanFactsService loanFacts;
    private final RegistrationSubjectScopeService subjectScopes;

    public ParsedInstanceAnalysisService(RetrievalService retrieval,
                                         ParsedDocumentPromptRenderer renderer,
                                         InstanceToolRegistry tools,
                                         InstanceOutputSchemaRegistry schemas,
                                         AnalysisService analysisService,
                                         RegistrationLoanFactsService loanFacts,
                                         RegistrationSubjectScopeService subjectScopes) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.loanFacts = Objects.requireNonNull(loanFacts, "loanFacts");
        this.subjectScopes = Objects.requireNonNull(subjectScopes, "subjectScopes");
    }

    public InstanceRunOutcome analyze(InstanceAnalysisCommand command) {
        Objects.requireNonNull(command, "command");
        InstanceReleaseManifest manifest = pinnable(command.release());
        var parsed = command.parsedInput();

        // A rejection is a refusal, not a warning: the resolver keeps the registration for review
        // but this run stops here, before retrieval, tools, or a single billed token.
        if (!parsed.compatibility().compatible()) {
            throw new InstanceAnalysisException(
                    InstanceAnalysisException.Code.PARSE_INCOMPATIBLE);
        }

        // Resolving the schema is the allowlist and digest check. Doing it now means a release
        // naming a schema this build does not ship fails before it can reach a provider.
        String outputSchema = schemas.requireText(
                manifest.output().schemaId(), manifest.output().schemaSha256());

        List<RetrievedChunk> chunks = retrieve(command, manifest);

        // Only the selection is analyzed; the whole parse stays available for provenance.
        var rendered = renderer.render(parsed.selectedEnvelope(), parsed.compatibility());
        List<InstanceToolRegistry.ExecutedTool> executed =
                tools.executePinned(manifest.tools(), parsed.selectedEnvelope());

        // Assembled here, before the provider call, and not after it. Two rules claiming one
        // subject is a configuration error in the release, and a configuration error must not
        // cost a billed model run to discover. Publication still happens after the answer comes
        // back — only the work that can fail moves forward.
        // A registered parse resolves its scope from the registration, never the command — the
        // loan's package is what carries a scope, and every member pinned to it shares one. Same
        // channel loan facts arrive on.
        // An evaluation analyzes a committed fixture, which came from no registration, so it has
        // no loan facts and only the scope its scenario declares — and both lookups refuse a null
        // registration id.
        UUID registrationId = parsed.registrationId();
        List<Finding> findings = assembleFindings(executed, registrationId == null
                ? command.fixtureSubjectScope()
                : subjectScopes.find(command.brainId(), registrationId));

        RunManifest runManifest = RunManifest.forParsedSource(
                command.analysisRunId(), command.brainId(), command.instanceSlug(),
                parsed.envelopeVersion(),
                new RunManifest.ParsedSource(parsed.packageId(), parsed.revision(),
                        parsed.parseGeneration(), parsed.processingJobId(),
                        parsed.sourceSetSha256(), parsed.envelopeSha256(),
                        (int) parsed.envelopeSizeBytes(), parsed.envelopeVersion(),
                        command.release().release().getId(), null));

        long startedAt = System.nanoTime();
        AnalysisService.ParsedOutcome outcome = analysisService.analyzePinned(
                command.brainId(), runManifest,
                new AnalysisService.PinnedContract(
                        command.instanceSlug(),
                        parsed.envelopeVersion(),
                        direction(manifest),
                        outputSchema,
                        manifest.behavior().retrievalQuery(),
                        command.corpusSnapshot().id(),
                        chunks,
                        manifest.model().provider(),
                        manifest.model().model(),
                        rendered.text(),
                        rendered.handleIds(),
                        command.correlationId(),
                        manifest.behavior().temperature(),
                        // Read from the registration, not the run request: a run group's members
                        // are identified entirely by id so that a comparison differs in exactly
                        // the declared dimension, and a per-run income figure would be another
                        // channel through which two members could diverge. The registration is
                        // the loan's package and is shared by every member pinned to it.
                        registrationId == null
                                ? null : loanFacts.find(command.brainId(), registrationId),
                        computedDomain(executed)));
        long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        AnalysisResult withFindings = outcome.result().withFindingsJson(
                FindingJson.publishInto(outcome.result().findingsJson(), findings));

        return new InstanceRunOutcome(withFindings,
                provenance(command, manifest, chunks, executed, outcome, latencyMillis));
    }

    /**
     * The release's standing direction followed by this run's task.
     *
     * <p>Both, in that order. {@code systemPrompt} is who the instance is and applies to every
     * turn including a follow-up question; {@code taskPrompt} is what to do on a run and would be
     * wrong in a conversation, because it asks for a JSON envelope. Sending only the task prompt
     * dropped the operator's standing direction from every run while the cost estimate and the
     * request digest both counted it — so a release was priced and compared as though it carried
     * an instruction the model never saw.
     *
     * <p>The provider abstraction has one prompt string and no system role, so "system" here means
     * first, not a separate message.
     */
    private static String direction(InstanceReleaseManifest manifest) {
        String system = manifest.behavior().systemPrompt();
        String task = manifest.behavior().taskPrompt();
        if (system == null || system.isBlank()) {
            return task;
        }
        return system + "\n\n" + task;
    }

    /**
     * Snapshot retrieval, before any tool or model call.
     *
     * <p>A release with no retrieval query grounds on parsed facts alone, which is a legitimate
     * shape. A release that declares one and cannot retrieve is not: the failure propagates rather
     * than producing an ungrounded answer recorded against this release.
     */
    private List<RetrievedChunk> retrieve(
            InstanceAnalysisCommand command, InstanceReleaseManifest manifest) {
        String query = manifest.behavior().retrievalQuery();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return retrieval.retrieveSnapshot(query, command.brainId(),
                command.corpusSnapshot().id(), SourceVisibility.INTERNAL,
                command.topK(), command.rerank()).chunks();
    }

    /**
     * The payload a pinned tool built from parsed facts, or null when none did.
     *
     * <p>Selected structurally rather than by tool name — nothing here needs to know which tool
     * is the ledger. {@link InstanceToolRegistry} has already refused a release pinning two
     * domain producers, so the first match is the only match.
     */
    private static JsonNode computedDomain(List<InstanceToolRegistry.ExecutedTool> executed) {
        return executed.stream()
                .filter(InstanceToolRegistry.ExecutedTool::producesDomain)
                .map(InstanceToolRegistry.ExecutedTool::output)
                .findFirst()
                .orElse(null);
    }

    /**
     * The findings this run publishes, stamped and ordered, or a refusal.
     *
     * <p>Separate from publication on purpose. Assembly can fail on the release's own
     * configuration and therefore runs before the model is called; publication needs the model's
     * answer and cannot.
     */
    private static List<Finding> assembleFindings(
            List<InstanceToolRegistry.ExecutedTool> executed, String subjectScope) {
        try {
            return FindingAssembler.assemble(collectedFindings(executed), subjectScope);
        } catch (FindingAssembler.DuplicateSubjectException duplicate) {
            // Not chained: the assembler's message is value-free, but nothing downstream should
            // depend on that, and every other refusal here is a bare code.
            throw new InstanceAnalysisException(
                    InstanceAnalysisException.Code.FINDING_DUPLICATE_SUBJECT);
        }
    }

    /**
     * Every finding the pinned tools produced, in tool order before assembly reorders them.
     *
     * <p>Selected structurally rather than by tool name, like {@link #computedDomain}. Unlike the
     * domain there may be many producers, so these merge instead of the first winning.
     *
     * <p>A findings producer whose output carries no {@code findings} array fails the run rather
     * than contributing nothing. Nothing validates a tool's output against
     * {@code findings-v1.output.schema.json} at execution time, so without this check a tool
     * whose shape drifted would yield zero findings in silence — and findings are not
     * best-effort. The taxonomy is the tool registry's own: from the run's point of view a tool
     * that answered in a shape its contract does not describe did not execute.
     */
    private static List<Finding> collectedFindings(
            List<InstanceToolRegistry.ExecutedTool> executed) {
        List<Finding> merged = new ArrayList<>();
        for (InstanceToolRegistry.ExecutedTool tool : executed) {
            if (tool.producesFindings()) {
                if (tool.output() == null || !tool.output().path("findings").isArray()) {
                    throw new InstanceToolRegistry.ToolException(
                            InstanceToolRegistry.ToolException.Code.TOOL_EXECUTION_FAILED);
                }
                merged.addAll(FindingJson.fromOutput(tool.output()));
            }
        }
        return merged;
    }

    private static InstanceReleaseManifest pinnable(ResolvedInstanceRelease release) {
        if (release.manifest() instanceof DecodedInstanceManifest.V2 v2) {
            return v2.manifest();
        }
        throw new InstanceAnalysisException(
                InstanceAnalysisException.Code.INSTANCE_RELEASE_NOT_PINNABLE);
    }

    private static InstanceRunProvenance provenance(
            InstanceAnalysisCommand command, InstanceReleaseManifest manifest,
            List<RetrievedChunk> chunks, List<InstanceToolRegistry.ExecutedTool> executed,
            AnalysisService.ParsedOutcome outcome, long latencyMillis) {
        var parsed = command.parsedInput();

        List<InstanceRunProvenance.RetrievedEvidence> evidence = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            evidence.add(new InstanceRunProvenance.RetrievedEvidence(
                    chunk.chunkId(), chunk.documentId(), chunk.contentSha256(),
                    chunk.sourceName(), chunk.documentTitle(), chunk.effectiveDate(),
                    chunk.content()));
        }

        List<InstanceRunProvenance.ExecutedToolRecord> toolRecords =
                new ArrayList<>(executed.size());
        for (InstanceToolRegistry.ExecutedTool tool : executed) {
            toolRecords.add(new InstanceRunProvenance.ExecutedToolRecord(
                    tool.name(), tool.version(), tool.inputSchemaSha256(),
                    tool.outputSchemaSha256(), "EXECUTED"));
        }

        ModelRouterService.Resolution resolution = outcome.resolution();
        InstanceRunProvenance.ModelResolution model = resolution == null
                ? new InstanceRunProvenance.ModelResolution(
                        manifest.model().provider(), manifest.model().model(),
                        null, null, null, null, false)
                : new InstanceRunProvenance.ModelResolution(
                        resolution.requestedProvider(), resolution.requestedModel(),
                        resolution.resolvedProvider(), resolution.resolvedModel(),
                        resolution.answeringProvider(), resolution.answeringModel(),
                        resolution.fallbackUsed());

        return new InstanceRunProvenance(
                command.analysisRunId(),
                command.brainId(),
                command.instanceSlug(),
                command.release().release().getId(),
                command.release().release().getManifestSha256(),
                new InstanceRunProvenance.ParsedInputDescriptor(
                        parsed.registrationId(), parsed.packageId(), parsed.revision(),
                        parsed.processingJobId(), parsed.parseGeneration(),
                        parsed.envelopeVersion(), parsed.envelopeSha256(),
                        parsed.envelopeSizeBytes(), parsed.sourceSetSha256(),
                        parsed.selectedSourceIds()),
                command.corpusSnapshot().id(),
                command.corpusSnapshot().manifestSha256(),
                evidence,
                toolRecords,
                model,
                manifest.output().schemaSha256(),
                latencyMillis);
    }
}
