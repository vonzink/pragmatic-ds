package com.pragmaticds.rag.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.AnalysisRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the analysis_runs JSONB columns actually round-trip through Postgres.
 *
 * <p>This matters more than a normal mapping test: {@link com.pragmaticds.rag.service.analyze.AnalysisRunRecorder}
 * swallows every persistence exception so a manifest can never fail an analyze
 * run. If these mappings were broken, production would silently write ZERO rows
 * and the only signal would be a log line — so the round-trip has to be proven
 * here rather than against a mocked repository.
 *
 * <p>calc_audit is the awkward one: a {@code List<Map<String,Object>>} whose
 * values include {@link BigDecimal} and a nested Jackson node.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AnalysisRunRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    AnalysisRunRepository repo;

    @Autowired
    EntityManager em;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalysisRun manifestRow() {
        AnalysisRun run = new AnalysisRun();
        run.setId(UUID.randomUUID());
        run.setBrainId(TestBrains.DEFAULT_ID);
        run.setAnalyzerSlug("income-v2");
        run.setEnvelopeVersion("v2");
        run.setStatus("SUCCESS");
        run.setProvider("anthropic");
        run.setModel("claude-haiku-4-5");
        run.setPromptSha256("a".repeat(64));
        run.setAttempts(2);
        run.setInputTokens(1200);
        run.setOutputTokens(3400);
        run.setCostUsd(0.0123);
        run.setDocCount(2);
        run.setPageCount(9);
        return run;
    }

    /** The redacted (flag-off) shape: no name, no inputs, no value. */
    private static Map<String, Object> shapeOnlyAuditRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "c1");
        row.put("method", "income.monthly_from_annual.v1");
        row.put("status", "ERROR");
        row.put("error", "missing or non-numeric input: annual");
        return row;
    }

    /** The unredacted (flag-on) shape, carrying the types that are easy to get wrong. */
    private static Map<String, Object> fullAuditRow() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "c2");
        row.put("name", "Borrower 1 W-2 base wages");
        row.put("method", "income.monthly_from_annual.v1");
        row.put("inputs", MAPPER.readTree("{\"annual\":96000}"));   // nested JsonNode
        row.put("status", "COMPUTED");
        row.put("value", new BigDecimal("8000.00"));                // BigDecimal
        row.put("error", null);                                     // null inside the map
        return row;
    }

    @Test
    void jsonbColumnsRoundTrip() throws Exception {
        AnalysisRun run = manifestRow();
        run.setDocs(List.of(new LinkedHashMap<>(Map.of("id", "d1", "sha256", "b".repeat(64)))));
        run.setSkipped(List.of(new LinkedHashMap<>(Map.of("id", "d2", "category", "UNREADABLE"))));
        run.setFiltered(List.of(new LinkedHashMap<>(Map.of("id", "d1", "pagesKept", 9, "pagesTotal", 62))));
        run.setRetrievedChunkIds(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        run.setCalcAudit(List.of(shapeOnlyAuditRow(), fullAuditRow()));

        UUID id = repo.saveAndFlush(run).getId();
        em.clear();   // force a real read back out of Postgres, not the first-level cache

        AnalysisRun found = repo.findById(id).orElseThrow();

        assertEquals("income-v2", found.getAnalyzerSlug());
        assertEquals(2, found.getAttempts());
        assertEquals(3400, found.getOutputTokens());
        assertEquals(1, found.getDocs().size());
        assertEquals("d1", found.getDocs().get(0).get("id"));
        assertEquals(1, found.getSkipped().size());
        assertEquals(1, found.getFiltered().size());
        assertEquals(2, found.getRetrievedChunkIds().size());

        assertEquals(2, found.getCalcAudit().size());
        Map<String, Object> redacted = found.getCalcAudit().get(0);
        assertEquals("income.monthly_from_annual.v1", redacted.get("method"));
        assertEquals("ERROR", redacted.get("status"));

        Map<String, Object> full = found.getCalcAudit().get(1);
        assertEquals("COMPUTED", full.get("status"));
        // The BigDecimal survives as a number, not a string — the suite reads these.
        assertEquals(0, new BigDecimal(full.get("value").toString()).compareTo(new BigDecimal("8000.00")));
        // The nested JsonNode survives as a nested object with its numeric value intact.
        assertEquals(96000, Integer.parseInt(
                ((Map<?, ?>) full.get("inputs")).get("annual").toString()));
    }

    @Test
    void nullJsonbColumnsAreAllowed() {
        // The default path leaves findings null; an error run before retrieval leaves
        // retrievedChunkIds/calcAudit empty. None of that may fail the insert.
        AnalysisRun run = manifestRow();
        run.setStatus("ERROR");
        run.setErrorReason("model provider error: upstream timeout");

        UUID id = repo.saveAndFlush(run).getId();
        em.clear();

        AnalysisRun found = repo.findById(id).orElseThrow();
        assertNull(found.getFindings(), "findings must stay null when persist-findings is off");
        assertNull(found.getCalcAudit());
        assertEquals("model provider error: upstream timeout", found.getErrorReason());
        assertNotNull(found.getCreatedAt(), "@PrePersist must default createdAt");
    }

    @Test
    void findingsRoundTripWhenPersisted() throws Exception {
        // Only reachable with persist-findings=true, but the mapping must work when it is.
        AnalysisRun run = manifestRow();
        run.setFindings(MAPPER.readValue(
                "{\"envelopeVersion\":\"2.0\",\"confidence\":0.9,\"facts\":[{\"id\":\"f1\"}]}",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));

        UUID id = repo.saveAndFlush(run).getId();
        em.clear();

        Map<String, Object> findings = repo.findById(id).orElseThrow().getFindings();
        assertEquals("2.0", findings.get("envelopeVersion"));
        assertTrue(findings.containsKey("facts"));
    }
}
