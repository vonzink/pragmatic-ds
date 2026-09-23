package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.ConstraintResult;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.CreateInstanceCommand;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.WizardValidationScope;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.CatalogModel;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.run.domain.LabModelCatalogEntry;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What this deployment can actually honour, checked against a complete definition.
 *
 * <p>Every test names one way a release could be authored that nothing could later run: a parser
 * this build cannot read, a collection that has moved, a model with no credentials, a tool that
 * does not exist, a schema outside the allowlist, a cost the brain cannot afford. Each of those is
 * cheap to catch here and expensive to discover at the first dispatch.
 */
class InstanceConstraintValidatorTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COLLECTION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SCHEMA_SHA = "c".repeat(64);

    private CorpusCollectionService collections;
    private InstanceModelCatalogService catalog;
    private InstanceToolRegistry tools;
    private InstanceOutputSchemaRegistry schemas;
    private InstanceScenarioSetRegistry scenarios;
    private SpendGuardService budgets;
    private InstanceConstraintValidator validator;

    @BeforeEach
    void setUp() {
        collections = mock(CorpusCollectionService.class);
        catalog = mock(InstanceModelCatalogService.class);
        tools = mock(InstanceToolRegistry.class);
        schemas = mock(InstanceOutputSchemaRegistry.class);
        scenarios = mock(InstanceScenarioSetRegistry.class);
        budgets = mock(SpendGuardService.class);

        when(collections.list(BRAIN)).thenReturn(List.of(collection("ACTIVE", 4)));
        when(catalog.find("anthropic", "claude-opus-5")).thenReturn(Optional.of(new CatalogModel(
                "anthropic", "claude-opus-5", 1_000_000, 128_000,
                LabModelCatalogEntry.TokenizerStrategy.CONSERVATIVE_RANGE,
                new BigDecimal("5.00"), new BigDecimal("0.50"), new BigDecimal("25.00"))));
        when(schemas.requireText(anyString(), anyString())).thenReturn("{}");
        when(scenarios.find(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.of(scenarioSet()));
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("10.00"));
        validator = new DefaultInstanceConstraintValidator(collections, catalog, tools, schemas,
                scenarios, budgets);
    }

    @Test
    void aCompleteAndHonourableDefinitionPasses() {
        assertTrue(validate(UnaryOperator.identity()).valid());
    }

    @Test
    void aParserThisBuildCannotReadIsRefusedAtAuthoringRatherThanAtEveryRun() {
        assertEquals(List.of("PARSER_ENVELOPE_VERSION_UNSUPPORTED"), codes(m -> withParsedData(m,
                new InstanceReleaseManifest.ParsedDataContract("9.9.9", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE))));

        // Requiring a type the release does not allow is unsatisfiable by construction: the
        // document would be filtered out before the requirement could ever be met.
        assertEquals(List.of("PARSER_REQUIRED_TYPE_NOT_ALLOWED"), codes(m -> withParsedData(m,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("W2"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE))));
    }

    @Test
    void aCorpusThatHasMovedOnIsNotSomethingAReleaseCanStillPinTo() {
        // Pinned to version 4, but the collection is now at 5.
        when(collections.list(BRAIN)).thenReturn(List.of(collection("ACTIVE", 5)));
        assertEquals(List.of("CORPUS_COLLECTION_VERSION_STALE"), codes(UnaryOperator.identity()));

        when(collections.list(BRAIN)).thenReturn(List.of(collection("DISABLED", 4)));
        assertEquals(List.of("CORPUS_COLLECTION_DISABLED"), codes(UnaryOperator.identity()));

        when(collections.list(BRAIN)).thenReturn(List.of());
        assertEquals(List.of("CORPUS_COLLECTION_NOT_FOUND"), codes(UnaryOperator.identity()));
    }

    @Test
    void groundingOnNothingIsAChoiceThisSurfaceDoesNotOffer() {
        assertEquals(List.of("CORPUS_NO_COLLECTION"), codes(m -> withCorpus(m, List.of())));
    }

    @Test
    void aModelWithNoCredentialsIsNotConfiguredNoMatterWhatTheFormSays() {
        when(catalog.find(anyString(), anyString())).thenReturn(Optional.empty());
        assertEquals(List.of("MODEL_NOT_CONFIGURED"), codes(UnaryOperator.identity()));
    }

    @Test
    void aDeploymentWithNoUsableCatalogReportsThatAsAViolationRatherThanCrashingTheWizard() {
        when(catalog.find(anyString(), anyString())).thenThrow(
                new InstanceModelCatalogService.ModelCatalogException(
                        InstanceModelCatalogService.ModelCatalogException.Code
                                .MODEL_CATALOG_UNAVAILABLE));

        assertEquals(List.of("MODEL_CATALOG_UNAVAILABLE"), codes(UnaryOperator.identity()));
    }

    @Test
    void aToolThisBuildDoesNotShipIsCaughtWhileTheAuthorIsStillLookingAtTheForm() {
        doThrow(new InstanceToolRegistry.ToolException(
                InstanceToolRegistry.ToolException.Code.TOOL_NOT_REGISTERED))
                .when(tools).verifyRegistered(any());

        assertEquals(List.of("TOOL_NOT_REGISTERED"), codes(UnaryOperator.identity()));
    }

    @Test
    void anOutputSchemaOutsideTheAllowlistIsRefusedWithTheRegistrysOwnCode() {
        doThrow(new InstanceOutputSchemaRegistry.OutputSchemaException(
                InstanceOutputSchemaRegistry.OutputSchemaException.Code
                        .OUTPUT_SCHEMA_DIGEST_MISMATCH))
                .when(schemas).requireText(anyString(), anyString());

        assertEquals(List.of("OUTPUT_SCHEMA_DIGEST_MISMATCH"), codes(UnaryOperator.identity()));
    }

    @Test
    void anInstanceThatCouldNeverAffordOneRunIsRefusedAtAuthoring() {
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("0.50"));
        assertEquals(List.of("LIMIT_MAX_COST_EXCEEDS_BUDGET"), codes(UnaryOperator.identity()));

        // A budget of zero is this deployment's "unlimited", so it is not a ceiling to compare to.
        when(budgets.resolveBudget(BRAIN)).thenReturn(BigDecimal.ZERO);
        assertTrue(validate(UnaryOperator.identity()).valid());
    }

    @Test
    void aScenarioSetNobodyShipsCannotBeTheGateThatApprovesARelease() {
        when(scenarios.find(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());

        assertEquals(List.of("EVALUATION_SCENARIO_SET_UNKNOWN"), codes(UnaryOperator.identity()));
    }

    @Test
    void validatingOneSectionIsAFilterOnTheSameAnswerNotASecondCodePath() {
        when(catalog.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(collections.list(BRAIN)).thenReturn(List.of(collection("DISABLED", 4)));

        ConstraintResult complete = validate(UnaryOperator.identity());
        assertEquals(2, complete.violations().size());

        // Each section on its own reports exactly its own half, and nothing else.
        assertEquals(List.of("CORPUS_COLLECTION_DISABLED"),
                validator.validate(WizardValidationScope.CORPUS, command(UnaryOperator.identity()))
                        .codes());
        assertEquals(List.of("MODEL_NOT_CONFIGURED"),
                validator.validate(WizardValidationScope.MODEL, command(UnaryOperator.identity()))
                        .codes());
        assertTrue(validator.validate(WizardValidationScope.OUTPUT,
                command(UnaryOperator.identity())).valid());
    }

    @Test
    void aCommandThatIsNotEvenShapedLikeOneReportsThatRatherThanSixConsequences() {
        ConstraintResult noManifest = validator.validate(WizardValidationScope.COMPLETE,
                new CreateInstanceCommand(BRAIN, "income", "Income", "", null));

        assertEquals(List.of("INSTANCE_COMMAND_INCOMPLETE"), noManifest.codes());
        assertFalse(noManifest.valid());
    }

    @Test
    void aSlugIsAnIdentifierInUrlsAndInThePointerTableSoItIsBoundedHereToo() {
        for (String slug : List.of("Income", "9lives", "with space", "x".repeat(33), "")) {
            assertTrue(validator.validate(WizardValidationScope.COMPLETE,
                            new CreateInstanceCommand(BRAIN, slug, "Income", "", manifest()))
                            .codes().contains("INSTANCE_IDENTITY_INVALID"),
                    "slug must be rejected: " + slug);
        }
    }

    // ================================================================ fixtures

    private List<String> codes(UnaryOperator<InstanceReleaseManifest> shape) {
        return validate(shape).codes();
    }

    private ConstraintResult validate(UnaryOperator<InstanceReleaseManifest> shape) {
        return validator.validate(WizardValidationScope.COMPLETE, command(shape));
    }

    private static CreateInstanceCommand command(UnaryOperator<InstanceReleaseManifest> shape) {
        return new CreateInstanceCommand(BRAIN, "income", "Income", "Analyze income.",
                shape.apply(manifest()));
    }

    /** A real record rather than a mock: records are final, and this one is cheap to build. */
    private static InstanceScenarioSetRegistry.ScenarioSet scenarioSet() {
        return new InstanceScenarioSetRegistry.ScenarioSet("income-smoke", 1, "a".repeat(64),
                List.of(new InstanceScenarioSetRegistry.Scenario("case", "fixture-a", 1,
                        List.of("/reportMarkdown"), List.of())));
    }

    private static CollectionView collection(String state, long version) {
        return new CollectionView(COLLECTION, BRAIN, "income", "Income", state, version, null,
                List.of());
    }

    private static InstanceReleaseManifest withParsedData(
            InstanceReleaseManifest base, InstanceReleaseManifest.ParsedDataContract parsedData) {
        return new InstanceReleaseManifest(2, parsedData, base.model(), base.corpus(),
                base.behavior(), base.tools(), base.output(), base.limits(), base.evaluations());
    }

    private static InstanceReleaseManifest withCorpus(
            InstanceReleaseManifest base, List<InstanceReleaseManifest.CollectionRef> refs) {
        return new InstanceReleaseManifest(2, base.parsedData(), base.model(),
                new InstanceReleaseManifest.CorpusContract(refs), base.behavior(), base.tools(),
                base.output(), base.limits(), base.evaluations());
    }

    private static InstanceReleaseManifest manifest() {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB", "W2"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(COLLECTION, 4))),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query",
                        new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", SCHEMA_SHA),
                new InstanceReleaseManifest.LimitContract(100_000, 20_000, 8_000, 4_000, 2,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.9500")));
    }
}
