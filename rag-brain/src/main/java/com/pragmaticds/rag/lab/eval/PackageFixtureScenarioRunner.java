package com.pragmaticds.rag.lab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisCommand;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisException;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.EvaluationException;
import com.pragmaticds.rag.lab.eval.InstancePackageFixtureRegistry.PackageFixture;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.Scenario;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.parsed.ParsedInputSelection;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs one scenario by executing the candidate release against a committed parse.
 *
 * <p><b>The model call is real.</b> A scenario asks whether this release produces a correctly
 * shaped, properly cited answer that does not invent what the parse did not contain — and shape,
 * citation discipline, and restraint are model behaviors. A runner that answered from a canned
 * fixture could only check wiring, and a gate that cannot observe the model cannot gate on it,
 * which is how a promotion gate ends up certifying without checking.
 *
 * <p><b>Everything but the parse is the production path.</b> Compatibility is judged by the same
 * {@link ParsedDataCompatibilityService} a real run uses, against the same policy built from the
 * release's own contract; the corpus snapshot is frozen from the collections the manifest pins;
 * retrieval, prompt assembly, tools, the output schema, and the model call are
 * {@link ParsedInstanceAnalysisService} unchanged. Only the parse is substituted, because a
 * committed envelope is the one thing a promotion gate cannot get from a live borrower package.
 *
 * <p><b>No {@code LabRun} is written.</b> An evaluation is not a run: it must not appear in a run
 * group, in member listings, or in anything that counts what an operator submitted. That is why
 * this calls the analysis service directly rather than going through the execution service. The
 * analyzer's own {@code analysis_run} row is still written, because a model call that spends money
 * and leaves no trace is worse than one an operator can find; the correlation id marks it.
 *
 * <p><b>A refusal is a result, not an error.</b> When the release's contract rejects the fixture —
 * a package containing nothing it allows, say — the run refuses before retrieval, before tools,
 * and before a billed token. That refusal is a real, gate-worthy behavior, so it becomes a
 * {@code REFUSED} assertion document that the scenario's pointers judge, rather than an exception
 * that loses the case. A scenario can then require {@code /status} and {@code /reason} and forbid
 * the report that a refusing release never produced.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class PackageFixtureScenarioRunner implements InstanceEvaluationService.ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(PackageFixtureScenarioRunner.class);

    /** The same retrieval width a dispatched run uses, so a case is judged on the run's shape. */
    private static final int RETRIEVAL_TOP_K = 8;

    /** Why one case did not hold. Stable codes; the pointer names a reviewed scenario file. */
    static final String REQUIRED_POINTER_ABSENT = "SCENARIO_REQUIRED_POINTER_ABSENT";
    static final String FORBIDDEN_POINTER_PRESENT = "SCENARIO_FORBIDDEN_POINTER_PRESENT";
    static final String RESULT_UNREADABLE = "SCENARIO_RESULT_UNREADABLE";
    static final String RUN_FAILED = "SCENARIO_RUN_FAILED";

    private final InstancePackageFixtureRegistry fixtures;
    private final ParsedDataCompatibilityService compatibility;
    private final CorpusSnapshotService snapshots;
    private final InstanceReleaseResolver releases;
    private final ParsedInstanceAnalysisService analysis;
    private final SpendGuardService spend;
    private final ObjectMapper mapper;

    public PackageFixtureScenarioRunner(InstancePackageFixtureRegistry fixtures,
                                        ParsedDataCompatibilityService compatibility,
                                        CorpusSnapshotService snapshots,
                                        InstanceReleaseResolver releases,
                                        ParsedInstanceAnalysisService analysis,
                                        SpendGuardService spend,
                                        ObjectMapper mapper) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.spend = Objects.requireNonNull(spend, "spend");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public boolean supports(String packageFixture) {
        return fixtures.has(packageFixture);
    }

    @Override
    public ScenarioOutcome run(LabInstanceRelease release, InstanceReleaseManifest manifest,
                               Scenario scenario) {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(scenario, "scenario");

        AnalysisResult result;
        try {
            result = execute(release, manifest, scenario);
        } catch (EvaluationException cannotEvaluate) {
            // Not a verdict about the release. Propagating loses the whole evaluation, which is
            // correct: recording three passes and one "we could not check" would be a scored,
            // promotion-authorizing result that measured less than it claims.
            throw cannotEvaluate;
        } catch (RuntimeException failure) {
            // Class name only: a provider or resolution failure's message routinely carries a
            // request URI and the provider's own response body.
            log.warn("Scenario {} could not be run against release {} ({})",
                    scenario.name(), release.getId(), failure.getClass().getSimpleName());
            return new ScenarioOutcome(scenario.name(), false, RUN_FAILED);
        }
        return judge(scenario, result);
    }

    // ================================================================ execution

    private AnalysisResult execute(LabInstanceRelease release, InstanceReleaseManifest manifest,
                                   Scenario scenario) {
        UUID brainId = release.getBrainId();

        // Checked before the fixture is even loaded, because the cheapest place to decline a
        // billed call is before anything has been assembled for it.
        if (spend.isOverBudget(brainId)) {
            throw new EvaluationException(EvaluationException.Code.EVALUATION_BUDGET_EXHAUSTED);
        }

        PackageFixture fixture = fixtures.require(scenario.packageFixture());
        ResolvedInstanceRelease resolved = releases.byId(
                new InstanceKey(brainId, release.getInstanceSlug()), release.getId());
        CorpusSnapshotService.FrozenCorpusSnapshot snapshot = freeze(brainId, manifest);

        InstanceAnalysisCommand command = new InstanceAnalysisCommand(
                brainId, release.getInstanceSlug(), UUID.randomUUID(), resolved,
                parsedInput(fixture, manifest), snapshot, RETRIEVAL_TOP_K, false,
                "eval-" + release.getId(), scenario.subjectScope());

        AnalysisResult result;
        try {
            result = analysis.analyze(command).result();
        } catch (InstanceAnalysisException refused) {
            // The release declined the package. That is an answer, and one a scenario may assert.
            return refusal(refused.code().name());
        }
        recordSpend(brainId, result);
        return result;
    }

    /**
     * Freezes the snapshot from the collections the manifest pins, at evaluation time.
     *
     * <p>A release naming a collection this brain does not have, has disabled, or has moved past
     * the pinned version is refused rather than evaluated against whatever retrieval happens to
     * return. Empty retrieval would let every grounded case fail for a reason that has nothing to
     * do with the release's behavior, and a passing case would mean less than it appears to.
     *
     * <p>Frozen per scenario rather than once per evaluation. {@code freeze} is content-addressed,
     * so identical pins reuse one snapshot row, and re-freezing means a collection that moved
     * mid-evaluation is caught rather than silently straddled by two halves of one verdict.
     */
    private CorpusSnapshotService.FrozenCorpusSnapshot freeze(
            UUID brainId, InstanceReleaseManifest manifest) {
        try {
            return snapshots.freeze(new CorpusSnapshotService.SnapshotRequest(brainId,
                    manifest.corpus().collections().stream()
                            .map(collection -> new CorpusSnapshotService.CollectionVersionRef(
                                    collection.collectionId(), collection.collectionVersion()))
                            .toList()));
        } catch (CorpusSnapshotService.SnapshotException unavailable) {
            throw new EvaluationException(EvaluationException.Code.EVALUATION_CORPUS_UNAVAILABLE);
        }
    }

    /**
     * Assembles the pinned parsed input from a committed envelope.
     *
     * <p>Every digest is the fixture's own: the artifact digest was taken over the bytes the
     * registry read, the source-set digest is the parse's and was checked against its own source
     * content hashes at load. Nothing here invents an identity.
     *
     * <p>{@code registrationId} is null because a fixture came from no registration. A synthetic
     * id would claim a document registration exists, and every provenance record downstream of an
     * evaluation would then point at a row nobody can produce.
     *
     * <p>Compatibility is judged on the selected envelope against the release's own contract,
     * through the same service and the same review vocabulary a dispatched run uses — so a release
     * that would refuse this package in production refuses it here.
     */
    private ParsedDataResolver.VerifiedParsedInput parsedInput(
            PackageFixture fixture, InstanceReleaseManifest manifest) {
        ParsedInputSelection.SelectionResult selection =
                ParsedInputSelection.select(fixture.envelope(), fixture.sourceIds());
        ParsedDataCompatibilityService.CompatibilityDecision decision = compatibility.evaluate(
                selection.selectedEnvelope(),
                ParsedDataCompatibilityService.Policy.of(manifest.parsedData(),
                        ParsedDataCompatibilityService.REVIEW_WARNING_VALIDATION_STATUSES));

        return new ParsedDataResolver.VerifiedParsedInput(
                null,
                fixture.envelope().packageId(),
                fixture.envelope().generation().packageRevision(),
                fixture.envelope().generation().processingJobId(),
                fixture.envelope().generation().parseGeneration(),
                fixture.envelope().envelopeVersion(),
                fixture.envelope().canonicalizationVersion(),
                fixture.artifact().sha256(),
                fixture.artifact().byteCount(),
                fixture.envelope().generation().sourceSetSha256(),
                selection.selectedSources().stream()
                        .map(ParsedInputSelection.SelectedSource::sourceId).toList(),
                fixture.envelope(),
                selection.selectedEnvelope(),
                decision);
    }

    /**
     * Adds what this call cost to the brain's daily total.
     *
     * <p>An evaluation spends real money and used to spend it invisibly: nothing on this path
     * touched a cost record, so a loop of evaluations could exhaust a brain's budget with no entry
     * anywhere. It writes to the same daily counter ordinary asks and dispatched runs write to —
     * one bill, one place to look.
     *
     * <p>An unmeasured call adds nothing rather than adding zero, which is the same rule
     * {@code InstanceUsageService} follows: a zero would quietly claim the call was free.
     */
    private void recordSpend(UUID brainId, AnalysisResult result) {
        if (result.inputTokens() == 0 && result.outputTokens() == 0) {
            return;
        }
        spend.recordSpend(brainId, result.model(),
                (long) result.inputTokens(), (long) result.outputTokens());
    }

    /** The result shape a release's own refusal produces: a status, a reason, and nothing else. */
    private static AnalysisResult refusal(String reason) {
        return new AnalysisResult(AnalysisResult.Status.REFUSED, null, null, List.of(),
                null, null, 0, 0, 0.0d, 0, List.of(), reason);
    }

    // ================================================================ judgement

    /**
     * Every required pointer must resolve and no forbidden one may.
     *
     * <p>Both directions, and the forbidden direction is the one that carries the weight: a
     * required pointer that cannot resolve fails loudly and someone fixes the set, while a
     * forbidden pointer that cannot resolve is satisfied by every release forever.
     * {@link InstanceAssertionDocument} refuses an unaddressable pointer when the set is read, so
     * by the time a case is judged both lists are known to address something real.
     *
     * <p>The failing pointer is part of the code. It comes from a source-controlled, reviewed
     * scenario file rather than from a model or a package, so it carries no value the taxonomy
     * exists to keep out — and without it an operator reading a failed verdict cannot tell which
     * assertion broke, because the report itself is deliberately never stored.
     */
    private ScenarioOutcome judge(Scenario scenario, AnalysisResult result) {
        JsonNode document;
        try {
            document = InstanceAssertionDocument.of(
                    mapper.writeValueAsString(result).getBytes(StandardCharsets.UTF_8), mapper);
        } catch (Exception unreadable) {
            return new ScenarioOutcome(scenario.name(), false, RESULT_UNREADABLE);
        }

        for (String pointer : scenario.requiredPointers()) {
            if (!InstanceAssertionDocument.resolves(document, pointer)) {
                return new ScenarioOutcome(scenario.name(), false,
                        REQUIRED_POINTER_ABSENT + ":" + pointer);
            }
        }
        for (String pointer : scenario.forbiddenPointers()) {
            if (InstanceAssertionDocument.resolves(document, pointer)) {
                return new ScenarioOutcome(scenario.name(), false,
                        FORBIDDEN_POINTER_PRESENT + ":" + pointer);
            }
        }
        return new ScenarioOutcome(scenario.name(), true, null);
    }
}
