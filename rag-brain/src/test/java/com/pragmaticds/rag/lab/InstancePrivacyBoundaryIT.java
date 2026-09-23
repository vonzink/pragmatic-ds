package com.pragmaticds.rag.lab;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.EncodedManifest;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.provider.AiModelProvider;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.repository.BrainConnectorClientRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import com.pragmaticds.rag.service.connect.ConnectorPermission;
import com.pragmaticds.rag.service.connect.ConnectorScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where caller-supplied identity is allowed to rest, proven against the schema itself.
 *
 * <p>A tenant id and a correlation id enter through a real connector launch carrying canary
 * values, and then every text-shaped column of every table in the schema is swept: the canaries
 * may exist in exactly the two ownership-context columns the contract stores them in, and nowhere
 * else — not in audit events, connector events, group rows, usage, hashes, or DTOs. The sweep is
 * driven by {@code information_schema}, so a new column added anywhere is automatically inside
 * the boundary the day it appears.
 *
 * <p>The same flow then proves the retention lifecycle end to end through the real stack:
 * cancelling releases the reservation and concludes the group; purging deletes the context, the
 * leaves, the runs, and the group in that order's effect; the immutable record — release,
 * registration, collection, snapshot, catalog version, audit tombstone — survives; and after the
 * purge the caller-supplied canaries exist nowhere, with only the administrator's standing
 * tenant grant remaining on the connector row.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.instances.connector-enabled=true",
        // These contexts mock the parse boundary, so no engine is ever called — but the
        // startup validator requires a declared auth mode before execution or the
        // connector may run, because a base URL alone never proved this deployment may
        // talk to the engine. Synthetic: nothing here authenticates to anything.
        "ragbrain.lab.engine.api-key=synthetic-engine-key-not-a-real-key",
        "ragbrain.rag.admin.api-key=privacy-boundary-it-key",
        "ragbrain.rag.rate-limit.connector-requests-per-minute=1000",
        "ragbrain.instances.models[0].provider=synthetic",
        "ragbrain.instances.models[0].model=synthetic-analyzer",
        "ragbrain.instances.models[0].context-token-ceiling=100000",
        "ragbrain.instances.models[0].output-token-ceiling=8000",
        "ragbrain.instances.models[0].tokenizer=CONSERVATIVE_RANGE",
        "ragbrain.instances.models[0].input-usd-per-million=1.00",
        "ragbrain.instances.models[0].output-usd-per-million=5.00"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstancePrivacyBoundaryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String ADMIN_KEY = "privacy-boundary-it-key";
    private static final String TENANT_CANARY = "tenant-canary-4f9d1c";
    private static final String CORRELATION_CANARY = "corr-canary-4f9d1c";

    private static final UUID PACKAGE =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SOURCE =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID REGISTRATION =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID ENGINE_JOB =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Autowired MockMvc mvc;
    @Autowired BrainRepository brains;
    @Autowired BrainConnectorClientRepository connectors;
    @Autowired LabInstanceRepository instances;
    @Autowired LabInstancePointerRepository pointers;
    @Autowired CorpusCollectionService collections;
    @Autowired InstanceManifestCodec codec;
    @Autowired JdbcTemplate jdbc;

    @MockBean ParsedDataResolver parsedInputs;

    /** Credential stand-in for the synthetic model; execution is off and generate() must be dead. */
    @TestConfiguration
    static class SyntheticProviderConfiguration {
        @Bean
        AiModelProvider syntheticProvider() {
            return new AiModelProvider() {
                @Override
                public AiResponse generate(AiRequest request) {
                    throw new IllegalStateException(
                            "no live provider call may happen in this test");
                }

                @Override
                public String getProviderName() {
                    return "synthetic";
                }

                @Override
                public String getModelName() {
                    return "synthetic-analyzer";
                }
            };
        }
    }

    @Test
    void canariesRestOnlyWhereTheContractStoresThemAndVanishOnPurge() throws Exception {
        Seeded seeded = seed();
        stubCompatibleParse();

        // ---- launch with the canaries as the tenant and the correlation id.
        MvcResult accepted = mvc.perform(
                        post("/api/connect/v1/brains/" + seeded.brainSlug
                                + "/instances/income/run-groups")
                                .header("Authorization", "Bearer " + seeded.token)
                                .header("Idempotency-Key", "privacy-it-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"tenantId": "%s", "externalRequestId": "%s",
                                         "packageId": "%s", "revision": 4,
                                         "selectedSourceIds": ["%s"]}
                                        """.formatted(TENANT_CANARY, CORRELATION_CANARY,
                                        PACKAGE, SOURCE)))
                .andExpect(status().isAccepted())
                .andReturn();
        String groupId = accepted.getResponse().getContentAsString()
                .replaceAll(".*\"runGroupId\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");

        // ---- the schema-wide sweep: each canary in exactly its contracted columns, nowhere
        // else. The connector's allow-list legitimately names the tenant — that is the
        // administrator's grant, configured before any caller arrived — so the boundary under
        // test is that caller-supplied run data adds exactly one resting place: the ownership
        // context row.
        assertEquals(List.of("brain_connector_clients.allowed_tenants",
                        "lab_connector_run_group_context.tenant_id"),
                columnsContaining(TENANT_CANARY),
                "the tenant may rest only in the grant and the ownership context");
        assertEquals(List.of("lab_connector_run_group_context.external_request_id"),
                columnsContaining(CORRELATION_CANARY),
                "the correlation id may rest only in the ownership context");

        // ---- the admin surface shows the group without the connector's tenant or correlation.
        String adminView = mvc.perform(
                        get("/api/ai/admin/instances/run-groups/" + groupId)
                                .param("brain", seeded.brainId.toString())
                                .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(adminView.contains(TENANT_CANARY), "admin DTOs must not carry the tenant");
        assertFalse(adminView.contains(CORRELATION_CANARY),
                "admin DTOs must not carry the correlation id");

        // ---- purging an active group refuses through the real stack.
        mvc.perform(delete("/api/ai/admin/instances/run-groups/" + groupId)
                        .param("brain", seeded.brainId.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_STILL_ACTIVE"));

        // ---- cancel: reservation released, group concluded — with no dispatcher anywhere.
        mvc.perform(post("/api/ai/admin/instances/run-groups/" + groupId + "/cancel")
                        .param("brain", seeded.brainId.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "privacy-it-cancel-1"))
                .andExpect(status().isOk());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM lab_run_group WHERE id = ?::uuid", String.class, groupId));
        assertEquals(0, count("SELECT count(*) FROM lab_run_group "
                + "WHERE id = ?::uuid AND terminal_at IS NULL", groupId));
        // The released reservation stops counting against the daily budget by construction:
        // the budget sum filters on status = RESERVED, and nothing here is RESERVED any more.
        assertEquals(0, count("SELECT count(*) FROM lab_spend_reservation "
                + "WHERE brain_id = ? AND status = 'RESERVED'", seeded.brainId));

        // ---- purge: leaves, runs, context, and group go; the immutable record stays.
        mvc.perform(delete("/api/ai/admin/instances/run-groups/" + groupId)
                        .param("brain", seeded.brainId.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.runsDeleted").value(1))
                .andExpect(jsonPath("$.connectorContextDeleted").value(true));

        assertEquals(0, count("SELECT count(*) FROM lab_connector_run_group_context "
                + "WHERE run_group_id = ?::uuid", groupId));
        assertEquals(0, count("SELECT count(*) FROM lab_run_group WHERE id = ?::uuid", groupId));
        assertEquals(0, count("SELECT count(*) FROM lab_run WHERE run_group_id = ?::uuid",
                groupId));
        assertEquals(0, count("SELECT count(*) FROM lab_model_usage WHERE brain_id = ?",
                seeded.brainId));
        assertEquals(0, count("SELECT count(*) FROM lab_spend_reservation WHERE brain_id = ?",
                seeded.brainId));

        assertEquals(1, count("SELECT count(*) FROM lab_instance_release WHERE brain_id = ?",
                seeded.brainId));
        assertEquals(1, count("SELECT count(*) FROM lab_document_registration "
                + "WHERE brain_id = ?", seeded.brainId));
        assertEquals(1, count("SELECT count(*) FROM brain_corpus_snapshot WHERE brain_id = ?",
                seeded.brainId));
        assertTrue(count("SELECT count(*) FROM lab_model_catalog_version") >= 1,
                "catalog history survives a purge");
        assertTrue(count("SELECT count(*) FROM lab_audit_event "
                        + "WHERE subject_type = 'GROUP' AND subject_id = ?::uuid", groupId) >= 1,
                "the purge leaves a GROUP tombstone");

        // ---- a second purge of the same group is an idempotent success that removed nothing.
        mvc.perform(delete("/api/ai/admin/instances/run-groups/" + groupId)
                        .param("brain", seeded.brainId.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false));

        // ---- after the purge, the caller-supplied canaries exist nowhere; only the
        // administrator's standing grant remains.
        assertEquals(List.of("brain_connector_clients.allowed_tenants"),
                columnsContaining(TENANT_CANARY));
        assertEquals(List.of(), columnsContaining(CORRELATION_CANARY));
    }

    // ============================================================ the sweep

    /**
     * Every {@code table.column} in the public schema whose text-shaped content contains the
     * needle. Table and column names come from {@code information_schema}, never from input.
     */
    private List<String> columnsContaining(String needle) {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT table_name, column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND data_type IN ('character varying', 'text', 'character', "
                        + "'json', 'jsonb') ORDER BY table_name, column_name");
        List<String> hits = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            Integer found = jdbc.queryForObject(
                    "SELECT count(*) FROM \"" + table + "\" WHERE \"" + name + "\"::text LIKE ?",
                    Integer.class, "%" + needle + "%");
            if (found != null && found > 0) {
                hits.add(table + "." + name);
            }
        }
        return hits;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ============================================================ seeding

    private record Seeded(UUID brainId, String brainSlug, String token) {}

    private Seeded seed() {
        UUID brainId = UUID.randomUUID();
        String slug = "privacy-brain";
        Brain brain = new Brain(brainId, slug, "Privacy IT");
        brain.setActive(true);
        brains.save(brain);

        var collection = collections.create(brainId, "privacy-guidelines",
                "Guidelines", "privacy-collection-key");

        instances.save(new LabInstance(brainId, "income", "Income", "Synthetic fixture."));
        EncodedManifest encoded = codec.encode(manifest(collection.id(), collection.version()));
        byte[] canonical = new LabManifestWriter().canonicalize(encoded.json());
        UUID releaseId = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_instance_release "
                        + "(id, brain_id, instance_slug, release_number, provenance_mode, "
                        + "manifest, manifest_sha256) "
                        + "VALUES (?, ?, 'income', 1, 'PRODUCTION', cast(? as jsonb), ?)",
                releaseId, brainId, new String(canonical, StandardCharsets.UTF_8),
                encoded.sha256());
        LabInstancePointer pointer = new LabInstancePointer();
        pointer.setBrainId(brainId);
        pointer.setInstanceSlug("income");
        pointer.setProductionReleaseId(releaseId);
        pointer.setPointerVersion(1L);
        pointers.save(pointer);

        jdbc.update("INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                + "VALUES (?, ?)", PACKAGE, brainId);
        jdbc.update("INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                        + " registration_mode, selected_revision, source_set_sha256) "
                        + "VALUES (?, ?, 'income', ?, ?, 'EXISTING_PARSE', 4, ?)",
                REGISTRATION, brainId, PACKAGE, ENGINE_JOB, "c".repeat(64));

        String token = "rb_conn_privacy";
        BrainConnectorClient connector = new BrainConnectorClient(UUID.randomUUID(),
                "DM privacy", "SERVER", ConnectorAuthService.hashToken(token));
        connector.setBrainId(brainId);
        connector.setScopes(List.of(ConnectorScope.INSTANCE_RUN, ConnectorScope.INSTANCE_RUN_READ));
        connector.setAllowedTenants(List.of(TENANT_CANARY));
        connector.setGrantedPermissions(List.of(ConnectorPermission.INSTANCE_RUN_LIVE,
                ConnectorPermission.INSTANCE_RUN_READ));
        connectors.save(connector);

        return new Seeded(brainId, slug, token);
    }

    private void stubCompatibleParse() {
        ParsedDataResolver.VerifiedSelection compatible =
                mock(ParsedDataResolver.VerifiedSelection.class);
        when(compatible.compatibility()).thenReturn(
                new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 1));
        when(parsedInputs.verifyExisting(any(), any())).thenReturn(compatible);
        ParsedDataResolver.VerifiedParsedInput pinned =
                new ParsedDataResolver.VerifiedParsedInput(REGISTRATION, PACKAGE, 4, PACKAGE, 1,
                        "1.0.0", "DOCENGINE-C14N-1", "b".repeat(64), 4096L, "c".repeat(64),
                        List.of(SOURCE), null, null,
                        new ParsedDataCompatibilityService.CompatibilityDecision(
                                true, null, List.of(), 1));
        when(parsedInputs.pin(any())).thenReturn(pinned);
        when(parsedInputs.review(any(), any(), any(), any())).thenReturn(pinned);
    }

    private static InstanceReleaseManifest manifest(UUID collectionId, long collectionVersion) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("synthetic", "synthetic-analyzer",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(collectionId, collectionVersion))),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "income",
                        BigDecimal.ZERO),
                List.of(),
                new InstanceReleaseManifest.OutputContract("analyzer-envelope-v2", "a".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100_000L, 20_000, 8_000, 0, 2,
                        new BigDecimal("5.00")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.80")));
    }
}
