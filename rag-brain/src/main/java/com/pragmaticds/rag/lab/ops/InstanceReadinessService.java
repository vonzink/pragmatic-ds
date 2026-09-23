package com.pragmaticds.rag.lab.ops;

import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService;
import com.pragmaticds.rag.lab.run.RunGroupDispatcher;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.lab.service.LabAuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * What this deployment can actually do right now, said in booleans, counts, and one version
 * string — never a URL, a credential, a tenant, or a provider's exception text.
 *
 * <p>Each capability's readiness is the conjunction of the things that would make its first real
 * request fail: execution needs the dispatcher bean, the cipher key, a decided retention policy,
 * a priced catalog, and engine configuration; the connector needs the catalog and the engine;
 * promotion needs only its flag and the schema. The report always carries every signal, so a
 * {@code 503} tells the operator <em>which</em> prerequisite is missing without a second call.
 *
 * <p>Document Engine <em>reachability</em> is deliberately reported as configuration plus client
 * presence, not as a live probe: the engine client exposes no health endpoint, and a readiness
 * check that fired real engine reads on every scrape would be load, not observability.
 * Reachability failures surface per-call as {@code PARSE_ENGINE_UNAVAILABLE}.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceReadinessService {

    /** The newest migration this build ships. Bump alongside every new V-file. */
    static final int EXPECTED_SCHEMA_VERSION = 41;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<RunGroupDispatcher> dispatcher;
    private final ObjectProvider<LabPayloadCipher> cipher;
    private final ObjectProvider<LabAuditService> audit;
    private final ObjectProvider<DocumentEngineClient> engineClient;
    private final InstanceModelCatalogService catalog;
    private final boolean executionEnabled;
    private final boolean connectorEnabled;
    private final boolean promotionEnabled;
    private final int retentionDays;
    private final String engineBaseUrl;
    private final boolean engineAuthenticated;

    public InstanceReadinessService(JdbcTemplate jdbc,
                                    ObjectProvider<RunGroupDispatcher> dispatcher,
                                    ObjectProvider<LabPayloadCipher> cipher,
                                    ObjectProvider<LabAuditService> audit,
                                    ObjectProvider<DocumentEngineClient> engineClient,
                                    InstanceModelCatalogService catalog,
                                    @Value("${ragbrain.instances.execution.enabled:false}")
                                    boolean executionEnabled,
                                    @Value("${ragbrain.instances.connector-enabled:false}")
                                    boolean connectorEnabled,
                                    @Value("${ragbrain.instances.promotion-enabled:false}")
                                    boolean promotionEnabled,
                                    @Value("${ragbrain.instances.retention.terminal-run-days:0}")
                                    int retentionDays,
                                    @Value("${ragbrain.lab.engine.base-url:}")
                                    String engineBaseUrl,
                                    @Value("${ragbrain.lab.engine.api-key:}")
                                    String engineApiKey,
                                    @Value("${ragbrain.lab.engine.bearer-token:}")
                                    String engineBearerToken,
                                    @Value("${ragbrain.lab.engine.dev-auth:false}")
                                    boolean engineDevAuth) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.engineClient = Objects.requireNonNull(engineClient, "engineClient");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executionEnabled = executionEnabled;
        this.connectorEnabled = connectorEnabled;
        this.promotionEnabled = promotionEnabled;
        this.retentionDays = retentionDays;
        this.engineBaseUrl = engineBaseUrl == null ? "" : engineBaseUrl;
        // Reported together because either alone is a half-truth: a URL says where the engine is,
        // a credential says this deployment may talk to it, and an operator reading
        // engineConfigured is asking whether a run could reach parsed data.
        this.engineAuthenticated =
                engineDevAuth
                        || (engineApiKey != null && !engineApiKey.isBlank())
                        || (engineBearerToken != null && !engineBearerToken.isBlank());
    }

    /** Safe operational state: booleans, bounded counts, and a schema version string. */
    public record ReadinessReport(
            String schemaVersion,
            boolean schemaCurrent,
            int instances,
            int releases,
            int livePointers,
            boolean engineConfigured,
            boolean engineClientPresent,
            boolean encryptionReady,
            boolean auditWritable,
            int auditWriteFailures,
            boolean retentionDecided,
            int retentionDays,
            boolean catalogReady,
            int offerableModels,
            boolean executionEnabled,
            boolean dispatcherPresent,
            int queueDepth,
            int staleLeases,
            boolean connectorEnabled,
            boolean promotionEnabled,
            boolean readReady,
            boolean executionReady,
            boolean connectorReady,
            boolean promotionReady) {}

    public ReadinessReport report() {
        String schemaVersion = schemaVersion();
        boolean schemaCurrent = schemaCurrent(schemaVersion);

        int instances = count("SELECT count(*) FROM lab_instance");
        int releases = count("SELECT count(*) FROM lab_instance_release");
        int livePointers = count("SELECT count(*) FROM lab_instance_pointer "
                + "WHERE production_release_id IS NOT NULL");

        boolean engineConfigured = !engineBaseUrl.isBlank() && engineAuthenticated;
        boolean engineClientPresent = engineClient.getIfAvailable() != null;
        LabPayloadCipher sealer = cipher.getIfAvailable();
        boolean encryptionReady = sealer != null && sealer.isAvailable();

        // Reported because the alternative is how this was missed: audit writes are swallowed by
        // design so they cannot fail a user's operation, and on 2026-09-01 that meant every write
        // had failed for weeks behind a log line nobody tailed, with lab_audit_event empty.
        //
        // auditWritable is deliberately "nothing has failed", not "writes are proven to work" —
        // a deployment that has attempted none is reported as writable because there is nothing
        // to report otherwise, and auditWriteFailures next to it is the number that matters. Any
        // non-zero is an incident: audit rows cannot be back-dated, so the gap is permanent.
        LabAuditService auditor = audit.getIfAvailable();
        long failures = auditor == null ? 0L : auditor.writeFailures();
        int auditWriteFailures = (int) Math.min(failures, Integer.MAX_VALUE);
        boolean auditWritable = failures == 0L;

        boolean retentionDecided = retentionDays >= 1;

        boolean catalogReady;
        int offerableModels;
        try {
            catalogReady = true;
            offerableModels = catalog.available().size();
            if (offerableModels == 0) {
                catalogReady = false;
            }
        } catch (RuntimeException unavailable) {
            // The catalog's own exception carries a code; readiness carries only the boolean.
            catalogReady = false;
            offerableModels = 0;
        }

        boolean dispatcherPresent = dispatcher.getIfAvailable() != null;
        int queueDepth = count("SELECT count(*) FROM lab_run WHERE status = 'QUEUED'");
        int staleLeases = count("SELECT count(*) FROM lab_run "
                + "WHERE status = 'PROCESSING' AND lease_expires_at < now()");

        boolean readReady = schemaCurrent;
        boolean executionReady = executionEnabled && dispatcherPresent && encryptionReady
                && retentionDecided && catalogReady && engineConfigured && schemaCurrent;
        boolean connectorReady = connectorEnabled && catalogReady && engineConfigured
                && schemaCurrent;
        boolean promotionReady = promotionEnabled && schemaCurrent;

        return new ReadinessReport(schemaVersion, schemaCurrent,
                instances, releases, livePointers,
                engineConfigured, engineClientPresent, encryptionReady,
                auditWritable, auditWriteFailures,
                retentionDecided, retentionDays,
                catalogReady, offerableModels,
                executionEnabled, dispatcherPresent, queueDepth, staleLeases,
                connectorEnabled, promotionEnabled,
                readReady, executionReady, connectorReady, promotionReady);
    }

    private String schemaVersion() {
        try {
            String version = jdbc.queryForObject(
                    "SELECT version FROM flyway_schema_history "
                            + "WHERE success AND version IS NOT NULL "
                            + "ORDER BY installed_rank DESC LIMIT 1", String.class);
            return version == null ? "0" : version;
        } catch (RuntimeException absent) {
            return "0";
        }
    }

    private boolean schemaCurrent(String version) {
        try {
            return Integer.parseInt(version.split("\\.")[0]) >= EXPECTED_SCHEMA_VERSION;
        } catch (NumberFormatException unparsable) {
            return false;
        }
    }

    private int count(String sql) {
        try {
            Integer value = jdbc.queryForObject(sql, Integer.class);
            return value == null ? 0 : value;
        } catch (RuntimeException failure) {
            return 0;
        }
    }
}
