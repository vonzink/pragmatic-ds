package com.pragmaticds.rag.lab.ops;

import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.CatalogModel;
import com.pragmaticds.rag.lab.run.domain.LabModelCatalogEntry;
import com.pragmaticds.rag.lab.run.RunGroupDispatcher;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import org.junit.jupiter.api.Test;
import com.pragmaticds.rag.lab.service.LabAuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Execution readiness is a conjunction, and a catalog failure becomes a boolean.
 *
 * <p>The second property is the privacy-relevant one: the catalog's own exception may carry a
 * configuration code, and a provider's failure text could carry anything. The report has no
 * string field for either to hide in — this test drives the exception path and gets a clean
 * {@code false}.
 */
class InstanceReadinessServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RunGroupDispatcher> dispatcher = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LabPayloadCipher> cipher = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DocumentEngineClient> engine = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LabAuditService> auditor = mock(ObjectProvider.class);
    private final InstanceModelCatalogService catalog = mock(InstanceModelCatalogService.class);

    @Test
    void executionReadinessIsTheConjunctionOfItsPrerequisites() {
        schemaAt("41");
        LabPayloadCipher availableCipher = mock(LabPayloadCipher.class);
        when(availableCipher.isAvailable()).thenReturn(true);
        when(cipher.getIfAvailable()).thenReturn(availableCipher);
        when(dispatcher.getIfAvailable()).thenReturn(mock(RunGroupDispatcher.class));
        when(catalog.available()).thenReturn(List.of(syntheticModel()));

        InstanceReadinessService ready = service(true, 30, "http://localhost:9090");
        assertTrue(ready.report().executionReady());

        // Flip each prerequisite alone and the conjunction falls.
        assertFalse(service(true, 0, "http://localhost:9090").report().executionReady());
        assertFalse(service(true, 30, "").report().executionReady());
        assertFalse(service(false, 30, "http://localhost:9090").report().executionReady());

        when(dispatcher.getIfAvailable()).thenReturn(null);
        assertFalse(service(true, 30, "http://localhost:9090").report().executionReady());
    }

    @Test
    void aCatalogFailureBecomesABooleanNeverAMessage() {
        schemaAt("41");
        when(catalog.available()).thenThrow(new IllegalStateException(
                "MODEL_CATALOG_UNAVAILABLE at http://config-detail.internal"));

        InstanceReadinessService.ReadinessReport report =
                service(false, 0, "http://localhost:9090").report();

        assertFalse(report.catalogReady());
        assertEquals(0, report.offerableModels());
    }

    @Test
    void theSchemaVersionGatesEveryCapability() {
        schemaAt("39");
        when(catalog.available()).thenReturn(List.of(syntheticModel()));

        InstanceReadinessService.ReadinessReport report =
                service(false, 0, "http://localhost:9090").report();

        assertEquals("39", report.schemaVersion());
        assertFalse(report.schemaCurrent());
        assertFalse(report.readReady());
        assertFalse(report.promotionReady());
    }

    @Test
    void anUnreadableSchemaHistoryReadsAsVersionZeroNotAnError() {
        when(jdbc.queryForObject(contains("flyway_schema_history"), eq(String.class)))
                .thenThrow(new IllegalStateException("relation does not exist"));
        when(catalog.available()).thenReturn(List.of());

        InstanceReadinessService.ReadinessReport report =
                service(false, 0, "").report();

        assertEquals("0", report.schemaVersion());
        assertFalse(report.schemaCurrent());
    }

    // ================================================================ fixtures

    @Test
    void anEngineWithNoCredentialIsNotReportedAsConfigured() {
        // A URL says where the engine is; a credential says this deployment may talk to it.
        // Reporting the first alone told an operator the engine was ready when every run would
        // have come back 401.
        schemaAt("41");
        when(catalog.available()).thenReturn(List.of(syntheticModel()));

        assertFalse(service(false, 30, "http://engine.internal:9090", false)
                .report().engineConfigured());
        assertTrue(service(false, 30, "http://engine.internal:9090", true)
                .report().engineConfigured());
    }

    private void schemaAt(String version) {
        when(jdbc.queryForObject(contains("flyway_schema_history"), eq(String.class)))
                .thenReturn(version);
    }

    @Test
    void auditWritableIsTrueUntilAWriteHasActuallyFailed() {
        // Deliberately "nothing has failed", not "writes are proven to work": a deployment that
        // has attempted none reports writable because there is nothing else it could honestly
        // say. auditWriteFailures beside it is the number that carries the weight.
        LabAuditService quiet = mock(LabAuditService.class);
        when(quiet.writeFailures()).thenReturn(0L);
        when(auditor.getIfAvailable()).thenReturn(quiet);

        var report = service(false, 30, "http://localhost:9090").report();

        assertTrue(report.auditWritable());
        assertEquals(0, report.auditWriteFailures());
    }

    @Test
    void aSwallowedAuditFailureSurfacesInReadiness() {
        // The whole point. record() swallows write failures so they cannot fail a user's
        // operation, which on 2026-09-01 meant every write failed for weeks behind a log line
        // nobody tailed. Readiness is where a swallowed failure has to become visible.
        LabAuditService failing = mock(LabAuditService.class);
        when(failing.writeFailures()).thenReturn(17L);
        when(auditor.getIfAvailable()).thenReturn(failing);

        var report = service(false, 30, "http://localhost:9090").report();

        assertFalse(report.auditWritable());
        assertEquals(17, report.auditWriteFailures());
    }

    private static CatalogModel syntheticModel() {
        // A real record, never a mock: the mock maker in this build cannot stub record
        // accessors, and readiness only needs the list to have a size anyway.
        return new CatalogModel("synthetic", "synthetic-analyzer", 100_000L, 8_000L,
                LabModelCatalogEntry.TokenizerStrategy.CONSERVATIVE_RANGE,
                new BigDecimal("1.00"), null, new BigDecimal("5.00"));
    }

    /** Credentialed by default: these cases are about the other prerequisites. */
    private InstanceReadinessService service(boolean executionEnabled, int retentionDays,
                                             String engineBaseUrl) {
        return service(executionEnabled, retentionDays, engineBaseUrl, true);
    }

    private InstanceReadinessService service(boolean executionEnabled, int retentionDays,
                                             String engineBaseUrl, boolean engineDevAuth) {
        return new InstanceReadinessService(jdbc, dispatcher, cipher, auditor, engine, catalog,
                executionEnabled, false, true, retentionDays, engineBaseUrl, "", "",
                engineDevAuth);
    }
}
