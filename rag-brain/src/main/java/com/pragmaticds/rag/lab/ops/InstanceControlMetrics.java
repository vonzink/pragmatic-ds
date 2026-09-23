package com.pragmaticds.rag.lab.ops;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.model.InstanceModelProperties;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.LabSpendReservation;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every instance-control meter, behind one wall that bounds what a tag may say.
 *
 * <p><b>Tag values are catalogs, never data.</b> A metric tag lives forever in a time-series
 * database with none of the access control the application enforces, so nothing
 * request-shaped may become one: no brain id, no instance slug, no tenant, no package, source,
 * loan, or run id, no prompt, no error message, no result value. Statuses and outcomes come from
 * enum names; the provider tag is admitted only if it appears in the <em>configured</em> model
 * catalog, and anything else — including a hostile string arriving through a provider field —
 * collapses to {@code other}. Cardinality is therefore bounded by configuration, not by traffic.
 *
 * <p><b>Recording never throws.</b> Telemetry that could fail a run would be a new failure mode
 * introduced by observability; every public method swallows and logs at debug.
 *
 * <p><b>Counting happens once per fact.</b> The call sites are chosen where the fact becomes
 * true exactly once — group insertion (replays return before it), the idempotent usage report,
 * the guarded terminal transitions — so a retry or a replay does not inflate a counter.
 */
@Component
@ConditionalOnExpression(
        "${ragbrain.lab.enabled:false} or ${ragbrain.instances.enabled:false}")
public class InstanceControlMetrics {

    private static final Logger log = LoggerFactory.getLogger(InstanceControlMetrics.class);

    static final String OTHER_PROVIDER = "other";

    private final MeterRegistry registry;
    private final Set<String> configuredProviders;

    public InstanceControlMetrics(MeterRegistry registry,
                                  InstanceModelProperties models,
                                  LabRunRepository runs) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.configuredProviders = models.models().stream()
                .map(InstanceModelProperties.ModelEntry::provider)
                .filter(Objects::nonNull)
                .map(provider -> provider.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        // Queue depth per configured provider: a gauge over the table, so it is correct after a
        // restart and after retention, not a drifting in-memory counter. The provider set is the
        // configuration's, so cardinality cannot grow with traffic.
        for (String provider : configuredProviders) {
            Gauge.builder("rag.instances.queue.depth",
                            () -> runs.countByStatusAndRequestedProvider(
                                    LabRun.Status.QUEUED, provider))
                    .description("Queued instance-run members awaiting dispatch")
                    .tag("provider", provider)
                    .register(registry);
        }
    }

    /** A member entered the queue. Counted at group insertion; replays never reach it. */
    public void runCreated(LabRunGroup.Mode mode, String provider) {
        record(() -> counter("rag.instances.run.count",
                "mode", mode.name(), "status", LabRun.Status.QUEUED.name(),
                "provider", safeProvider(provider)).increment());
    }

    /** A member reached a terminal state; latency is creation to terminal, queue wait included. */
    public void runTerminal(LabRun run, LabRunGroup.Mode mode) {
        record(() -> {
            String provider = safeProvider(run.getRequestedProvider());
            String status = run.getStatus().name();
            counter("rag.instances.run.count",
                    "mode", mode == null ? "UNKNOWN" : mode.name(),
                    "status", status, "provider", provider).increment();
            OffsetDateTime created = run.getCreatedAt();
            OffsetDateTime terminal = run.getTerminalAt();
            if (created != null && terminal != null) {
                Timer.builder("rag.instances.run.latency")
                        .description("Creation to terminal, queue wait included")
                        .tag("provider", provider).tag("status", status)
                        .register(registry)
                        .record(Duration.between(created, terminal));
            }
        });
    }

    /** One dispatcher claim attempt. Outcomes are the dispatcher's own bounded set. */
    public void dispatchClaim(String outcome) {
        record(() -> counter("rag.instances.dispatch.claim",
                "outcome", outcome).increment());
    }

    /** A reservation changed state: RESERVED at creation, CONSUMED or RELEASED at settlement. */
    public void reservation(LabSpendReservation.Status status) {
        record(() -> counter("rag.instances.budget.reservation",
                "status", status.name()).increment());
    }

    /** The provider's usage report quality, counted once by the idempotent report guard. */
    public void usageQuality(UsageQuality quality, String provider) {
        record(() -> counter("rag.instances.usage.quality",
                "quality", quality.name(), "provider", safeProvider(provider)).increment());
    }

    /** One promotion attempt's outcome — a pointer move, a rollback, or a refusal code. */
    public void promotion(String outcome) {
        record(() -> counter("rag.instances.promotion", "outcome", outcome).increment());
    }

    /** One connector refusal, by its stable public code — the same word the caller was told. */
    public void connectorRefusal(String code) {
        record(() -> counter("rag.instances.connector.refusal", "code", code).increment());
    }

    // ================================================================ the wall

    /** The configured provider name, or {@code other}: tag values are catalogs, never data. */
    String safeProvider(String provider) {
        if (provider == null) {
            return OTHER_PROVIDER;
        }
        String normalized = provider.toLowerCase(Locale.ROOT);
        return configuredProviders.contains(normalized) ? normalized : OTHER_PROVIDER;
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    private void record(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException failure) {
            // Telemetry must never become a new way for a run to fail.
            log.debug("metric recording skipped: {}", failure.getClass().getSimpleName());
        }
    }
}
