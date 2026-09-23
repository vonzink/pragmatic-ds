package com.pragmaticds.rag.lab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisCommand;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisException;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.EvaluationException;
import com.pragmaticds.rag.lab.instance.VerifiedInstanceReleaseReader;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.EvaluationResult;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.ScenarioRunner;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.Scenario;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.ScenarioSet;
import com.pragmaticds.rag.lab.findings.Finding;
import com.pragmaticds.rag.lab.findings.FindingAssembler;
import com.pragmaticds.rag.lab.findings.FindingJson;
import com.pragmaticds.rag.lab.findings.ManualReviewRequiredFindingTool;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.run.domain.LabReleaseEvaluation;
import com.pragmaticds.rag.lab.run.repository.LabReleaseEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an evaluation records, and what it refuses to record.
 *
 * <p>The verdict is a score, a pass flag, and a digest. The report itself contains model output
 * about the fixture documents and never reaches the database, so the digest is what makes the
 * stored verdict checkable at all: a result and a report that no longer hash together are visibly
 * not about each other.
 */
class InstanceEvaluationServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RELEASE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String INSTANCE = "income";

    private InstanceScenarioSetRegistry registry;
    private LabInstanceReleaseRepository releases;
    private LabReleaseEvaluationRepository evaluations;
    private VerifiedInstanceReleaseReader manifests;

    @BeforeEach
    void setUp() {
        registry = new InstanceScenarioSetRegistry(new ObjectMapper());
        releases = mock(LabInstanceReleaseRepository.class);
        evaluations = mock(LabReleaseEvaluationRepository.class);
        manifests = mock(VerifiedInstanceReleaseReader.class);

        when(releases.findByIdAndBrainIdAndInstanceSlug(RELEASE, BRAIN, INSTANCE))
                .thenReturn(Optional.of(release()));
        when(manifests.read(any(LabInstanceRelease.class)))
                .thenReturn(new DecodedInstanceManifest.V2(manifest(new BigDecimal("0.9500"))));
        when(evaluations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void theShippedScenarioSetLoadsAndCarriesNoExpectedBorrowerValues() {
        ScenarioSet set = registry.require("income-smoke", 2);

        assertEquals(5, set.scenarios().size());
        assertTrue(set.sha256().matches("[0-9a-f]{64}"));
        for (Scenario scenario : set.scenarios()) {
            assertTrue(scenario.packageFixture().startsWith("fixture-"),
                    "a scenario set must never name a real borrower package");
            // Assertions are JSON pointers into the assertion document, so a case checks that the
            // answer is shaped correctly and cites its evidence, never that it says a number.
            //
            // Addressability is asserted on BOTH lists, and the forbidden one is the reason this
            // test matters. A forbidden pointer that cannot resolve is satisfied by every release
            // forever — it records a promotion-authorizing pass that measured nothing. The shipped
            // set once forbade the v1-era /findings/items/0 while the v2 envelope calls that array
            // facts, so the single case written to prove an instance does not fabricate findings
            // would have passed for a release that fabricated them freely.
            for (String pointer : scenario.requiredPointers()) {
                assertTrue(InstanceAssertionDocument.addressable(pointer),
                        "required pointer must address the assertion document: " + pointer);
            }
            for (String pointer : scenario.forbiddenPointers()) {
                assertTrue(InstanceAssertionDocument.addressable(pointer),
                        "a forbidden pointer that cannot resolve can never fail: " + pointer);
            }
        }
    }

    @Test
    void everyFixtureTheShippedSetNamesIsOneThisBuildActuallyShips() {
        InstancePackageFixtureRegistry fixtures = new InstancePackageFixtureRegistry();

        // The two allowlists are separate files and drift silently. A scenario naming a fixture
        // nobody ships does not fail quietly — evaluation refuses with RUNNER_UNAVAILABLE, which
        // reads like missing infrastructure rather than a typo in a set, and nothing is
        // promotable until someone works out which of the two it was.
        for (Scenario scenario : registry.require("income-smoke", 2).scenarios()) {
            assertTrue(fixtures.has(scenario.packageFixture()),
                    scenario.name() + " names a fixture this build does not ship: "
                            + scenario.packageFixture());
        }
    }

    @Test
    void everyNegativeScenarioForbidsSomethingThatCouldActuallyHaveHappened() {
        List<Scenario> negatives = registry.require("income-smoke", 2).scenarios().stream()
                .filter(scenario -> !scenario.forbiddenPointers().isEmpty())
                .toList();
        assertFalse(negatives.isEmpty(), "the set must carry at least one negative case");

        // Not just addressable in the abstract: every forbidden pointer must resolve against a
        // document where the analyzer DID answer in full, or the case cannot distinguish a
        // release that declined to fabricate from one that was never in a position to.
        // Built with Jackson rather than an escaped literal — JSON nested inside a JSON string is
        // exactly where a hand-written fixture goes quietly wrong.
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode envelope = mapper.createObjectNode();
        envelope.putArray("facts").addObject().put("id", "f1");
        envelope.putArray("calculations").addObject().put("id", "calc1");
        envelope.putArray("missingItems").addObject().put("id", "m1");
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCESS");
        result.put("reportMarkdown", "## Report");
        result.put("reason", "NONE");
        result.putArray("citations").addObject().put("id", "c1");
        result.put("findingsJson", envelope.toString());

        JsonNode answered = InstanceAssertionDocument.of(
                result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), mapper);
        for (Scenario negative : negatives) {
            for (String pointer : negative.forbiddenPointers()) {
                assertTrue(InstanceAssertionDocument.resolves(answered, pointer),
                        negative.name() + " could never trip " + pointer);
            }
        }
    }

    /**
     * The two negative cases forbid absences for opposite reasons, and both must stay reachable.
     *
     * <p>An absence is only worth asserting when the fixture puts the release in a position to
     * produce the thing being forbidden. {@code fixture-unsupported-only} contains nothing an
     * income contract allows, so a release refuses it before analysis and the forbidden report
     * never exists — that case gates the refusal itself. {@code fixture-paystub-unreadable} is a
     * compatible paystub with every field MISSING, so the release <em>is</em> asked and has
     * nothing true to answer with — that is the case that can catch fabrication.
     */
    @Test
    void theTwoNegativeCasesRestOnDifferentFixturesForDifferentReasons() {
        List<Scenario> negatives = registry.require("income-smoke", 2).scenarios().stream()
                .filter(scenario -> !scenario.forbiddenPointers().isEmpty())
                .toList();

        assertEquals(2, negatives.size());
        assertEquals(2, negatives.stream().map(Scenario::packageFixture).distinct().count(),
                "two negative cases over one fixture test one situation twice");

        Scenario refusal = negatives.stream()
                .filter(scenario -> scenario.packageFixture().equals("fixture-unsupported-only"))
                .findFirst().orElseThrow();
        assertTrue(refusal.requiredPointers().contains("/status"));
        assertTrue(refusal.requiredPointers().contains("/reason"));
        assertTrue(refusal.forbiddenPointers().contains("/reportMarkdown"),
                "a refused package produces no report to forbid findings in");

        Scenario fabrication = negatives.stream()
                .filter(scenario -> scenario.packageFixture().equals("fixture-paystub-unreadable"))
                .findFirst().orElseThrow();
        assertTrue(fabrication.requiredPointers().contains("/reportMarkdown"),
                "this case only means something if the release actually answered");
        assertTrue(fabrication.requiredPointers().contains("/findings/missingItems/0"),
                "saying what is missing is the correct answer, not merely staying silent");
        assertTrue(fabrication.forbiddenPointers().contains("/findings/facts/0"));
    }

    @Test
    void aSetNobodyShipsIsNotAGateThatCanApproveAnything() {
        assertTrue(registry.find("invented-set", 1).isEmpty());
        assertTrue(registry.find("income-smoke", 99).isEmpty());
        assertEquals(InstanceScenarioSetRegistry.ScenarioSetException.Code
                        .SCENARIO_SET_NOT_ALLOWLISTED,
                assertThrows(InstanceScenarioSetRegistry.ScenarioSetException.class,
                        () -> registry.require("invented-set", 1)).code());
    }

    // ============================================================ the socket and the plug

    /**
     * The shipped set, the shipped fixtures, and the real runner, end to end.
     *
     * <p>Everything but the model is production wiring: the registry reads the real set, the
     * runner loads the real fixtures, and the real compatibility service judges each parse against
     * the release's own contract. Only the analyzer is stood in for, because the point here is
     * whether the gate distinguishes one kind of answer from another — and to do that a test has
     * to be able to choose the answer.
     */
    private InstanceEvaluationService wired(java.util.function.Function<
            InstanceAnalysisCommand, AnalysisResult> analyzer) {
        CorpusSnapshotService snapshots = mock(CorpusSnapshotService.class);
        InstanceReleaseResolver resolver = mock(InstanceReleaseResolver.class);
        ParsedInstanceAnalysisService analysis = mock(ParsedInstanceAnalysisService.class);

        when(snapshots.freeze(any())).thenReturn(new CorpusSnapshotService.FrozenCorpusSnapshot(
                UUID.fromString("55555555-5555-4555-8555-555555555555"), BRAIN, "e".repeat(64),
                List.of(), List.of()));
        when(resolver.byId(any(), any())).thenReturn(new ResolvedInstanceRelease(
                null, release(), new DecodedInstanceManifest.V2(manifest(null)), false));
        when(analysis.analyze(any())).thenAnswer(call -> {
            InstanceAnalysisCommand command = call.getArgument(0);
            // Exactly what the real service does first, and the reason the refusal case can be
            // asserted at all: a package the contract rejects never reaches a provider.
            if (!command.parsedInput().compatibility().compatible()) {
                throw new InstanceAnalysisException(
                        InstanceAnalysisException.Code.PARSE_INCOMPATIBLE);
            }
            return new ParsedInstanceAnalysisService.InstanceRunOutcome(
                    analyzer.apply(command), null);
        });

        return service(new PackageFixtureScenarioRunner(new InstancePackageFixtureRegistry(),
                new ParsedDataCompatibilityService(), snapshots, resolver, analysis,
                mock(SpendGuardService.class), new ObjectMapper()));
    }

    @Test
    void aReleaseThatAnswersTheSameWayWhateverItIsHandedDoesNotPassThisSet() {
        // Facts, a calculation, and a citation for every package, including one containing no
        // legible value at all.
        EvaluationResult result = wired(command -> answer("## Income",
                "{\"facts\":[{\"id\":\"f1\"}],\"calculations\":[{\"id\":\"c1\"}],"
                        + "\"missingItems\":[]}"))
                .evaluate(BRAIN, INSTANCE, RELEASE);

        assertFalse(result.passed());
        assertEquals(5, result.scenariosRun());

        // Two cases catch it: the unreadable paystub, whose missingItems requirement a
        // one-size answer never satisfies, and the review-required paystub, whose findings
        // requirement a one-size answer with no findings node never satisfies either. The
        // refusal case passes for this release too — the contract rejects that package before
        // the analyzer is asked, so a release inclined to fabricate never gets the chance there.
        assertEquals(3, result.scenariosPassed());
    }

    @Test
    void aReleaseThatAnswersFromWhatItWasActuallyGivenPassesEveryCase() {
        EvaluationResult result = wired(command -> grounded(command))
                .evaluate(BRAIN, INSTANCE, RELEASE);

        // The first end-to-end proof that this set can be satisfied at all. Before the runner
        // existed nothing could be promoted, and a gate nothing can pass is only accidentally
        // different from a gate nothing can fail.
        assertTrue(result.passed());
        assertEquals(5, result.scenariosRun());
        assertEquals(5, result.scenariosPassed());
        assertEquals(new BigDecimal("1.0000"), result.score());
        assertTrue(result.reportSha256().matches("[0-9a-f]{64}"));
    }

    /**
     * An honest analyzer: it reports what the parse contained, and says so when it contained
     * nothing.
     *
     * <p>The findings half is not simulated. The shipped rule runs over the fixture's own
     * envelope, the shipped assembler orders and stamps the result under the command's own
     * subject scope — null, exactly as a production run supplies — and the shipped publisher
     * grafts the array onto the envelope. A test that hand-wrote the finding it then asserts
     * would prove only that the string it typed contains the key it looked for.
     */
    private static AnalysisResult grounded(InstanceAnalysisCommand command) {
        EngineResultEnvelope envelope = command.parsedInput().selectedEnvelope();
        boolean anythingLegible = envelope.documents().stream()
                .flatMap(document -> document.fields().stream())
                .anyMatch(field -> field.status() == EngineResultEnvelope.FieldStatus.FOUND);
        if (!anythingLegible) {
            return answer("## No legible values on this paystub",
                    "{\"facts\":[],\"calculations\":[],\"missingItems\":[{\"id\":\"m1\"}]}");
        }
        List<Finding> findings = FindingAssembler.assemble(
                FindingJson.fromOutput(
                        new ManualReviewRequiredFindingTool().execute(envelope)),
                "eval-scope");
        return answer("## Income", FindingJson.publishInto(
                "{\"facts\":[{\"id\":\"f1\"}],\"calculations\":[{\"id\":\"c1\"}],"
                        + "\"missingItems\":[]}", findings));
    }

    private static AnalysisResult answer(String report, String findingsJson) {
        return new AnalysisResult(AnalysisResult.Status.SUCCESS, report, findingsJson,
                List.of(new com.pragmaticds.rag.dto.CitationDto(
                        "Fixture Handbook", "fixture", "1", "1", "2026-01-01")),
                "anthropic", "claude-opus-5", 1200, 340, 0.02d, 1, List.of(), null);
    }

    @Test
    void aCaseNoRunnerCanServeFailsTheEvaluationRatherThanBeingSkipped() {
        InstanceEvaluationService service = service();

        // The correct direction to fail. Skipping the case and scoring the rest would record a
        // promotion-authorizing verdict for a set that was only partly run, and a simulated pass
        // is worse than a refusal.
        assertEquals(EvaluationException.Code.EVALUATION_RUNNER_UNAVAILABLE,
                assertThrows(EvaluationException.class,
                        () -> service.evaluate(BRAIN, INSTANCE, RELEASE)).code());
        verify(evaluations, never()).saveAndFlush(any());
    }

    @Test
    void everyCasePassingRecordsAFullScoreAndAPass() {
        EvaluationResult result = service(runner(name -> true))
                .evaluate(BRAIN, INSTANCE, RELEASE);

        assertEquals(new BigDecimal("1.0000"), result.score());
        assertTrue(result.passed());
        assertEquals(5, result.scenariosRun());
        assertEquals(5, result.scenariosPassed());
        assertTrue(result.reportSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void oneFailingCaseFailsTheSetEvenWhenTheAverageWouldClearTheBar() {
        // Four of five pass: 0.8000, under the 0.9500 minimum and also not everything.
        EvaluationResult result = service(runner(name -> !name.startsWith("unsupported")))
                .evaluate(BRAIN, INSTANCE, RELEASE);

        assertFalse(result.passed());
        assertEquals(4, result.scenariosPassed());

        // Even with no minimum at all, a case that demonstrated a way for this release to be
        // wrong is not something a promotion gate should be able to ignore.
        when(manifests.read(any(LabInstanceRelease.class)))
                .thenReturn(new DecodedInstanceManifest.V2(manifest(null)));
        assertFalse(service(runner(name -> !name.startsWith("unsupported")))
                .evaluate(BRAIN, INSTANCE, RELEASE).passed());
    }

    @Test
    void onlyTheVerdictIsStoredAndItNamesTheExactSetAndVersion() {
        service(runner(name -> true)).evaluate(BRAIN, INSTANCE, RELEASE);

        ArgumentCaptor<LabReleaseEvaluation> stored =
                ArgumentCaptor.forClass(LabReleaseEvaluation.class);
        verify(evaluations).saveAndFlush(stored.capture());
        assertEquals(RELEASE, stored.getValue().getReleaseId());
        assertEquals("income-smoke", stored.getValue().getScenarioSetId());
        assertEquals(2, stored.getValue().getScenarioSetVersion());
        assertTrue(stored.getValue().isPassed());
        assertTrue(stored.getValue().getReportSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void theReportDigestChangesWhenTheOutcomesDo() {
        String allPassed = service(runner(name -> true))
                .evaluate(BRAIN, INSTANCE, RELEASE).reportSha256();
        String oneFailed = service(runner(name -> !name.startsWith("unsupported")))
                .evaluate(BRAIN, INSTANCE, RELEASE).reportSha256();

        // A stored verdict is bound to the exact run it came from, so a different run cannot
        // quietly reuse an earlier report's identity.
        assertNotEquals(allPassed, oneFailed);
    }

    @Test
    void aV1ReleaseDeclaresNoScenarioContractSoThereIsNothingHonestToRun() {
        when(manifests.read(any(LabInstanceRelease.class)))
                .thenReturn(new DecodedInstanceManifest.V1Income(null));

        assertEquals(EvaluationException.Code.EVALUATION_RELEASE_NOT_PINNABLE,
                assertThrows(EvaluationException.class,
                        () -> service(runner(name -> true)).evaluate(BRAIN, INSTANCE, RELEASE))
                        .code());
    }

    @Test
    void aReleaseFromAnotherBrainOrInstanceIsAbsentRatherThanForbidden() {
        assertEquals(EvaluationException.Code.EVALUATION_RELEASE_NOT_FOUND,
                assertThrows(EvaluationException.class,
                        () -> service(runner(name -> true))
                                .evaluate(UUID.randomUUID(), INSTANCE, RELEASE)).code());
    }

    // ================================================================ fixtures

    private InstanceEvaluationService service(ScenarioRunner... runners) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ScenarioRunner> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call -> java.util.Arrays.stream(runners));
        return new DefaultInstanceEvaluationService(registry, releases, evaluations, manifests,
                provider);
    }

    /** A stub runner that serves every fixture and decides by scenario name. */
    private static ScenarioRunner runner(java.util.function.Predicate<String> passes) {
        return new ScenarioRunner() {
            @Override
            public boolean supports(String packageFixture) {
                return true;
            }

            @Override
            public ScenarioOutcome run(LabInstanceRelease release,
                                       InstanceReleaseManifest manifest, Scenario scenario) {
                boolean passed = passes.test(scenario.name());
                return new ScenarioOutcome(scenario.name(), passed,
                        passed ? null : "SCENARIO_ASSERTION_FAILED");
            }
        };
    }

    /**
     * A real release, not a mock.
     *
     * <p>This is called from inside a {@code when(...).thenReturn(...)} argument, which Java
     * evaluates while the outer stubbing is still open — a helper stubbing its own mock there
     * starts a nested stubbing and Mockito rejects the statement.
     */
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

    private static InstanceReleaseManifest manifest(BigDecimal minimumScore) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(new ArrayList<>()),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query",
                        new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 2, minimumScore));
    }
}
