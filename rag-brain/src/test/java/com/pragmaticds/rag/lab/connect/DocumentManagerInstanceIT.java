package com.pragmaticds.rag.lab.connect;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.domain.BrainConnectorEvent;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.EncodedManifest;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.provider.AiModelProvider;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.repository.BrainConnectorClientRepository;
import com.pragmaticds.rag.repository.BrainConnectorEventRepository;
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
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The connector surface, end to end through the real application: real interceptor and rate
 * limiter, real connector rows with a hashed token, real Flyway schema through V40, real
 * RunGroupService writing the group and its ownership context in one transaction, real advice
 * chain.
 *
 * <p>One boundary is mocked: {@link ParsedDataResolver}, which is where this repository talks to
 * the external Document Engine. Its real behaviour — descriptor checks, canonical envelope
 * verification, compatibility — has its own unit tests and admin-surface ITs; re-driving the
 * engine protocol here would test that layer a third time at the price of a two-hundred-line
 * canonical envelope fixture. Everything on this side of it is the real thing.
 *
 * <p>Execution dispatch stays off ({@code ragbrain.instances.execution.enabled} defaults false),
 * so members rest at QUEUED — which is exactly what the 202-then-poll contract promises before a
 * dispatcher picks anything up, and what lets these tests assert the whole launch-and-poll
 * lifecycle without a provider anywhere.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.instances.connector-enabled=true",
        // These contexts mock the parse boundary, so no engine is ever called — but the
        // startup validator requires a declared auth mode before execution or the
        // connector may run, because a base URL alone never proved this deployment may
        // talk to the engine. Synthetic: nothing here authenticates to anything.
        "ragbrain.lab.engine.api-key=synthetic-engine-key-not-a-real-key",
        "ragbrain.rag.admin.api-key=dm-connector-it-key",
        "ragbrain.rag.rate-limit.connector-requests-per-minute=1000",
        // A synthetic model, priced synthetically. Never a real provider's model id.
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
class DocumentManagerInstanceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

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
    @Autowired BrainConnectorEventRepository events;
    @Autowired LabInstanceRepository instances;
    @Autowired LabInstancePointerRepository pointers;
    @Autowired CorpusCollectionService collections;
    @Autowired InstanceManifestCodec codec;
    @Autowired JdbcTemplate jdbc;

    @MockBean ParsedDataResolver parsedInputs;

    /**
     * The model catalog offers only models whose provider has a bean — configuration intersected
     * with credentials. Without this, preflight refuses the synthetic model as unpriceable and
     * every launch answers 422. The bean is a credential stand-in, not a provider: execution
     * dispatch is off in this test, so a {@code generate} call would mean a live-provider path
     * opened by accident, and it throws instead.
     */
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
    void bothRoutesRequireAConnectorTokenThroughTheRealInterceptor() throws Exception {
        mvc.perform(post("/api/connect/v1/brains/anything/instances/income/run-groups")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/connect/v1/brains/anything/instance-run-groups/"
                        + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void launchesPollsRepliesAndGuardsTenancyThroughTheRealStack() throws Exception {
        Seeded seeded = seed("dm-live");
        stubCompatibleParse();

        // The run row's registration_id is a real NOT NULL foreign key. The mocked resolver
        // vouches for a registration it would normally have written itself, so the row it
        // promises has to exist — package binding first, then the EXISTING_PARSE registration.
        jdbc.update("INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                + "VALUES (?, ?)", PACKAGE, seeded.brainId);
        jdbc.update("INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                        + " registration_mode, selected_revision, source_set_sha256) "
                        + "VALUES (?, ?, 'income', ?, ?, 'EXISTING_PARSE', 4, ?)",
                REGISTRATION, seeded.brainId, PACKAGE, ENGINE_JOB, "c".repeat(64));

        // ---- launch: 202, Location, estimates priced from the synthetic catalog.
        ResultActions launch = mvc.perform(
                post("/api/connect/v1/brains/" + seeded.brainSlug
                        + "/instances/income/run-groups")
                        .header("Authorization", "Bearer " + seeded.token)
                        .header("Idempotency-Key", "dm-it-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("tenant-a")));
        MvcResult firstAnswer = launch.andReturn();
        if (firstAnswer.getResponse().getStatus() != 202) {
            // Blind-build diagnostics: without this, a failure here reports only the status and
            // the real exception dies unseen in the mock request.
            throw new AssertionError("launch answered " + firstAnswer.getResponse().getStatus()
                    + " body=" + firstAnswer.getResponse().getContentAsString(),
                    firstAnswer.getResolvedException());
        }
        MvcResult accepted = launch
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.estimates[0].provider").value("synthetic"))
                .andExpect(jsonPath("$.estimates[0].estimateQuality").value("ESTIMATED_RANGE"))
                .andReturn();
        String groupId = accepted.getResponse().getContentAsString()
                .replaceAll(".*\"runGroupId\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");

        // ---- the ownership row committed with the group, in the same transaction.
        Map<String, Object> context = jdbc.queryForMap(
                "SELECT connector_client_id, tenant_id, external_request_sha256 "
                        + "FROM lab_connector_run_group_context WHERE run_group_id = ?::uuid",
                groupId);
        assertEquals(seeded.connectorId, context.get("connector_client_id"));
        assertEquals("tenant-a", context.get("tenant_id"));

        // ---- poll as the creating tenant: QUEUED members, expected ranges, no actuals invented.
        mvc.perform(get("/api/connect/v1/brains/" + seeded.brainSlug
                        + "/instance-run-groups/" + groupId)
                        .header("Authorization", "Bearer " + seeded.token)
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.members[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.members[0].expectedInputMin").isNumber())
                // Present and null, not absent: the contract serializes unmeasured as an explicit
                // null so a consumer cannot mistake "never reported" for zero.
                .andExpect(jsonPath("$.members[0].actualInputTokens").value(nullValue()))
                .andExpect(jsonPath("$.members[0].usageQuality").value("PENDING"))
                .andExpect(jsonPath("$.members[0].result").value(nullValue()));

        // ---- the other tenant on the same token passes auth and still reads nothing.
        mvc.perform(get("/api/connect/v1/brains/" + seeded.brainSlug
                        + "/instance-run-groups/" + groupId)
                        .header("Authorization", "Bearer " + seeded.token)
                        .header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isNotFound());

        // ---- replay: same key, same request, same group — and no second resolution anywhere.
        mvc.perform(post("/api/connect/v1/brains/" + seeded.brainSlug
                        + "/instances/income/run-groups")
                        .header("Authorization", "Bearer " + seeded.token)
                        .header("Idempotency-Key", "dm-it-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("tenant-a")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runGroupId").value(groupId));
        verify(parsedInputs, times(1)).verifyExisting(any(), any());

        // ---- same key, different request: refused, and still exactly one group for the key.
        mvc.perform(post("/api/connect/v1/brains/" + seeded.brainSlug
                        + "/instances/income/run-groups")
                        .header("Authorization", "Bearer " + seeded.token)
                        .header("Idempotency-Key", "dm-it-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("tenant-a").replace("\"revision\": 4", "\"revision\": 5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // ---- audit: ids, scope and status only. No tenant, no source ids, no result canary.
        List<BrainConnectorEvent> recorded = events.findAll();
        assertTrue(recorded.stream().anyMatch(event ->
                "INSTANCE_RUN".equals(event.getEventType()) && "200".equals(event.getStatus())));
        for (BrainConnectorEvent event : recorded) {
            String flattened = String.valueOf(event.getEventType()) + event.getScope()
                    + event.getRequestHost() + event.getStatus();
            assertFalse(flattened.contains("tenant-a"), "audit must not carry the tenant");
            assertFalse(flattened.contains(SOURCE.toString()),
                    "audit must not carry selected sources");
        }
    }

    @Test
    void anIncompatibleParseCreatesNothingAtAll() throws Exception {
        Seeded seeded = seed("dm-incompatible");
        ParsedDataResolver.VerifiedSelection incompatible =
                mock(ParsedDataResolver.VerifiedSelection.class);
        when(incompatible.compatibility()).thenReturn(
                new ParsedDataCompatibilityService.CompatibilityDecision(false,
                        ParsedDataCompatibilityService.RejectionCode.NO_SUPPORTED_DOCUMENT,
                        List.of(), 0));
        when(parsedInputs.verifyExisting(any(), any())).thenReturn(incompatible);

        mvc.perform(post("/api/connect/v1/brains/" + seeded.brainSlug
                        + "/instances/income/run-groups")
                        .header("Authorization", "Bearer " + seeded.token)
                        .header("Idempotency-Key", "dm-it-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("tenant-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARSE_INCOMPATIBLE"));

        // Nothing durable: no group, no context, no snapshot for a parse nothing can analyze.
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM lab_run_group WHERE brain_id = ?", Integer.class,
                seeded.brainId));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM brain_corpus_snapshot WHERE brain_id = ?", Integer.class,
                seeded.brainId));
    }

    @Test
    void aTokenWithoutTheSpendPermissionCannotLaunchEvenWithTheScope() throws Exception {
        Seeded seeded = seed("dm-readonly", List.of(ConnectorPermission.INSTANCE_RUN_READ));

        mvc.perform(post("/api/connect/v1/brains/" + seeded.brainSlug
                        + "/instances/income/run-groups")
                        .header("Authorization", "Bearer " + seeded.token)
                        .header("Idempotency-Key", "dm-it-key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("tenant-a")))
                .andExpect(status().isForbidden());

        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM lab_run_group WHERE brain_id = ?", Integer.class,
                seeded.brainId));
    }

    // ============================================================ seeding

    private record Seeded(UUID brainId, String brainSlug, UUID connectorId, String token) {}

    private Seeded seed(String prefix) {
        return seed(prefix, List.of(ConnectorPermission.INSTANCE_RUN_LIVE,
                ConnectorPermission.INSTANCE_RUN_READ));
    }

    /** A brain, an active instance with a live V2 release pinning one real collection, a token. */
    private Seeded seed(String prefix, List<String> permissions) {
        UUID brainId = UUID.randomUUID();
        String slug = prefix + "-brain";
        Brain brain = new Brain(brainId, slug, "DM IT " + prefix);
        brain.setActive(true);
        brains.save(brain);

        var collection = collections.create(brainId, prefix + "-guidelines",
                "Guidelines", prefix + "-collection-key");

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

        String token = "rb_conn_" + prefix;
        BrainConnectorClient connector = new BrainConnectorClient(UUID.randomUUID(),
                "DM " + prefix, "SERVER", ConnectorAuthService.hashToken(token));
        connector.setBrainId(brainId);
        connector.setScopes(List.of(ConnectorScope.INSTANCE_RUN, ConnectorScope.INSTANCE_RUN_READ));
        connector.setAllowedTenants(List.of("tenant-a", "tenant-b"));
        connector.setGrantedPermissions(permissions);
        connectors.save(connector);

        return new Seeded(brainId, slug, connector.getId(), token);
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

    private static String startBody(String tenant) {
        return """
                {"tenantId": "%s", "externalRequestId": "req-77",
                 "packageId": "%s", "revision": 4,
                 "selectedSourceIds": ["%s"]}
                """.formatted(tenant, PACKAGE, SOURCE);
    }
}
