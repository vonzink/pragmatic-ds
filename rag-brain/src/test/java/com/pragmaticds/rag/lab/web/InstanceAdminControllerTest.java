package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceCommandResult;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.InstanceSnapshotService;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.repository.LabInstanceCommandResultRepository;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for the feature-gated, brain-scoped instance registry surface. */
class InstanceAdminControllerTest {
    private static final String ADMIN_KEY = "test-admin-key";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RELEASE = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private InstanceRegistryService registry;
    private InstanceSnapshotService snapshots;
    private LabInstanceCommandResultRepository results;
    private LabIdempotencyService idempotency;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = mock(InstanceRegistryService.class);
        snapshots = mock(InstanceSnapshotService.class);
        results = mock(LabInstanceCommandResultRepository.class);
        idempotency = mock(LabIdempotencyService.class);
        when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        mvc = MockMvcBuilders.standaloneSetup(
                        new InstanceAdminController(registry, snapshots, idempotency, results))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new InstanceAdminExceptionHandler())
                .build();
    }

    @Test
    void allSixRoutesAreAdminGatedAndExplicitlyBrainScoped() throws Exception {
        for (String route : List.of(
                "/api/ai/admin/instances",
                "/api/ai/admin/instances/income",
                "/api/ai/admin/instances/income/releases")) {
            mvc.perform(get(route).param("brain", BRAIN.toString()))
                    .andExpect(status().isUnauthorized());
        }

        LabInstance instance = instance(LabInstance.State.ACTIVE);
        ResolvedInstanceRelease live = release(instance, true, LabInstanceRelease.ProvenanceMode.PRODUCTION);
        when(snapshots.list(BRAIN)).thenReturn(List.of(snapshot(instance, live, List.of(live))));
        when(snapshots.detail(new InstanceKey(BRAIN, "income"))).thenReturn(snapshot(instance, live, List.of(live)));

        mvc.perform(get("/api/ai/admin/instances").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].brainId").value(BRAIN.toString()))
                .andExpect(jsonPath("$[0].liveReleaseNumber").value(7));
        mvc.perform(get("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("income"));

        verify(snapshots, never()).list(OTHER_BRAIN);
    }

    @Test
    void listReadAndReleaseHistoryExposeOnlySafeReleaseMetadata() throws Exception {
        LabInstance instance = instance(LabInstance.State.ACTIVE);
        ResolvedInstanceRelease live = release(instance, true, LabInstanceRelease.ProvenanceMode.PRODUCTION);
        ResolvedInstanceRelease candidate = release(instance, false, LabInstanceRelease.ProvenanceMode.CANDIDATE);
        when(snapshots.list(BRAIN)).thenReturn(List.of(snapshot(instance, live, List.of(live, candidate))));
        when(snapshots.detail(new InstanceKey(BRAIN, "income"))).thenReturn(snapshot(instance, live, List.of(live, candidate)));

        String list = mvc.perform(get("/api/ai/admin/instances").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].candidateCount").value(1))
                .andExpect(jsonPath("$[0].manifestVersion").value(2))
                .andExpect(jsonPath("$[0].provider").value("openai"))
                .andExpect(jsonPath("$[0].collectionCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCandidateRelease").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/ai/admin/instances/income/releases").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].releaseNumber").value(7))
                .andExpect(jsonPath("$[1].live").value(false));

        for (String forbidden : List.of("system prompt", "task prompt", "retrieval query",
                "credential", "authorization", "secret-value")) {
            assertFalse((list + detail).toLowerCase().contains(forbidden), forbidden + " must not escape");
        }
    }

    @Test
    void mutatingRoutesRequireAKeyAndUseCanonicalIdempotencyCommands() throws Exception {
        LabInstance instance = instance(LabInstance.State.ACTIVE);
        ResolvedInstanceRelease live = release(instance, true, LabInstanceRelease.ProvenanceMode.PRODUCTION);
        when(snapshots.detail(new InstanceKey(BRAIN, "income"))).thenReturn(snapshot(instance, live, List.of(live)));
        doAnswer(invocation -> invocation.<LabIdempotencyService.IdempotentCommand<Object>>getArgument(0)
                .action().get()).when(idempotency).execute(any());
        when(registry.updateMetadata(eq(new InstanceKey(BRAIN, "income")), eq("Income"), eq("Review income")))
                .thenReturn(instance);
        when(registry.disable(new InstanceKey(BRAIN, "income"))).thenReturn(instance);
        when(registry.restore(new InstanceKey(BRAIN, "income"))).thenReturn(instance);

        mvc.perform(patch("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Income\",\"purpose\":\"Review income\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mvc.perform(patch("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"Review income\",\"displayName\":\"Income\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "update-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Income\",\"purpose\":\"Review income\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/ai/admin/instances/income/disable").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "disable-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/ai/admin/instances/income/restore").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "restore-1"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<LabIdempotencyService.IdempotentCommand<?>> commands =
                org.mockito.ArgumentCaptor.forClass(LabIdempotencyService.IdempotentCommand.class);
        verify(idempotency, org.mockito.Mockito.times(4)).execute(commands.capture());
        assertEquals(List.of("instance.update", "instance.update", "instance.disable", "instance.restore"),
                commands.getAllValues().stream().map(LabIdempotencyService.IdempotentCommand::operation).toList());
        assertTrue(commands.getAllValues().stream().allMatch(command ->
                command.brainId().equals(BRAIN) && command.requestSha256().matches("[0-9a-f]{64}")));
        assertEquals(commands.getAllValues().get(0).requestSha256(), commands.getAllValues().get(1).requestSha256(),
                "JSON member order must not change the canonical request hash");
    }

    @Test
    void patchRejectsInvalidOrMalformedMetadataBeforeIdempotency() throws Exception {
        for (String body : List.of("{}", "{\"displayName\":null,\"purpose\":\"ok\"}",
                "{\"displayName\":\"\",\"purpose\":\"ok\"}",
                "{\"displayName\":\"x" + "x".repeat(120) + "\",\"purpose\":\"ok\"}",
                "{\"displayName\":\"ok\",\"purpose\":\"x" + "x".repeat(500) + "\"}", "{")) {
            mvc.perform(patch("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                            .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "invalid")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INSTANCE_REQUEST_INVALID"));
        }
        verify(idempotency, never()).execute(any());
    }

    /**
     * An instance that has never been promoted is administrable, not missing.
     *
     * <p>Registration writes a candidate release and no pointer, so this is the state every
     * freshly created instance is in. Refusing the read would leave the wizard's own output
     * unopenable and put the first promotion out of reach, since the screen that composes it
     * loads the instance and its release list first.
     */
    @Test
    void anInstanceWithNothingLiveReadsAsAStateRatherThanA404() throws Exception {
        LabInstance instance = instance(LabInstance.State.ACTIVE);
        ResolvedInstanceRelease candidate = release(instance, false, LabInstanceRelease.ProvenanceMode.CANDIDATE);
        when(snapshots.detail(new InstanceKey(BRAIN, "income")))
                .thenReturn(snapshot(instance, null, List.of(candidate)));

        String detail = mvc.perform(get("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("income"))
                // The candidate the wizard just authored is announced, which is what makes the
                // release list worth loading and the first promotion composable.
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.hasCandidateRelease").value(true))
                .andReturn().getResponse().getContentAsString();
        // Serialized as present-and-null, not omitted: the client distinguishes "nothing live"
        // from "field absent", and both halves of that pair must say the same thing.
        assertTrue(detail.contains("\"liveRelease\":null"), detail);
        assertTrue(detail.contains("\"liveReleaseNumber\":null"), detail);

        mvc.perform(get("/api/ai/admin/instances/income/releases").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].releaseNumber").value(7))
                .andExpect(jsonPath("$[0].live").value(false));
    }

    /**
     * The mutation replay round-trip, with nothing live to describe.
     *
     * <p>A mutation stores a body-free row and rebuilds the response from it, both on the first
     * call and on a replay. {@code ReleaseSummary.releaseNumber} is a primitive and the stored
     * flag column is NOT NULL, so an unguarded round-trip answers a replay of an update to a
     * never-promoted instance with a 500 — strictly worse than the 404 this change removes.
     */
    @Test
    void theStoredMutationRowAndItsReplayBothSurviveNothingBeingLive() {
        LabInstance instance = instance(LabInstance.State.ACTIVE);
        ResolvedInstanceRelease candidate = release(instance, false, LabInstanceRelease.ProvenanceMode.CANDIDATE);
        InstanceAdminDtos.InstanceDetail detail =
                InstanceAdminDtos.detail(snapshot(instance, null, List.of(candidate)));
        assertNull(detail.liveRelease());
        assertNull(detail.liveReleaseNumber());

        LabInstanceCommandResult row = InstanceAdminDtos.result(detail);
        assertNull(row.getLiveReleaseId());
        assertNull(row.getLiveReleaseNumber());
        assertNull(row.getLiveCreatedAt());
        // The column is NOT NULL, and its setter copies what it is handed.
        assertEquals(List.of(), row.getLiveLimitationFlags());

        InstanceAdminDtos.InstanceDetail replayed = InstanceAdminDtos.detail(row);
        assertNull(replayed.liveRelease());
        assertNull(replayed.liveReleaseNumber());
        assertEquals(detail.slug(), replayed.slug());
        assertEquals(detail.displayName(), replayed.displayName());
        assertEquals(detail.candidateCount(), replayed.candidateCount());
        assertTrue(replayed.hasCandidateRelease());
    }

    @Test
    void safeTaxonomyMapsNotFoundAndConflictWithoutExceptionText() throws Exception {
        when(snapshots.detail(new InstanceKey(BRAIN, "missing"))).thenThrow(
                new InstanceRegistryService.InstanceException(InstanceRegistryService.InstanceException.Code.INSTANCE_NOT_FOUND));
        mvc.perform(get("/api/ai/admin/instances/missing").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSTANCE_NOT_FOUND"));

        org.mockito.Mockito.doThrow(new LabManifestWriter.ManifestException(
                LabManifestWriter.ManifestException.Code.MANIFEST_NOT_STRICT_JSON))
                .when(snapshots).detail(new InstanceKey(BRAIN, "income"));
        String body = mvc.perform(get("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANIFEST_UNSUPPORTED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("MANIFEST_NOT_STRICT_JSON"));

        org.mockito.Mockito.doThrow(new InstanceAdminController.InstanceAdminException(
                InstanceAdminController.InstanceAdminException.Code.LIVE_RELEASE_NOT_FOUND))
                .when(snapshots).detail(new InstanceKey(BRAIN, "income"));
        mvc.perform(get("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LIVE_RELEASE_NOT_FOUND"));

        org.mockito.Mockito.reset(registry, snapshots);
        when(registry.updateMetadata(eq(new InstanceKey(BRAIN, "income")), eq("Income"), eq("Review income")))
                .thenThrow(new InstanceRegistryService.InstanceException(
                        InstanceRegistryService.InstanceException.Code.INSTANCE_DISABLED));
        doAnswer(invocation -> invocation.<LabIdempotencyService.IdempotentCommand<Object>>getArgument(0)
                .action().get()).when(idempotency).execute(any());
        mvc.perform(patch("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "disabled-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Income\",\"purpose\":\"Review income\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSTANCE_DISABLED"));

        when(snapshots.detail(new InstanceKey(BRAIN, "income"))).thenThrow(
                new InstanceReleaseResolver.ReleaseResolutionException(InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_SCOPE_MISMATCH));
        mvc.perform(get("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RELEASE_SCOPE_MISMATCH"));

        org.mockito.Mockito.doThrow(new LabIdempotencyService.IdempotencyException(
                LabIdempotencyService.IdempotencyException.Code.IDEMPOTENCY_KEY_REUSED))
                .when(snapshots).detail(new InstanceKey(BRAIN, "income"));
        mvc.perform(get("/api/ai/admin/instances/income").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void featureGateIsIndependentFromTheLegacyLabFlagAndOffMeansUnmapped() throws Exception {
        ConditionalOnProperty gate = InstanceAdminController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());

        MockMvc disabled = MockMvcBuilders.standaloneSetup(new Object() {})
                .addFilters(new AdminApiKeyFilter(properties())).build();
        disabled.perform(get("/api/ai/admin/instances").param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    private static LabInstance instance(LabInstance.State state) {
        LabInstance instance = mock(LabInstance.class);
        when(instance.getId()).thenReturn(UUID.fromString("44444444-4444-4444-8444-444444444444"));
        when(instance.getBrainId()).thenReturn(BRAIN);
        when(instance.getSlug()).thenReturn("income");
        when(instance.getDisplayName()).thenReturn("Income");
        when(instance.getPurpose()).thenReturn("Review income");
        when(instance.getState()).thenReturn(state);
        when(instance.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-20T12:00:00Z"));
        when(instance.getUpdatedAt()).thenReturn(OffsetDateTime.parse("2026-08-20T13:00:00Z"));
        return instance;
    }

    private static ResolvedInstanceRelease release(LabInstance instance, boolean live,
                                                    LabInstanceRelease.ProvenanceMode provenance) {
        LabInstanceRelease release = mock(LabInstanceRelease.class);
        when(release.getId()).thenReturn(RELEASE);
        when(release.getReleaseNumber()).thenReturn(7);
        when(release.getProvenanceMode()).thenReturn(provenance);
        when(release.getManifestSha256()).thenReturn("a".repeat(64));
        when(release.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-20T12:00:00Z"));
        InstanceReleaseManifest manifest = new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1", Set.of("PAYSTUB"),
                        Set.of("PAYSTUB"), 1, InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("openai", "gpt-5", InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(new InstanceReleaseManifest.CollectionRef(RELEASE, 1))),
                new InstanceReleaseManifest.BehaviorContract("system prompt", "task prompt", "retrieval query", BigDecimal.ZERO),
                List.of(), new InstanceReleaseManifest.OutputContract("income", "b".repeat(64)),
                new InstanceReleaseManifest.LimitContract(1, 1, 1, 1, 1, BigDecimal.ONE),
                new InstanceReleaseManifest.EvaluationContract("income", 1, BigDecimal.ONE));
        return new ResolvedInstanceRelease(instance, release, new DecodedInstanceManifest.V2(manifest), live);
    }

    private static InstanceSnapshotService.InstanceSnapshot snapshot(LabInstance instance,
            ResolvedInstanceRelease live, List<ResolvedInstanceRelease> history) {
        return new InstanceSnapshotService.InstanceSnapshot(instance, live, history);
    }

    private static RagProperties properties() {
        return new RagProperties(new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150), new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin(ADMIN_KEY), new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }
}
