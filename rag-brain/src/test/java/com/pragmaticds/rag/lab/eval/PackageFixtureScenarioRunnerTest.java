package com.pragmaticds.rag.lab.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisCommand;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisException;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.EvaluationException;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.ScenarioRunner.ScenarioOutcome;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.Scenario;
import com.pragmaticds.rag.lab.findings.Finding;
import com.pragmaticds.rag.lab.findings.FindingAssembler;
import com.pragmaticds.rag.lab.findings.FindingJson;
import com.pragmaticds.rag.lab.findings.ManualReviewRequiredFindingTool;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What one case actually measures, and what it must refuse to measure.
 *
 * <p>The fixture registry and the compatibility service are real here rather than stubbed. Whether
 * a release refuses a package is the behavior half of these cases exist to observe, and a stubbed
 * decision would let the test assert a refusal the policy would never have produced.
 */
class PackageFixtureScenarioRunnerTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RELEASE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SNAPSHOT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String INSTANCE = "income";

    private CorpusSnapshotService snapshots;
    private InstanceReleaseResolver releases;
    private ParsedInstanceAnalysisService analysis;
    private SpendGuardService spend;
    private PackageFixtureScenarioRunner runner;

    @BeforeEach
    void setUp() {
        snapshots = mock(CorpusSnapshotService.class);
        releases = mock(InstanceReleaseResolver.class);
        analysis = mock(ParsedInstanceAnalysisService.class);
        spend = mock(SpendGuardService.class);

        when(snapshots.freeze(any())).thenReturn(new CorpusSnapshotService.FrozenCorpusSnapshot(
                SNAPSHOT, BRAIN, "d".repeat(64), List.of(), List.of()));
        when(releases.byId(any(), any())).thenReturn(new ResolvedInstanceRelease(
                null, release(), new DecodedInstanceManifest.V2(manifest()), false));
        when(spend.isOverBudget(BRAIN)).thenReturn(false);

        runner = new PackageFixtureScenarioRunner(new InstancePackageFixtureRegistry(),
                new ParsedDataCompatibilityService(), snapshots, releases, analysis, spend,
                new ObjectMapper());
    }

    // ============================================================ what it can serve

    @Test
    void itServesExactlyTheFixturesThisBuildShips() {
        assertTrue(runner.supports("fixture-paystub-single"));
        assertTrue(runner.supports("fixture-paystub-w2"));
        assertFalse(runner.supports("fixture-invented"));
        assertFalse(runner.supports(null));
    }

    // ============================================================ judgement

    @Test
    void aCaseHoldsWhenEveryRequiredPointerResolvesAndNoForbiddenOneDoes() {
        answers(answered());

        ScenarioOutcome outcome = runner.run(release(), manifest(), scenario(
                "single-paystub-produces-a-cited-report", "fixture-paystub-single",
                List.of("/reportMarkdown", "/findings/facts", "/citations/0"), List.of()));

        assertTrue(outcome.passed());
        assertNull(outcome.failureCode(), "failureCode is null exactly when passed");
        assertEquals("single-paystub-produces-a-cited-report", outcome.scenarioName());
    }

    @Test
    void theShippedReviewRequiredScenarioResolvesIntoAFindingTheRealPipelineProduced() {
        // Loaded from the real shipped set, not hand-built here: this is what proves the
        // scenario's own pointer resolves rather than a pointer this test invented. The shipped
        // income set once forbade a pointer that could never resolve and passed for a release
        // that fabricated findings freely — this scenario exists to prove a pointer into a real
        // finding actually addresses something.
        Scenario shipped = new InstanceScenarioSetRegistry(new ObjectMapper())
                .require("income-smoke", 2).scenarios().stream()
                .filter(candidate -> candidate.packageFixture()
                        .equals("fixture-paystub-review-required"))
                .findFirst().orElseThrow();

        // The findings are produced by the shipped rule, ordered by the shipped assembler and
        // grafted on by the shipped publisher, over the fixture's own envelope and under a null
        // subject scope — which is exactly what a production run supplies today. Nothing here
        // invents the value it then asserts, so the case measures the pipeline rather than the
        // arrangement. That is also why the scenario asserts ruleId and inputDigest and not
        // subjectKey: with a null scope no subject key exists to address.
        when(analysis.analyze(any())).thenAnswer(call -> {
            InstanceAnalysisCommand command = call.getArgument(0);
            List<Finding> findings = FindingAssembler.assemble(
                    FindingJson.fromOutput(new ManualReviewRequiredFindingTool()
                            .execute(command.parsedInput().selectedEnvelope())),
                    "eval-scope");
            return new ParsedInstanceAnalysisService.InstanceRunOutcome(
                    result("## Income", FindingJson.publishInto(
                            "{\"facts\":[],\"calculations\":[],\"missingItems\":[]}", findings)),
                    null);
        });

        ScenarioOutcome outcome = runner.run(release(), manifest(), shipped);

        assertTrue(outcome.passed(), outcome.failureCode());
    }

    @Test
    void theShippedScopedScenarioHandsItsScopeToTheRunAndItsSubjectKeyResolves() {
        Scenario shipped = new InstanceScenarioSetRegistry(new ObjectMapper())
                .require("income-smoke", 5).scenarios().stream()
                .filter(candidate -> candidate.subjectScope() != null)
                .findFirst().orElseThrow();

        // The scope comes from the command the runner built, so the key exists only if the
        // runner forwarded the scenario's scope.
        when(analysis.analyze(any())).thenAnswer(call -> {
            InstanceAnalysisCommand command = call.getArgument(0);
            List<Finding> findings = FindingAssembler.assemble(
                    FindingJson.fromOutput(new ManualReviewRequiredFindingTool()
                            .execute(command.parsedInput().selectedEnvelope())),
                    command.fixtureSubjectScope());
            return new ParsedInstanceAnalysisService.InstanceRunOutcome(
                    result("## Income", FindingJson.publishInto(
                            "{\"facts\":[],\"calculations\":[],\"missingItems\":[]}", findings)),
                    null);
        });

        ScenarioOutcome outcome = runner.run(release(), manifest(), shipped);

        assertTrue(outcome.passed(), outcome.failureCode());
        assertEquals("fixture:review-required", shipped.subjectScope());
    }

    @Test
    void aMissingRequiredPointerNamesItselfInTheCode() {
        // Facts but no calculation — a release that reported what it read and never did the
        // arithmetic the case asks for.
        answers(result("## Income", "{\"facts\":[{\"id\":\"f1\"}],\"calculations\":[]}"));

        // The report is never stored, so a bare code would leave an operator unable to tell which
        // of six assertions broke without paying for another model call to find out.
        ScenarioOutcome outcome = runner.run(release(), manifest(), scenario(
                "needs-a-calculation", "fixture-paystub-single",
                List.of("/reportMarkdown", "/findings/calculations/0"), List.of()));

        assertFalse(outcome.passed());
        assertEquals("SCENARIO_REQUIRED_POINTER_ABSENT:/findings/calculations/0",
                outcome.failureCode());
    }

    @Test
    void aForbiddenPointerThatResolvedIsTheFabricationThisGateExistsToCatch() {
        answers(answered());

        ScenarioOutcome outcome = runner.run(release(), manifest(), scenario(
                "unreadable-paystub-yields-no-fabricated-findings", "fixture-paystub-unreadable",
                List.of("/reportMarkdown"), List.of("/findings/facts/0")));

        assertFalse(outcome.passed());
        assertEquals("SCENARIO_FORBIDDEN_POINTER_PRESENT:/findings/facts/0",
                outcome.failureCode());
    }

    @Test
    void anEmptyEnvelopeSatisfiesTheForbiddenPointersAndStillNeedsTheReport() {
        // The correct answer to an unreadable package: a report saying what is missing, and no
        // facts. Silence is not the same thing, which is why missingItems is required too.
        answers(result("## Nothing legible on this paystub",
                "{\"facts\":[],\"calculations\":[],\"missingItems\":[{\"id\":\"m1\"}]}"));

        ScenarioOutcome outcome = runner.run(release(), manifest(), scenario(
                "unreadable-paystub-yields-no-fabricated-findings", "fixture-paystub-unreadable",
                List.of("/reportMarkdown", "/findings/missingItems/0"),
                List.of("/findings/facts/0", "/findings/calculations/0")));

        assertTrue(outcome.passed());
    }

    // ============================================================ refusal as an answer

    @Test
    void aReleaseRefusingThePackageProducesAResultTheCaseCanAssertOn() {
        when(analysis.analyze(any())).thenThrow(new InstanceAnalysisException(
                InstanceAnalysisException.Code.PARSE_INCOMPATIBLE));

        // A refusal is gate-worthy behavior, not a lost case. The release declined before
        // retrieval, before tools, and before a billed token, and the scenario says so.
        ScenarioOutcome outcome = runner.run(release(), manifest(), scenario(
                "unsupported-package-is-refused-before-any-analysis", "fixture-unsupported-only",
                List.of("/status", "/reason"),
                List.of("/reportMarkdown", "/citations/0", "/findings/facts/0")));

        assertTrue(outcome.passed());
    }

    @Test
    void theUnsupportedFixtureIsGenuinelyIncompatibleWithAnIncomeContract() {
        answers(answered());

        runner.run(release(), manifest(), scenario("case", "fixture-unsupported-only",
                List.of("/reportMarkdown"), List.of()));

        // Decided by the real policy, not by a stub. This is what makes the refusal case above
        // measure the release's contract rather than the test's arrangement: a homeowners
        // insurance declaration is nothing an income contract allows.
        InstanceAnalysisCommand command = captured();
        assertFalse(command.parsedInput().compatibility().compatible());
        assertEquals(ParsedDataCompatibilityService.RejectionCode.NO_SUPPORTED_DOCUMENT,
                command.parsedInput().compatibility().rejection());
    }

    @Test
    void theSupportedFixturesAreCompatibleSoTheirCasesMeasureTheModel() {
        answers(answered());

        runner.run(release(), manifest(), scenario("case", "fixture-paystub-unreadable",
                List.of("/reportMarkdown"), List.of()));

        // A paystub with every field MISSING must stay COMPATIBLE under a PRESERVE contract, or
        // the one case that can catch fabrication would never reach the model at all — it would
        // pass on a refusal, proving only that the release never looked.
        assertTrue(captured().parsedInput().compatibility().compatible());
    }

    // ============================================================ the pinned command

    @Test
    void theCommandCarriesTheFixturesOwnDigestsAndClaimsNoRegistration() {
        answers(answered());

        runner.run(release(), manifest(), scenario("case", "fixture-paystub-single",
                List.of("/reportMarkdown"), List.of()));
        InstanceAnalysisCommand command = captured();
        var parsed = command.parsedInput();

        // Nothing here is invented: the artifact digest was taken over the bytes the registry
        // read, and the source-set digest is the parse's own, already checked against its source
        // content hashes at load.
        assertTrue(parsed.envelopeSha256().matches("[0-9a-f]{64}"));
        assertTrue(parsed.envelopeSizeBytes() > 0);
        assertEquals(InstancePackageFixtureRegistry.sourceSetDigest(parsed.envelope()),
                parsed.sourceSetSha256());
        assertEquals("1.0.0", parsed.envelopeVersion());
        assertEquals("DOCENGINE-C14N-1", parsed.canonicalizationVersion());

        // A fixture came from no registration. A synthetic id would claim a document registration
        // exists, and every provenance record downstream would point at a row nobody can produce.
        assertNull(parsed.registrationId());

        assertEquals(BRAIN, command.brainId());
        assertEquals(INSTANCE, command.instanceSlug());
        assertEquals(SNAPSHOT, command.corpusSnapshot().id());
        assertTrue(command.correlationId().startsWith("eval-"),
                "an evaluation's analysis_run row must be attributable to an evaluation");
    }

    @Test
    void theSnapshotIsFrozenFromTheCollectionsTheManifestPins() {
        answers(answered());
        UUID collection = UUID.fromString("44444444-4444-4444-8444-444444444444");
        InstanceReleaseManifest pinned = manifest(List.of(
                new InstanceReleaseManifest.CollectionRef(collection, 7L)));

        runner.run(release(), pinned, scenario("case", "fixture-paystub-single",
                List.of("/reportMarkdown"), List.of()));

        ArgumentCaptor<CorpusSnapshotService.SnapshotRequest> request =
                ArgumentCaptor.forClass(CorpusSnapshotService.SnapshotRequest.class);
        verify(snapshots).freeze(request.capture());
        assertEquals(BRAIN, request.getValue().brainId());
        assertEquals(List.of(new CorpusSnapshotService.CollectionVersionRef(collection, 7L)),
                request.getValue().collections());
    }

    // ============================================================ what stops an evaluation

    @Test
    void aBrainOverItsBudgetStopsTheEvaluationInsteadOfFailingTheRelease() {
        when(spend.isOverBudget(BRAIN)).thenReturn(true);

        // Recording the cases that did run would be a scored, promotion-authorizing verdict that
        // measured less than it claims. And the refusal comes before anything is assembled: the
        // cheapest place to decline a billed call is before there is anything to bill for.
        assertEquals(EvaluationException.Code.EVALUATION_BUDGET_EXHAUSTED,
                assertThrows(EvaluationException.class,
                        () -> runner.run(release(), manifest(), scenario("case",
                                "fixture-paystub-single", List.of("/reportMarkdown"), List.of())))
                        .code());
        verify(analysis, never()).analyze(any());
        verify(snapshots, never()).freeze(any());
    }

    @Test
    void aReleasePinningCollectionsThisBrainLacksIsRefusedRatherThanRunUngrounded() {
        when(snapshots.freeze(any())).thenThrow(new CorpusSnapshotService.SnapshotException(
                CorpusSnapshotService.SnapshotException.Code.COLLECTION_NOT_FOUND));

        // Not an empty-retrieval pass: every grounded case would fail for a reason that has
        // nothing to do with the release, and every ungrounded one would pass while proving less
        // than it appears to.
        assertEquals(EvaluationException.Code.EVALUATION_CORPUS_UNAVAILABLE,
                assertThrows(EvaluationException.class,
                        () -> runner.run(release(), manifest(), scenario("case",
                                "fixture-paystub-single", List.of("/reportMarkdown"), List.of())))
                        .code());
        verify(analysis, never()).analyze(any());
    }

    @Test
    void anUnexpectedFailureFailsTheCaseRatherThanTheWholeEvaluation() {
        when(analysis.analyze(any())).thenThrow(new IllegalStateException("provider exploded"));

        ScenarioOutcome outcome = runner.run(release(), manifest(), scenario("case",
                "fixture-paystub-single", List.of("/reportMarkdown"), List.of()));

        assertFalse(outcome.passed());
        assertEquals("SCENARIO_RUN_FAILED", outcome.failureCode());
    }

    // ============================================================ spend

    @Test
    void whatTheCallCostGoesOnTheSameDailyTotalAsEveryOtherModelCall() {
        answers(answered());

        runner.run(release(), manifest(), scenario("case", "fixture-paystub-single",
                List.of("/reportMarkdown"), List.of()));

        // An evaluation spends real money and used to spend it invisibly: a loop of them could
        // exhaust a brain's budget with no entry anywhere.
        verify(spend).recordSpend(BRAIN, "claude-opus-5", 1200L, 340L);
    }

    @Test
    void anUnmeasuredCallAddsNothingRatherThanAddingZero() {
        answers(new AnalysisResult(AnalysisResult.Status.SUCCESS, "## Report", "{\"facts\":[]}",
                List.of(), "anthropic", "claude-opus-5", 0, 0, 0.0d, 0, List.of(), null));

        runner.run(release(), manifest(), scenario("case", "fixture-paystub-single",
                List.of("/reportMarkdown"), List.of()));

        // A zero would quietly claim the call was free — the same rule InstanceUsageService keeps.
        verify(spend, never()).recordSpend(any(), anyString(), anyLong(), anyLong());
    }

    // ============================================================ fixtures

    private void answers(AnalysisResult result) {
        when(analysis.analyze(any())).thenReturn(
                new ParsedInstanceAnalysisService.InstanceRunOutcome(result, null));
    }

    private InstanceAnalysisCommand captured() {
        ArgumentCaptor<InstanceAnalysisCommand> command =
                ArgumentCaptor.forClass(InstanceAnalysisCommand.class);
        verify(analysis).analyze(command.capture());
        return command.getValue();
    }

    /** A release that answered in full: a report, a citation, a fact, and a calculation. */
    private static AnalysisResult answered() {
        return result("## Income", "{\"facts\":[{\"id\":\"f1\"}],"
                + "\"calculations\":[{\"id\":\"c1\"}],\"missingItems\":[{\"id\":\"m1\"}]}");
    }

    private static AnalysisResult result(String report, String findingsJson) {
        return new AnalysisResult(AnalysisResult.Status.SUCCESS, report, findingsJson,
                List.of(new com.pragmaticds.rag.dto.CitationDto(
                        "Fixture Handbook", "fixture-paystub-single", "1", "1", "2026-01-01")),
                "anthropic", "claude-opus-5", 1200, 340, 0.02d, 1, List.of(), null);
    }

    private static Scenario scenario(String name, String fixture,
                                     List<String> required, List<String> forbidden) {
        return new Scenario(name, fixture, 1, required, forbidden);
    }

    private static LabInstanceRelease release() {
        LabInstanceRelease release = new LabInstanceRelease();
        release.setId(RELEASE);
        release.setBrainId(BRAIN);
        release.setInstanceSlug(INSTANCE);
        release.setReleaseNumber(1);
        release.setProvenanceMode(LabInstanceRelease.ProvenanceMode.CANDIDATE);
        release.setManifest(Map.of());
        return release;
    }

    private static InstanceReleaseManifest manifest() {
        return manifest(new ArrayList<>());
    }

    private static InstanceReleaseManifest manifest(
            List<InstanceReleaseManifest.CollectionRef> collections) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB", "W2"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(collections),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query",
                        new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.9500")));
    }
}
