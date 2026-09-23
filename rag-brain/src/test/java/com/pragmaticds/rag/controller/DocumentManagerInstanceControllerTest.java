package com.pragmaticds.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.lab.connect.DocumentManagerRunCommand;
import com.pragmaticds.rag.lab.connect.DocumentManagerRunService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupService;
import com.pragmaticds.rag.lab.run.RunGroupService.RunGroupException;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import com.pragmaticds.rag.lab.run.domain.LabConnectorRunGroupContext;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import com.pragmaticds.rag.lab.run.repository.LabConnectorRunGroupContextRepository;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import com.pragmaticds.rag.service.connect.ConnectorPermission;
import com.pragmaticds.rag.service.connect.ConnectorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for Document Manager runs.
 *
 * <p>Authorization details live in {@code ConnectorAuthServiceTest}; what this file proves is that
 * the controller asks for the right things — the run scope and the spend permission on POST, the
 * read pair on GET, the body's tenant on POST and the header's on GET — and that it refuses to
 * proceed when the strict auth throws, whatever the reason was.
 *
 * <p>The polling authorization is the part with teeth: owner and tenant must match the ownership
 * row, and every mismatch answers exactly like absence. A 403 would confirm to the wrong tenant
 * that the group exists, which for a loan-processing integration is itself a disclosure.
 */
class DocumentManagerInstanceControllerTest {

    private static final UUID BRAIN_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONNECTOR =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID GROUP =
            UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID RUN =
            UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID PACKAGE =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PRICING =
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

    private BrainRepository brains;
    private ConnectorAuthService auth;
    private DocumentManagerRunService runs;
    private LabRunGroupRepository groups;
    private LabRunRepository members;
    private LabModelUsageRepository usage;
    private LabConnectorRunGroupContextRepository contexts;
    private LabRunPayloadRepository payloads;
    private MockMvc mvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        brains = mock(BrainRepository.class);
        auth = mock(ConnectorAuthService.class);
        runs = mock(DocumentManagerRunService.class);
        groups = mock(LabRunGroupRepository.class);
        members = mock(LabRunRepository.class);
        usage = mock(LabModelUsageRepository.class);
        contexts = mock(LabConnectorRunGroupContextRepository.class);
        payloads = mock(LabRunPayloadRepository.class);
        ObjectProvider<LabPayloadCipher> cipher = mock(ObjectProvider.class);
        when(cipher.getIfAvailable()).thenReturn(null);
        ObjectProvider<com.pragmaticds.rag.lab.ops.InstanceControlMetrics> metricsProvider =
                mock(ObjectProvider.class);
        when(metricsProvider.getIfAvailable()).thenReturn(null);

        Brain brain = new Brain(BRAIN_ID, "mortgage", "Mortgage");
        brain.setActive(true);
        when(brains.findBySlug("mortgage")).thenReturn(Optional.of(brain));

        when(auth.requireForTenant(any(), anyString(), anyString(), any(), any(), any(), any(),
                anyString())).thenReturn(authorized("tenant-a"));
        when(runs.start(any(), anyString())).thenReturn(
                new RunGroupService.CreatedRunGroup(GROUP, true, List.of(RUN)));
        when(groups.findByIdAndBrainId(GROUP, BRAIN_ID)).thenReturn(Optional.of(group()));
        when(usage.findByRunIdInAndBrainId(any(), any())).thenReturn(List.of(usageRow()));
        when(members.findByRunGroupIdOrderByMemberIndexAsc(GROUP)).thenReturn(List.of(runRow()));
        when(contexts.findById(GROUP)).thenReturn(Optional.of(context(CONNECTOR, "tenant-a")));

