package com.pragmaticds.rag.lab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry;
import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.lab.model.InstanceUsageService;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One run, one terminal state, and codes instead of messages.
 *
 * <p>The disclosure rule is the point of most of these tests. A provider exception routinely quotes
 * the request URI and the provider's own response body, and a tool failure can carry borrower
 * values, so a failure's message and cause must reach neither the stored failure code, the audit
 * row, nor the caller. Each layer publishes a value-free vocabulary; this service only picks one.
 */
class InstanceExecutionServiceTest {
    private static final UUID RUN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ANALYSIS_RUN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SNAPSHOT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RELEASE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PACKAGE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID REGISTRATION = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID JOB = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID SOURCE = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID PAGE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String INSTANCE = "income";

    /** A message shaped like the ones that must never escape: a URI and a borrower value. */
    private static final String LEAKY_MESSAGE =
            "POST https://engine.internal/v1/parse failed: {\"borrower\":\"JANE Q BORROWER\"}";

    private ParsedInstanceAnalysisService analysis;
    private LabRunTransactionService transactions;
    private LabAuditService audit;
    private InstanceUsageService usage;
    private InstanceExecutionService service;

    @BeforeEach
    void setUp() {
        analysis = mock(ParsedInstanceAnalysisService.class);
        transactions = mock(LabRunTransactionService.class);
        audit = mock(LabAuditService.class);
        usage = mock(InstanceUsageService.class);
        service = new InstanceExecutionService(analysis, transactions, audit,
                new LabManifestWriter(), usage, new ObjectMapper());
    }

    @Test
    void aSuccessfulRunSealsOutputAndProvenanceInTheSameCall() {
        when(analysis.analyze(any())).thenReturn(new ParsedInstanceAnalysisService.InstanceRunOutcome(
                result(), provenance()));

        InstanceExecutionService.ExecutionOutcome outcome = service.execute(RUN, command());

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals(RUN, outcome.runId());
        assertNull(outcome.failureCode());

        // Both payloads move with the status change. A run with an output but no provenance would
        // be an answer nobody could audit, so the seal is one call or none.
        ArgumentCaptor<byte[]> output = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> sealed = ArgumentCaptor.forClass(byte[].class);
        verify(transactions).completeRun(eq(RUN), eq(BRAIN), eq(ANALYSIS_RUN),
                output.capture(), sealed.capture());
        assertTrue(output.getValue().length > 0, "the analyzer result must be sealed");
        assertTrue(sealed.getValue().length > 0, "the provenance must be sealed beside it");
        verify(transactions, never()).failRun(any(), any(), anyString());
    }

    @Test
    void everyLayerKeepsItsOwnStableVocabulary() {
        assertFailsWith(new ParsedInstanceAnalysisService.InstanceAnalysisException(
                        ParsedInstanceAnalysisService.InstanceAnalysisException.Code
                                .PARSE_INCOMPATIBLE),
                "PARSE_INCOMPATIBLE");
        assertFailsWith(new ParsedDataResolver.ParsedDataException(
                        ParsedDataResolver.ParsedDataException.Code.PARSE_DESCRIPTOR_MISMATCH),
                "PARSE_DESCRIPTOR_MISMATCH");
        assertFailsWith(new InstanceToolRegistry.ToolException(
                        InstanceToolRegistry.ToolException.Code.TOOL_NOT_REGISTERED),
                "TOOL_NOT_REGISTERED");
        assertFailsWith(new InstanceOutputSchemaRegistry.OutputSchemaException(
                        InstanceOutputSchemaRegistry.OutputSchemaException.Code
                                .OUTPUT_SCHEMA_DIGEST_MISMATCH),
                "OUTPUT_SCHEMA_DIGEST_MISMATCH");
        assertFailsWith(new CorpusSnapshotService.SnapshotException(
                        CorpusSnapshotService.SnapshotException.Code.CORPUS_SNAPSHOT_NOT_FOUND),
                "CORPUS_SNAPSHOT_NOT_FOUND");
        assertFailsWith(new ModelRouterService.SanitizedProviderException(
                        ModelRouterService.SanitizedProviderException.Code.PROVIDER_PIN_UNAVAILABLE,
                        "anthropic", "SocketTimeoutException", "correlation-1"),
                "PROVIDER_PIN_UNAVAILABLE");
    }

    @Test
    void anUnrecognisedFailureCollapsesToOneGenericCodeAndQuotesNothing() {
        InstanceExecutionService.ExecutionOutcome outcome =
                failWith(new IllegalStateException(LEAKY_MESSAGE));

        // Not the class name, not the message: a bare class name would still hint at internals.
        assertEquals("INSTANCE_RUN_FAILED", outcome.failureCode());

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(transactions).failRun(eq(RUN), eq(BRAIN), stored.capture());
        assertEquals("INSTANCE_RUN_FAILED", stored.getValue());
        assertFalse(stored.getValue().contains("engine.internal"));
        assertFalse(stored.getValue().contains("JANE Q BORROWER"));
    }

