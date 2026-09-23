package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.findings.Finding;
import com.pragmaticds.rag.lab.findings.FindingAnchor;
import com.pragmaticds.rag.lab.findings.FindingDigest;
import com.pragmaticds.rag.lab.findings.FindingJson;
import com.pragmaticds.rag.lab.findings.ManualReviewRequiredFindingTool;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.lab.parsed.RegistrationSubjectScopeService;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The seam between a findings tool and the result a run seals.
 *
 * <p>Every other findings test builds its own inputs and checks one link: the rule, the digest,
 * the assembler's ordering, the publisher's grafting. All of them passed while nothing at all
 * reached {@code AnalysisResult.findingsJson} in production, because no test drove the whole
 * chain — tool registry, then collection, then assembly, then publication — in one go. This one
 * does, with the real registry and the real shipped rule, so that a break anywhere along it
 * fails here rather than in a release.
 *
 * <p>Both scope branches are asserted, because they are different contracts. With a scope, a
 * subject key must survive all the way into the stored payload or a waiver can never carry.
 * Without one — which is what every production run supplies today — the property must be absent
 * rather than null or fabricated, because that absence is what an evaluation scenario has to be
 * written against.
 */
class ParsedInstanceFindingsSeamTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RUN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SNAPSHOT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RELEASE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PACKAGE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID REGISTRATION =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID JOB = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID SOURCE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID PAGE = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID DOCUMENT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SCHEMA_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String INSTANCE = "income";
    private static final String SCHEMA_SHA = "c".repeat(64);
    private static final String ENVELOPE_JSON =
            "{\"facts\":[],\"calculations\":[],\"missingItems\":[]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ManualReviewRequiredFindingTool rule = new ManualReviewRequiredFindingTool();

    private RetrievalService retrieval;
    private ParsedDocumentPromptRenderer renderer;
    private InstanceOutputSchemaRegistry schemas;
    private AnalysisService analysisService;
    private RegistrationLoanFactsService loanFacts;
    private RegistrationSubjectScopeService subjectScopes;

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        renderer = mock(ParsedDocumentPromptRenderer.class);
        schemas = mock(InstanceOutputSchemaRegistry.class);
        analysisService = mock(AnalysisService.class);
        loanFacts = mock(RegistrationLoanFactsService.class);
        subjectScopes = mock(RegistrationSubjectScopeService.class);
        // Scoped by default; the unscoped test re-stubs this to null. A duplicate subject can
        // only be detected when a scope exists, so the conflict test needs one too.
        when(subjectScopes.find(any(), any())).thenReturn("scope-a");

        when(schemas.requireText(anyString(), eq(SCHEMA_SHA))).thenReturn("{}");
        when(renderer.render(any(EngineResultEnvelope.class),
                any(ParsedDataCompatibilityService.CompatibilityDecision.class)))
                .thenReturn(new ParsedDocumentPromptRenderer.Rendered("FACTS", List.of()));
        when(analysisService.analyzePinned(eq(BRAIN), any(), any())).thenReturn(
                new AnalysisService.ParsedOutcome(
                        new AnalysisResult(AnalysisResult.Status.SUCCESS, "r", ENVELOPE_JSON,
                                List.of(), "anthropic", "claude-x", 1, 1, 0.0, 0, List.of(), null),
                        new ModelRouterService.Resolution("anthropic", "claude-x", true,
                                "anthropic", "claude-x", "anthropic", "claude-x", false),
                        1));
    }

    @Test
    void aStampedSubjectKeySurvivesAllTheWayIntoTheStoredResult() {
        JsonNode findings = publishedFindings(serviceWith(rule), contracts(rule), "scope-a");

        assertEquals(1, findings.size(), "the shipped rule fires once on this envelope");
        JsonNode finding = findings.get(0);
        assertEquals(ManualReviewRequiredFindingTool.NAME, finding.path("ruleId").asText());
        assertTrue(finding.path("subjectKey").asText().matches("sha256:[0-9a-f]{64}"),
                "a scoped run must carry a subject key or no waiver can ever carry forward");

        // Not merely present: the key the assembler would compute from this run's own scope and
        // this anchor's own subject part. An assertion on the shape alone would pass for a key
        // taken from the wrong finding.
        JsonNode anchor = finding.path("anchors").get(0);
        String expected = FindingDigest.of("scope-a", ManualReviewRequiredFindingTool.NAME,
                ManualReviewRequiredFindingTool.VERSION,
                FindingDigest.of(anchor.path("documentTypeCode").asText(),
                        Integer.toString(anchor.path("documentOrdinal").asInt()),
                        anchor.path("fieldName").asText(),
                        anchor.path("groupKey").isNull() ? null : anchor.path("groupKey").asText()));
        assertEquals(expected, finding.path("subjectKey").asText());
    }

    @Test
    void anUnscopedRunPublishesTheFindingWithNoSubjectKeyPropertyAtAll() {
        JsonNode findings = publishedFindings(serviceWith(rule), contracts(rule), null);

        assertEquals(1, findings.size(), "a missing scope degrades the key, never the finding");
        JsonNode finding = findings.get(0);
        // Absent, not null and not fabricated. An evaluation scenario asserting this pointer
        // would fail against every correct release, which is exactly what the shipped
        // income-smoke set once did.
        assertFalse(finding.has("subjectKey"));
        assertEquals(ManualReviewRequiredFindingTool.NAME, finding.path("ruleId").asText());
        assertTrue(finding.path("inputDigest").asText().startsWith("sha256:"),
                "ruleId and inputDigest are what a scope-free run can still be asserted on");
    }

    @Test
    void anEvaluationFixtureStampsItsDeclaredScopeWithoutTouchingTheRegistryOfScopes() {
        // A fixture came from no registration, so an evaluation scenario declares its scope
        // itself. That is the only way a gate can assert a subject key at all.
        ParsedInstanceAnalysisService.InstanceRunOutcome outcome = serviceWith(rule).analyze(
                new ParsedInstanceAnalysisService.InstanceAnalysisCommand(
                        BRAIN, INSTANCE, RUN, release(contracts(rule)), parsedInput(null),
                        snapshot(), 8, false, "eval-1", "fixture:review-required"));

        JsonNode finding = readFindings(outcome).get(0);
        JsonNode anchor = finding.path("anchors").get(0);
        assertEquals(FindingDigest.of("fixture:review-required",
                        ManualReviewRequiredFindingTool.NAME, ManualReviewRequiredFindingTool.VERSION,
                        FindingDigest.of(anchor.path("documentTypeCode").asText(),
                                Integer.toString(anchor.path("documentOrdinal").asInt()),
                                anchor.path("fieldName").asText(),
                                anchor.path("groupKey").isNull()
                                        ? null : anchor.path("groupKey").asText())),
                finding.path("subjectKey").asText());
        verifyNoInteractions(subjectScopes);
    }

    @Test
    void aRegisteredParseCannotCarryAFixtureScope() {
        // A per-run scope on a real registration would let two members of one comparison group
        // differ in something other than their declared dimension.
        assertThrows(IllegalArgumentException.class,
                () -> new ParsedInstanceAnalysisService.InstanceAnalysisCommand(
                        BRAIN, INSTANCE, RUN, release(contracts(rule)), parsedInput(),
                        snapshot(), 8, false, "eval-1", "fixture:review-required"));
    }

    @Test
    void aFindingsToolWhoseOutputCarriesNoFindingsArrayFailsTheRunRatherThanContributingNothing() {
        // Nothing validates tool output against findings-v1.output.schema.json at execution time,
        // so a producer whose shape drifted would otherwise yield zero findings in silence, and
        // findings are not best-effort.
        InstanceToolExecutor drifted = fake("drifted.rule", NF.objectNode().put("results", 1));

        InstanceToolRegistry.ToolException refused = assertThrows(
                InstanceToolRegistry.ToolException.class,
                () -> serviceWith(drifted).analyze(command(contracts(drifted))));

        assertEquals(InstanceToolRegistry.ToolException.Code.TOOL_EXECUTION_FAILED,
                refused.code());
    }

    @Test
    void twoRulesClaimingOneSubjectFailBeforeAnythingIsBilled() {
        InstanceToolExecutor first = fake("first.tool", output(duplicated()));
        InstanceToolExecutor second = fake("second.tool", output(duplicated()));

        ParsedInstanceAnalysisService.InstanceAnalysisException refused = assertThrows(
                ParsedInstanceAnalysisService.InstanceAnalysisException.class,
                () -> serviceWith(first, second)
                        .analyze(command(contracts(first, second))));

        assertEquals(ParsedInstanceAnalysisService.InstanceAnalysisException.Code
                .FINDING_DUPLICATE_SUBJECT, refused.code());
        // The whole point of moving assembly ahead of the provider call: a configuration error in
        // the release must not cost a billed model run to discover.
        verifyNoInteractions(analysisService);
    }

    // ================================================================ fixtures

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private JsonNode publishedFindings(ParsedInstanceAnalysisService service,
                                       List<InstanceReleaseManifest.ToolContract> contracts,
                                       String subjectScope) {
        // The scope reaches the analyzer from the REGISTRATION, never from the command: a per-run
        // channel would let two members of one comparison group differ in something other than
        // their declared dimension.
        when(subjectScopes.find(eq(BRAIN), any())).thenReturn(subjectScope);

        return readFindings(service.analyze(command(contracts)));
    }

    private JsonNode readFindings(ParsedInstanceAnalysisService.InstanceRunOutcome outcome) {
        try {
            return mapper.readTree(outcome.result().findingsJson()).path("findings");
        } catch (Exception unreadable) {
            throw new AssertionError("the published envelope must stay parseable", unreadable);
        }
    }

    private ParsedInstanceAnalysisService serviceWith(InstanceToolExecutor... executors) {
        return new ParsedInstanceAnalysisService(retrieval, renderer,
                new DefaultInstanceToolRegistry(List.of(executors)), schemas, analysisService,
                loanFacts, subjectScopes);
    }

    private static List<InstanceReleaseManifest.ToolContract> contracts(
            InstanceToolExecutor... executors) {
        return java.util.Arrays.stream(executors)
                .map(executor -> new InstanceReleaseManifest.ToolContract(
                        executor.name(), executor.version(), executor.inputSchemaSha256(),
                        executor.outputSchemaSha256()))
                .toList();
    }

    /** A findings producer whose output this test dictates, so a drifted shape can be staged. */
    private static InstanceToolExecutor fake(String name, JsonNode output) {
        return new InstanceToolExecutor() {
            @Override public String name() { return name; }
            @Override public String version() { return "1.0.0"; }
            @Override public String inputSchemaSha256() { return "1".repeat(64); }
            @Override public String outputSchemaSha256() { return "2".repeat(64); }
            @Override public boolean producesFindings() { return true; }
            @Override public JsonNode execute(EngineResultEnvelope envelope) { return output; }
        };
    }

    /**
     * One finding two different tools both emit. Identical rule id, version and anchor is what
     * makes the subject key collide; the tools' own identities never enter it.
     */
    private static Finding duplicated() {
        return new Finding("shared.rule", "1.0.0", Finding.Severity.WARNING, "same subject",
                null, FindingDigest.of("constant"),
                List.of(new FindingAnchor(DOCUMENT, "PAYSTUB", 0, "ytd_gross", null, null, null)),
                List.of());
    }

    private static ObjectNode output(Finding finding) {
        return FindingJson.toOutput(List.of(finding));
    }

    private ParsedInstanceAnalysisService.InstanceAnalysisCommand command(
            List<InstanceReleaseManifest.ToolContract> contracts) {
        return new ParsedInstanceAnalysisService.InstanceAnalysisCommand(
                BRAIN, INSTANCE, RUN, release(contracts), parsedInput(), snapshot(), 8, false,
                "correlation-1");
    }

    private static CorpusSnapshotService.FrozenCorpusSnapshot snapshot() {
        return new CorpusSnapshotService.FrozenCorpusSnapshot(
                SNAPSHOT, BRAIN, "e".repeat(64), List.of(), List.of());
    }

    private static ParsedDataResolver.VerifiedParsedInput parsedInput() {
        return parsedInput(REGISTRATION);
    }

    private static ParsedDataResolver.VerifiedParsedInput parsedInput(UUID registration) {
        EngineResultEnvelope envelope = envelope();
        EngineArtifactDescriptor artifact =
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8));
        return new ParsedDataResolver.VerifiedParsedInput(registration, PACKAGE, 3, JOB, 1,
                "1.0.0", "DOCENGINE-C14N-1", artifact.sha256(), artifact.byteCount(),
                "ab".repeat(32), List.of(SOURCE), envelope, envelope,
                new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 1));
    }

    private static ResolvedInstanceRelease release(
            List<InstanceReleaseManifest.ToolContract> contracts) {
        LabInstanceRelease stored = mock(LabInstanceRelease.class);
        when(stored.getId()).thenReturn(RELEASE);
        when(stored.getManifestSha256()).thenReturn("f".repeat(64));
        return new ResolvedInstanceRelease(mock(LabInstance.class), stored,
                new DecodedInstanceManifest.V2(manifest(contracts)), true);
    }

    private static InstanceReleaseManifest manifest(
            List<InstanceReleaseManifest.ToolContract> contracts) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-x",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of()),
                new InstanceReleaseManifest.BehaviorContract(
                        "system", "task", "   ", new BigDecimal("0.250")),
                contracts,
                new InstanceReleaseManifest.OutputContract("output", SCHEMA_SHA),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract(
                        "income-smoke", 2, new BigDecimal("0.950")));
    }

    /** One paystub carrying one field the extractor flagged, with a span to anchor it on. */
    private static EngineResultEnvelope envelope() {
        EngineResultEnvelope.FieldOccurrence flagged = new EngineResultEnvelope.FieldOccurrence(
                "ytd_gross", null, EngineResultEnvelope.FieldStatus.FOUND, "MONEY",
                "1,000.00", "1000.00", null,
                new EngineResultEnvelope.SchemaRef(SCHEMA_ID, "1.0.0"), "OCR", "1.4.0",
                new BigDecimal("0.62"), null, "MANUAL_REVIEW_REQUIRED", false,
                List.of(new EngineResultEnvelope.EvidenceSpan(PAGE, null, null, "VALUE", 0,
                        new EngineResultEnvelope.Box(new BigDecimal("10"), new BigDecimal("20"),
                                new BigDecimal("30"), new BigDecimal("40")))));

        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                PACKAGE,
                new EngineResultEnvelope.Generation(JOB, 1, 3, "ab".repeat(32),
                        "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new EngineResultEnvelope.SourceFile(
                        SOURCE, 0, "a1".repeat(32), 48211L, "application/pdf")),
                List.of(new EngineResultEnvelope.EnginePage(PAGE, SOURCE, 0, 0,
                        new BigDecimal("612"), new BigDecimal("792"), 0, null, "NATIVE",
                        false, false, null)),
                List.of(new EngineResultEnvelope.LogicalDocument(
                        DOCUMENT, "PAYSTUB", 0, List.of(PAGE), List.of(flagged))),
                List.of(),
                new EngineResultEnvelope.Provenance(
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        List.of(new EngineResultEnvelope.StageAttempt(
                                "EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }
}