        mvc = MockMvcBuilders
                .standaloneSetup(new DocumentManagerInstanceController(brains, auth, runs, groups,
                        members, usage, contexts, payloads, cipher, new ObjectMapper()))
                .setControllerAdvice(new DocumentManagerInstanceExceptionHandler(metricsProvider))
                .build();
    }

    private static ConnectorAuthService.AuthorizedConnector authorized(String tenant) {
        BrainConnectorClient client = new BrainConnectorClient(
                CONNECTOR, "DM", "SERVER", null);
        client.setBrainId(BRAIN_ID);
        return new ConnectorAuthService.AuthorizedConnector(client, tenant);
    }

    private static String body() {
        return """
                {"tenantId": "tenant-a", "externalRequestId": "req-77",
                 "packageId": "%s", "revision": 4,
                 "selectedSourceIds": ["aaaaaaaa-0000-4000-8000-000000000001"]}
                """.formatted(PACKAGE);
    }

    // ============================================================ launch

    @Test
    void launchAsksForTheRunScopeTheSpendPermissionAndTheBodysTenant() throws Exception {
        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted());

        verify(auth).requireForTenant(eq("Bearer rb_conn_x"),
                eq(ConnectorScope.INSTANCE_RUN), eq(ConnectorPermission.INSTANCE_RUN_LIVE),
                eq(BRAIN_ID), eq("tenant-a"), any(), any(), eq("INSTANCE_RUN"));
    }

    @Test
    void launchAnswers202WithThePollingLocationAndThePricedEstimates() throws Exception {
        // A freshly created group is QUEUED; the shared fixture is terminal because the poll
        // tests need a finished group. The controller reports the row's real status — a replay
        // may legitimately answer with a group that has already run.
        LabRunGroup queued = group();
        queued.setStatus(LabRunGroup.Status.QUEUED);
        when(groups.findByIdAndBrainId(GROUP, BRAIN_ID)).thenReturn(Optional.of(queued));

        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP))
                .andExpect(jsonPath("$.runGroupId").value(GROUP.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.statusUrl").value(
                        "/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP))
                .andExpect(jsonPath("$.memberIds[0]").value(RUN.toString()))
                // Ranges and their quality, priced before any provider was called. The response
                // never waits for completion; that is what the polling URL is for.
                .andExpect(jsonPath("$.estimates[0].inputTokensMin").value(1000))
                .andExpect(jsonPath("$.estimates[0].costUsdMax").value(0.40))
                .andExpect(jsonPath("$.estimates[0].estimateQuality").value("ESTIMATED_RANGE"));
    }

    @Test
    void launchHandsTheServiceTheAuthorizedTenantNotTheBodysSpelling() throws Exception {
        // The strict auth trims; the service must see the trimmed value the context row will
        // store, or replay comparisons diverge on whitespace.
        when(auth.requireForTenant(any(), anyString(), anyString(), any(), any(), any(), any(),
                anyString())).thenReturn(authorized("tenant-a"));

        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("\"tenant-a\"", "\"  tenant-a  \"")))
                .andExpect(status().isAccepted());

        ArgumentCaptor<DocumentManagerRunCommand> command =
                ArgumentCaptor.forClass(DocumentManagerRunCommand.class);
        verify(runs).start(command.capture(), eq("dm-key-1"));
        assertEquals("tenant-a", command.getValue().tenantId());
        assertEquals(CONNECTOR, command.getValue().connectorClientId());
        assertEquals(BRAIN_ID, command.getValue().brainId());
        assertEquals("income", command.getValue().instanceSlug());
    }

    @Test
    void launchRequiresAnIdempotencyKeyButOnlyAfterAuthorization() throws Exception {
        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_REQUEST_INVALID"));

        // Auth ran first: an unauthenticated caller learns nothing about what headers the
        // authenticated protocol wants.
        verify(auth).requireForTenant(any(), anyString(), anyString(), any(), any(), any(), any(),
                anyString());
        verify(runs, never()).start(any(), anyString());
    }

    @Test
    void launchRefusesWhenTheStrictAuthRefuses() throws Exception {
        when(auth.requireForTenant(any(), anyString(), anyString(), any(), any(), any(), any(),
                anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Connector permission is required: INSTANCE_RUN_LIVE"));

        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());

        verify(runs, never()).start(any(), anyString());
    }

    @Test
    void launchAnswers404ForAnUnknownOrInactiveBrainSlug() throws Exception {
        Brain inactive = new Brain(UUID.randomUUID(), "retired", "Retired");
        inactive.setActive(false);
        when(brains.findBySlug("retired")).thenReturn(Optional.of(inactive));

        mvc.perform(post("/api/connect/v1/brains/unknown/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/connect/v1/brains/retired/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound());
    }

    // ============================================================ safe errors

    @Test
    void aBlockedSubmissionAnswers422WithItsBlockingCodesAndNothingElse() throws Exception {
        when(runs.start(any(), anyString())).thenThrow(new RunGroupException(
                RunGroupException.Code.RUN_GROUP_BLOCKED,
                List.of("GROUP_EXCEEDS_DAILY_BUDGET")));

        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_BLOCKED"))
                .andExpect(jsonPath("$.blockingCodes[0]").value("GROUP_EXCEEDS_DAILY_BUDGET"));
    }

    @Test
    void aConflictingReplayAnswers409() throws Exception {
        when(runs.start(any(), anyString())).thenThrow(new RunGroupException(
                RunGroupException.Code.IDEMPOTENCY_KEY_REUSED));

        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void aStaleReleaseCollectionAnswers409AsACodeOnly() throws Exception {
        when(runs.start(any(), anyString())).thenThrow(
                new CorpusSnapshotService.SnapshotException(
                        CorpusSnapshotService.SnapshotException.Code.COLLECTION_VERSION_CONFLICT,
                        9L));

        mvc.perform(post("/api/connect/v1/brains/mortgage/instances/income/run-groups")
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("Idempotency-Key", "dm-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_VERSION_CONFLICT"));
    }

    // ============================================================ polling

    @Test
    void pollAsksForTheReadPairAndTheHeadersTenant() throws Exception {
        mvc.perform(get("/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP)
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isOk());

        verify(auth).requireForTenant(eq("Bearer rb_conn_x"),
                eq(ConnectorScope.INSTANCE_RUN_READ), eq(ConnectorPermission.INSTANCE_RUN_READ),
                eq(BRAIN_ID), eq("tenant-a"), any(), any(), eq("INSTANCE_RUN_READ"));
    }

    @Test
    void pollReturnsStatusesEstimatesActualsAndLatencyWithQualityLabels() throws Exception {
        mvc.perform(get("/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP)
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runGroupId").value(GROUP.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.members[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.members[0].expectedInputMin").value(1000))
                .andExpect(jsonPath("$.members[0].actualInputTokens").value(1200))
                // Unreported stays null on the wire; a zero here would be a measured absence
                // the provider never measured.
                .andExpect(jsonPath("$.members[0].actualCachedTokens").doesNotExist())
                .andExpect(jsonPath("$.members[0].usageQuality").value("REPORTED"))
                .andExpect(jsonPath("$.members[0].pricingVersionId").value(PRICING.toString()))
                .andExpect(jsonPath("$.members[0].latencyMillis").value(2000));
    }

    @Test
    void pollAnswers404ForAnotherConnectorsGroup() throws Exception {
        when(contexts.findById(GROUP))
                .thenReturn(Optional.of(context(UUID.randomUUID(), "tenant-a")));

        mvc.perform(get("/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP)
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pollAnswers404ForAnotherTenantOnTheSameConnector() throws Exception {
        // tenant-b may be in the token's allow-list and pass auth; the group is still not its to
        // read, and the refusal looks exactly like absence.
        when(auth.requireForTenant(any(), anyString(), anyString(), any(), any(), any(), any(),
                anyString())).thenReturn(authorized("tenant-b"));

        mvc.perform(get("/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP)
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pollAnswers404ForAnAdminGroupThatHasNoOwnershipRow() throws Exception {
        when(contexts.findById(GROUP)).thenReturn(Optional.empty());

        mvc.perform(get("/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP)
                        .header("Authorization", "Bearer rb_conn_x")
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pollNeverLeaksPromptsChunksOrProviderText() throws Exception {
        String responseBody = mvc.perform(
                        get("/api/connect/v1/brains/mortgage/instance-run-groups/" + GROUP)
                                .header("Authorization", "Bearer rb_conn_x")
                                .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The poll response is statuses, codes, numbers and the decrypted result. Anything
        // prompt-shaped or chunk-shaped in it would be a tenant-facing disclosure.
        for (String forbidden : List.of("systemPrompt", "taskPrompt", "chunk", "provider_error",
                "nonce", "ciphertext")) {
            assertFalse(responseBody.contains(forbidden),
                    "poll response must not carry " + forbidden);
        }
    }

    // ============================================================ fixtures

    private static LabRunGroup group() {
        LabRunGroup group = new LabRunGroup();
        ReflectionTestUtils.setField(group, "id", GROUP);
        group.setBrainId(BRAIN_ID);
        group.setMode(LabRunGroup.Mode.INDEPENDENT);
        group.setIdempotencyKey("dm-key-1");
        group.setRequestSha256("f".repeat(64));
        group.setStatus(LabRunGroup.Status.SUCCEEDED);
        return group;
    }

    private static LabConnectorRunGroupContext context(UUID connectorId, String tenant) {
        return new LabConnectorRunGroupContext(GROUP, connectorId, BRAIN_ID, tenant,
                "req-77", "e".repeat(64));
    }

    private static LabRun runRow() {
        LabRun run = new LabRun();
        run.setId(RUN);
        run.setBrainId(BRAIN_ID);
        run.setInstanceSlug("income");
        run.setStatus(LabRun.Status.SUCCEEDED);
        run.setCreatedAt(java.time.OffsetDateTime.parse("2026-08-20T10:00:00Z"));
        run.setTerminalAt(java.time.OffsetDateTime.parse("2026-08-20T10:00:02Z"));
        run.setRequestedProvider("synthetic");
        run.setRequestedModel("synthetic-analyzer");
        run.setPricingVersionId(PRICING);
        return run;
    }

    private static LabModelUsage usageRow() {
        LabModelUsage row = new LabModelUsage(RUN, BRAIN_ID, PRICING, "synthetic",
                "synthetic-analyzer", 1000, 1400, 200, 400,
                new BigDecimal("0.10"), new BigDecimal("0.40"),
                EstimateQuality.ESTIMATED_RANGE);
        // Through the entity's own mutator: cached tokens deliberately unreported, and the
        // entity itself keeps them null rather than zero.
        row.report(UsageQuality.REPORTED, 1200L, null, 300L, 1500L, new BigDecimal("0.02"));
        return row;
    }
}