    @Test
    void aFailedRunSealsNoOutputAndAuditsOnlyItsCode() {
        InstanceExecutionService.ExecutionOutcome outcome = failWith(
                new InstanceToolRegistry.ToolException(
                        InstanceToolRegistry.ToolException.Code.TOOL_EXECUTION_FAILED));

        assertEquals("FAILED", outcome.status());
        // A run that failed must not carry a half-sealed answer.
        verify(transactions, never()).completeRun(any(), any(), any(), any(), any());
        verify(transactions, never()).completeRun(any(), any(), any(), any());

        // The code is the whole disclosure: the audit row carries a flag and nothing else, so
        // there is no field a message or a cause could be smuggled into.
        ArgumentCaptor<Map<String, Object>> counts = captureAuditCounts(LabAuditEvent.Status.FAILED);
        assertEquals(Set.of("failed"), counts.getValue().keySet());
    }

    @Test
    void anInBandErrorResultFailsTheRunWithItsOwnCodeAndSealsNothing() {
        // The shared analyzer reports a provider failure as an ERROR result, not an exception —
        // its legacy surface renders that row. Sealing it would consume the reservation for a
        // call that produced nothing; the run must be FAILED with the result's own code.
        when(analysis.analyze(any())).thenReturn(new ParsedInstanceAnalysisService.InstanceRunOutcome(
                errorResult("MODEL_PROVIDER_FAILED", 0, 0), provenance()));

        InstanceExecutionService.ExecutionOutcome outcome = service.execute(RUN, command());

        assertEquals("FAILED", outcome.status());
        assertEquals("MODEL_PROVIDER_FAILED", outcome.failureCode());
        verify(transactions).failRun(RUN, BRAIN, "MODEL_PROVIDER_FAILED");
        verify(transactions, never()).completeRun(any(), any(), any(), any(), any());
        // Zero tokens means the provider never said — the usage row must read UNAVAILABLE,
        // never a fabricated zero.
        verify(usage).report(RUN, BRAIN, new ModelEstimate.ProviderUsage(null, null, null));
    }

    @Test
    void anInBandErrorAfterBilledCallsStillReportsTheRealSpend() {
        // A validation failure at two attempts is the most expensive run in the system — two
        // full calls billed before the envelope was rejected. Terminal FAILED, real usage.
        when(analysis.analyze(any())).thenReturn(new ParsedInstanceAnalysisService.InstanceRunOutcome(
                errorResult("ENVELOPE_VALIDATION_FAILED_AFTER_RETRY", 2400, 610), provenance()));

        InstanceExecutionService.ExecutionOutcome outcome = service.execute(RUN, command());

        assertEquals("FAILED", outcome.status());
        assertEquals("ENVELOPE_VALIDATION_FAILED_AFTER_RETRY", outcome.failureCode());
        verify(usage).report(RUN, BRAIN, new ModelEstimate.ProviderUsage(2400L, null, 610L));
    }

    @Test
    void anInBandReasonThatIsNotVocabularyCollapsesAndQuotesNothing() {
        // The reason field is free text by type; only the lab-safe path guarantees a code. A
        // message-shaped reason must collapse to the generic code, not be stored as a "code".
        when(analysis.analyze(any())).thenReturn(new ParsedInstanceAnalysisService.InstanceRunOutcome(
                errorResult(LEAKY_MESSAGE, 0, 0), provenance()));

        InstanceExecutionService.ExecutionOutcome outcome = service.execute(RUN, command());

        assertEquals("INSTANCE_RUN_FAILED", outcome.failureCode());
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(transactions).failRun(eq(RUN), eq(BRAIN), stored.capture());
        assertFalse(stored.getValue().contains("engine.internal"));
        assertFalse(stored.getValue().contains("JANE Q BORROWER"));
    }

    @Test
    void aSuccessfulRunAuditsCountsAndNeverContent() {
        when(analysis.analyze(any())).thenReturn(new ParsedInstanceAnalysisService.InstanceRunOutcome(
                result(), provenance()));

        service.execute(RUN, command());

        ArgumentCaptor<Map<String, Object>> counts =
                captureAuditCounts(LabAuditEvent.Status.SUCCEEDED);
        assertEquals(1, counts.getValue().get("retrievedChunks"));
        assertEquals(0, counts.getValue().get("tools"));
        assertEquals(false, counts.getValue().get("fallbackUsed"));
        // The evidence text is in the sealed provenance, never in an audit row.
        assertFalse(counts.getValue().toString().contains("Qualifying income"));
    }

