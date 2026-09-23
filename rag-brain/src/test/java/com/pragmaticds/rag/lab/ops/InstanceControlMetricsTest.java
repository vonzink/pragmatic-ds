package com.pragmaticds.rag.lab.ops;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.model.InstanceModelProperties;
import com.pragmaticds.rag.lab.model.InstanceModelProperties.ModelEntry;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.LabSpendReservation;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a metric tag is allowed to say, proven against hostile inputs.
 *
 * <p>The wall under test: tag values are bounded catalogs — enum names, configured provider
 * names, {@code other} — and never data. The hostile-input tests push a brain UUID, a tenant
 * string, and a request id through the provider field and assert none of them survives into a
 * tag; the sweep test then walks every registered meter and rejects anything UUID-shaped or
 * outside the expected vocabularies.
 */
class InstanceControlMetricsTest {

    private static final Pattern UUID_SHAPED = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LabRunRepository runs = mock(LabRunRepository.class);
    private final InstanceControlMetrics metrics = new InstanceControlMetrics(
            registry,
            new InstanceModelProperties(List.of(syntheticModel())),
            runs);

    @Test
    void everyTagValueComesFromABoundedCatalog() {
        metrics.runCreated(LabRunGroup.Mode.INDEPENDENT, "synthetic");
        metrics.runTerminal(terminalRun("synthetic"), LabRunGroup.Mode.COMPARISON);
        metrics.dispatchClaim("CLAIMED");
        metrics.reservation(LabSpendReservation.Status.RESERVED);
        metrics.usageQuality(UsageQuality.REPORTED, "synthetic");
        metrics.promotion("PROMOTE");
        metrics.connectorRefusal("IDEMPOTENCY_KEY_REUSED");

        Set<String> vocabulary = Set.of(
                "INDEPENDENT", "COMPARISON", "UNKNOWN",
                "QUEUED", "PROCESSING", "SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED",
                "synthetic", "other",
                "CLAIMED", "SATURATED", "EMPTY",
                "RESERVED", "CONSUMED", "RELEASED",
                "PENDING", "REPORTED", "INFERRED", "UNAVAILABLE",
                "PROMOTE", "ROLLBACK", "IDEMPOTENCY_KEY_REUSED");
        for (Meter meter : registry.getMeters()) {
            assertTrue(meter.getId().getName().startsWith("rag.instances."),
                    "unexpected meter " + meter.getId().getName());
            for (Tag tag : meter.getId().getTags()) {
                assertTrue(vocabulary.contains(tag.getValue()),
                        "tag outside the bounded catalogs: " + tag.getKey()
                                + "=" + tag.getValue());
                assertFalse(UUID_SHAPED.matcher(tag.getValue()).find(),
                        "identifier leaked into a tag: " + tag.getValue());
            }
        }
    }

    @Test
    void aProviderOutsideTheConfiguredCatalogCollapsesToOther() {
        // A brain id, a tenant, and a correlation id arriving through the provider field must
        // all collapse: cardinality is bounded by configuration, not by traffic.
        metrics.runCreated(LabRunGroup.Mode.INDEPENDENT,
                "aaaaaaaa-1111-4111-8111-111111111111");
        metrics.usageQuality(UsageQuality.REPORTED, "tenant-canary-x");
        metrics.runCreated(LabRunGroup.Mode.INDEPENDENT, null);

        assertEquals("other", metrics.safeProvider("tenant-canary-x"));
        assertEquals("other", metrics.safeProvider(null));
        assertEquals("synthetic", metrics.safeProvider("SYNTHETIC"));
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertFalse(tag.getValue().contains("tenant-canary"),
                        "hostile provider string reached a tag");
                assertFalse(UUID_SHAPED.matcher(tag.getValue()).find());
            }
        }
    }

    @Test
    void queueDepthGaugesExistPerConfiguredProviderAndReadTheTable() {
        when(runs.countByStatusAndRequestedProvider(eq(LabRun.Status.QUEUED), any()))
                .thenReturn(3L);

        Double depth = registry.get("rag.instances.queue.depth")
                .tag("provider", "synthetic").gauge().value();

        assertEquals(3.0, depth);
        // One gauge per configured provider — nothing traffic-driven can add one.
        assertEquals(1, registry.find("rag.instances.queue.depth").gauges().size());
    }

    @Test
    void terminalLatencyRunsFromCreationToTerminalAndSkipsUnmeasuredRuns() {
        metrics.runTerminal(terminalRun("synthetic"), LabRunGroup.Mode.INDEPENDENT);

        Timer latency = registry.get("rag.instances.run.latency")
                .tag("provider", "synthetic").tag("status", "SUCCEEDED").timer();
        assertEquals(1, latency.count());
        assertEquals(90.0, latency.totalTime(TimeUnit.SECONDS), 0.001);

        // A run without timestamps still counts, but records no invented latency.
        LabRun unmeasured = new LabRun();
        unmeasured.setStatus(LabRun.Status.FAILED);
        unmeasured.setRequestedProvider("synthetic");
        assertDoesNotThrow(() ->
                metrics.runTerminal(unmeasured, LabRunGroup.Mode.INDEPENDENT));
        assertEquals(0, registry.find("rag.instances.run.latency")
                .tag("status", "FAILED").timers().size());
    }

    @Test
    void recordingNeverThrowsEvenOnHostileInput() {
        // Telemetry must never become a new way for a run to fail.
        assertDoesNotThrow(() -> metrics.usageQuality(null, "synthetic"));
        assertDoesNotThrow(() -> metrics.runTerminal(new LabRun(), null));
        assertDoesNotThrow(() -> metrics.runCreated(LabRunGroup.Mode.INDEPENDENT, null));
    }

    // ================================================================ fixtures

    private static LabRun terminalRun(String provider) {
        LabRun run = new LabRun();
        run.setId(UUID.randomUUID());
        run.setStatus(LabRun.Status.SUCCEEDED);
        run.setRequestedProvider(provider);
        run.setCreatedAt(OffsetDateTime.of(2026, 8, 27, 12, 0, 0, 0, ZoneOffset.UTC));
        run.setTerminalAt(OffsetDateTime.of(2026, 8, 27, 12, 1, 30, 0, ZoneOffset.UTC));
        return run;
    }

    private static ModelEntry syntheticModel() {
        return new ModelEntry("synthetic", "synthetic-analyzer", 100_000L, 8_000L,
                "CONSERVATIVE_RANGE", new BigDecimal("1.00"), null, new BigDecimal("5.00"));
    }
}
