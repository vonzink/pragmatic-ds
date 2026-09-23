package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.AnalysisRun;
import com.pragmaticds.rag.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalysisRunRecorderTest {

    private final AnalysisRunRepository repo = mock(AnalysisRunRepository.class);
    private final ObjectMapper om = new ObjectMapper();

    /** One populated calc_audit row, matching CalculationExecutor.auditRow's shape. */
    private Map<String, Object> calcAuditRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "calc1");
        row.put("name", "Borrower 1 W-2 base wages");   // model-authored free text — can carry a name
        row.put("method", "income.monthly_from_annual.v1");
        row.put("inputs", Map.of("annual", new BigDecimal("96000")));
        row.put("status", "COMPUTED");
        row.put("value", new BigDecimal("8000.00"));
        row.put("error", null);
        return row;
    }

    private RunManifest manifest() {
        RunManifest m = RunManifest.start(UUID.randomUUID(), "income-v2", "v2",
                List.of(new DocInput("d1", "Smith_John_W2_2025.pdf", "application/pdf",
                        "pdfbytes".getBytes(), 0L)));
        m.setPromptSha256(RunManifest.sha256Hex("the prompt"));
        m.setRetrievedChunkIds(List.of("11111111-1111-1111-1111-111111111111"));
        m.setCalcAudit(List.of(calcAuditRow()));
        return m;
    }

    private AnalysisResult success() {
        return new AnalysisResult(AnalysisResult.Status.SUCCESS, "report",
                "{\"envelopeVersion\":\"2.0\"}", List.of(), "anthropic", "claude-haiku-4-5",
                100, 200, 0.01, 3,
                List.of(new SkippedDoc("d2", "Doe_Jane_1099.pdf", SkipCategory.UNSUPPORTED_TYPE,
                        "unsupported document type: text/plain")),
                null, List.of());
    }

    @Test
    void persistsManifestRow() {
        new AnalysisRunRecorder(repo, om, false).save(manifest(), success(), 2);

        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(repo).save(captor.capture());
        AnalysisRun row = captor.getValue();
        assertEquals("income-v2", row.getAnalyzerSlug());
        assertEquals("v2", row.getEnvelopeVersion());
        assertEquals("SUCCESS", row.getStatus());
        assertEquals("anthropic", row.getProvider());
        assertEquals(2, row.getAttempts());
        assertEquals(100, row.getInputTokens());
        assertEquals(64, row.getPromptSha256().length());
        assertEquals(1, row.getDocs().size());
        assertEquals(64, ((String) row.getDocs().get(0).get("sha256")).length());
        assertNull(row.getFindings());                      // persist-findings OFF by default
    }

    @Test
    void persistsFindingsOnlyWhenFlagOn() {
        new AnalysisRunRecorder(repo, om, true).save(manifest(), success(), 1);
        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(repo).save(captor.capture());
        assertEquals("2.0", captor.getValue().getFindings().get("envelopeVersion"));
    }

    @Test
    void repositoryFailureNeverPropagates() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() ->
                new AnalysisRunRecorder(repo, om, false).save(manifest(), success(), 1));
    }

    // ---------------------------------------------------------------- redaction (persist-findings=false)

    @Test
    void calcAuditIsTrimmedToShapeOnlyWhenFlagOff() {
        new AnalysisRunRecorder(repo, om, false).save(manifest(), success(), 1);

        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(repo).save(captor.capture());
        Map<String, Object> row = captor.getValue().getCalcAudit().get(0);

        assertEquals("calc1", row.get("id"));
        assertEquals("income.monthly_from_annual.v1", row.get("method"));
        assertEquals("COMPUTED", row.get("status"));
        assertTrue(row.containsKey("error"), "error must survive — it never carries values, only field names");
        assertFalse(row.containsKey("name"), "name (often model-authored, can carry a borrower name) must be dropped");
        assertFalse(row.containsKey("inputs"), "inputs (borrower income figures) must be dropped");
        assertFalse(row.containsKey("value"), "value (borrower's computed income) must be dropped");
    }

    @Test
    void calcAuditIsFullWhenFlagOn() {
        new AnalysisRunRecorder(repo, om, true).save(manifest(), success(), 1);

        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(repo).save(captor.capture());
        Map<String, Object> row = captor.getValue().getCalcAudit().get(0);

        assertEquals("Borrower 1 W-2 base wages", row.get("name"));
        assertTrue(row.containsKey("inputs"));
        assertTrue(row.containsKey("value"));
    }

    @Test
    void docsAndSkippedDropFileNameWhenFlagOff() {
        new AnalysisRunRecorder(repo, om, false).save(manifest(), success(), 1);

        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(repo).save(captor.capture());
        AnalysisRun row = captor.getValue();

        assertFalse(row.getDocs().get(0).containsKey("fileName"),
                "docs[].fileName can embed a borrower surname and must be dropped");
        assertTrue(row.getDocs().get(0).containsKey("id"));
        assertTrue(row.getDocs().get(0).containsKey("sha256"));
        assertFalse(row.getSkipped().get(0).containsKey("fileName"),
                "skipped[].fileName can embed a borrower surname and must be dropped");
        assertTrue(row.getSkipped().get(0).containsKey("id"));
        assertTrue(row.getSkipped().get(0).containsKey("category"));
    }

    @Test
    void docsAndSkippedKeepFileNameWhenFlagOn() {
        new AnalysisRunRecorder(repo, om, true).save(manifest(), success(), 1);

        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(repo).save(captor.capture());
        AnalysisRun row = captor.getValue();

        assertEquals("Smith_John_W2_2025.pdf", row.getDocs().get(0).get("fileName"));
        assertEquals("Doe_Jane_1099.pdf", row.getSkipped().get(0).get("fileName"));
    }

    // ---------------------------------------------------------------- required Lab path (Task 5)

    @Test
    void saveRequiredIsMetadataOnlyEvenWithPersistFindingsOn() {
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.existsById(any(UUID.class))).thenReturn(true);

        new AnalysisRunRecorder(repo, om, true).saveRequired(manifest(), success(), 1);

        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(repo).saveAndFlush(captor.capture());
        AnalysisRun row = captor.getValue();

        assertNull(row.getFindings(),
                "the global persist-findings flag is not consent to store Lab findings");
        assertFalse(row.getDocs().get(0).containsKey("fileName"));
        assertFalse(row.getSkipped().get(0).containsKey("fileName"));
        assertEquals(java.util.Set.of("id", "method", "status", "error"),
                row.getCalcAudit().get(0).keySet());
        verify(repo, never()).save(any());
    }

    @Test
    void saveRequiredPropagatesAPayloadFreeCodeInsteadOfSwallowing() {
        when(repo.saveAndFlush(any()))
                .thenThrow(new RuntimeException("duplicate key value: findings=CANARY-VALUE"));

        AnalysisRunRecorder.RecorderException failure =
                assertThrows(AnalysisRunRecorder.RecorderException.class,
                        () -> new AnalysisRunRecorder(repo, om, false)
                                .saveRequired(manifest(), success(), 1));

        assertEquals(AnalysisRunRecorder.RecorderException.Code.ANALYSIS_RUN_PERSIST_FAILED,
                failure.code());
        assertFalse(failure.getMessage().contains("CANARY-VALUE"));
        assertNull(failure.getCause());
    }

    @Test
    void saveRequiredRefusesToReportSuccessWhenTheRowIsNotReadable() {
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.existsById(any(UUID.class))).thenReturn(false);

        assertEquals(AnalysisRunRecorder.RecorderException.Code.ANALYSIS_RUN_ABSENT_AFTER_WRITE,
                assertThrows(AnalysisRunRecorder.RecorderException.class,
                        () -> new AnalysisRunRecorder(repo, om, false)
                                .saveRequired(manifest(), success(), 1)).code());
    }
}