    // ================================================================ helpers

    private void assertFailsWith(RuntimeException failure, String expectedCode) {
        setUp();
        InstanceExecutionService.ExecutionOutcome outcome = failWith(failure);
        assertEquals(expectedCode, outcome.failureCode());
        verify(transactions).failRun(RUN, BRAIN, expectedCode);
    }

    private InstanceExecutionService.ExecutionOutcome failWith(RuntimeException failure) {
        when(analysis.analyze(any())).thenThrow(failure);
        InstanceExecutionService.ExecutionOutcome outcome = service.execute(RUN, command());
        assertEquals("FAILED", outcome.status());
        return outcome;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> captureAuditCounts(LabAuditEvent.Status status) {
        ArgumentCaptor<Map<String, Object>> counts = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(BRAIN), eq(LabAuditService.RUN_TERMINAL), eq(status),
                eq(LabAuditEvent.SubjectType.RUN), eq(RUN), any(), counts.capture());
        return counts;
    }

    // ================================================================ fixtures

    private static AnalysisResult result() {
        return new AnalysisResult(AnalysisResult.Status.SUCCESS, "# Report", "{}", List.of(),
                "anthropic", "claude-x", 120, 45, 0.0, 0, List.of(), null);
    }

    /** The shape the shared analyzer returns for an in-band failure: ERROR, reason, tokens. */
    private static AnalysisResult errorResult(String reason, int inputTokens, int outputTokens) {
        return new AnalysisResult(AnalysisResult.Status.ERROR,
                "AI analysis could not be completed.", "{}", List.of(),
                "anthropic", "claude-x", inputTokens, outputTokens, 0.0, 0, List.of(), reason);
    }

    private static InstanceRunProvenance provenance() {
        return new InstanceRunProvenance(
                ANALYSIS_RUN, BRAIN, INSTANCE, RELEASE, "f".repeat(64),
                new InstanceRunProvenance.ParsedInputDescriptor(REGISTRATION, PACKAGE, 3, JOB, 1,
                        "1.0.0", "d".repeat(64), 4096L, "ab".repeat(32), List.of(SOURCE)),
                SNAPSHOT, "e".repeat(64),
                List.of(new InstanceRunProvenance.RetrievedEvidence(
                        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                        "a1".repeat(32), "Selling Guide", "Selling Guide", null,
                        "Qualifying income is averaged over 24 months.")),
                List.of(),
                new InstanceRunProvenance.ModelResolution("anthropic", "claude-x", "anthropic",
                        "claude-x", "anthropic", "claude-x", false),
                "c".repeat(64),
                42L);
    }

    private static ParsedInstanceAnalysisService.InstanceAnalysisCommand command() {
        return new ParsedInstanceAnalysisService.InstanceAnalysisCommand(
                BRAIN, INSTANCE, ANALYSIS_RUN, release(), parsedInput(), snapshot(), 8, false,
                "correlation-1");
    }

    private static CorpusSnapshotService.FrozenCorpusSnapshot snapshot() {
        return new CorpusSnapshotService.FrozenCorpusSnapshot(
                SNAPSHOT, BRAIN, "e".repeat(64), List.of(), List.of());
    }

    private static ParsedDataResolver.VerifiedParsedInput parsedInput() {
        EngineResultEnvelope envelope = envelope();
        EngineArtifactDescriptor artifact =
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8));
        return new ParsedDataResolver.VerifiedParsedInput(REGISTRATION, PACKAGE, 3, JOB, 1,
                "1.0.0", "DOCENGINE-C14N-1", artifact.sha256(), artifact.byteCount(),
                "ab".repeat(32), List.of(SOURCE), envelope, envelope,
                new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 1));
    }

    private static ResolvedInstanceRelease release() {
        LabInstanceRelease stored = mock(LabInstanceRelease.class);
        when(stored.getId()).thenReturn(RELEASE);
        when(stored.getManifestSha256()).thenReturn("f".repeat(64));
        InstanceReleaseManifest manifest = new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-x",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of()),
                new InstanceReleaseManifest.BehaviorContract(
                        "system", "task", "query", new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract(
                        "golden", 1, new BigDecimal("0.950")));
        return new ResolvedInstanceRelease(mock(LabInstance.class), stored,
                new DecodedInstanceManifest.V2(manifest), true);
    }

    private static EngineResultEnvelope envelope() {
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
                        new UUID(0xdddd, 0), "PAYSTUB", 0, List.of(PAGE), List.of())),
                List.of(),
                new EngineResultEnvelope.Provenance(
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        List.of(new EngineResultEnvelope.StageAttempt(
                                "EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }
}
