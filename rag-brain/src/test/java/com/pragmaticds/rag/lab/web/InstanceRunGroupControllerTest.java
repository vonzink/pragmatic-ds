package com.pragmaticds.rag.lab.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand;
import com.pragmaticds.rag.lab.run.RunGroupPreflightService;
import com.pragmaticds.rag.lab.run.RunGroupService;
import com.pragmaticds.rag.lab.run.RunGroupStatusService;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.lab.service.InstanceRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for run groups.
 *
 * <p>Two properties carry most of the weight here. <b>A listing can never become a way to read
 * answers</b> — only the single-group detail route decrypts, and only for members that succeeded,
 * so no amount of enumerating groups yields a stored result. And <b>preflight writes nothing</b>,
 * which is why it alone takes no idempotency key: requiring one for a pure pricing call would be
 * theatre and would push clients into minting a key per keystroke.
 */
class InstanceRunGroupControllerTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String KEY = "idempotency-key-1";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GROUP = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RELEASE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID REGISTRATION = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID SNAPSHOT = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID PRICING = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID PAYLOAD = UUID.fromString("99999999-9999-4999-8999-999999999999");

    /** The kind of value a stored answer holds. It must not appear in any listing response. */
    private static final String ANSWER_JSON =
            "{\"reportMarkdown\":\"# Qualifying income $8,412.55\",\"citations\":[]}";

    private RunGroupPreflightService preflight;
    private RunGroupService groupService;
    private RunGroupStatusService statusService;
    private InstanceRetentionService retention;
    private InstanceModelCatalogService catalog;
    private LabRunGroupRepository groups;
    private LabRunRepository runs;
    private LabModelUsageRepository usage;
    private LabRunPayloadRepository payloads;
    private LabPayloadCipher cipher;
    private ObjectProvider<LabPayloadCipher> cipherProvider;
    private MockMvc mvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        preflight = mock(RunGroupPreflightService.class);
        groupService = mock(RunGroupService.class);
        statusService = mock(RunGroupStatusService.class);
        retention = mock(InstanceRetentionService.class);
        catalog = mock(InstanceModelCatalogService.class);
        groups = mock(LabRunGroupRepository.class);
        runs = mock(LabRunRepository.class);
        usage = mock(LabModelUsageRepository.class);
        payloads = mock(LabRunPayloadRepository.class);
        cipher = mock(LabPayloadCipher.class);
        cipherProvider = mock(ObjectProvider.class);
        when(cipherProvider.getIfAvailable()).thenReturn(cipher);

        mvc = MockMvcBuilders
                .standaloneSetup(new InstanceRunGroupController(preflight, groupService,
                        statusService, retention, catalog, groups, runs, usage, payloads,
                        cipherProvider, new ObjectMapper()))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new InstanceRunGroupExceptionHandler())
                .build();
    }

    // ============================================================ gating

    @Test
    void everyRouteIsAdminGatedAndTheWholeSurfaceIsFeatureGated() throws Exception {
        mvc.perform(get("/api/ai/admin/instances/run-groups").param("brain", BRAIN.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai/admin/instances/run-groups").param("brain", BRAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai/admin/instances/run-groups/" + GROUP + "/cancel")
                        .param("brain", BRAIN.toString()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(preflight, groupService, statusService, groups, runs, payloads);

        // Gated on instances alone, so with the flag absent these paths have no mapping at all
        // and an authenticated admin gets a bare 404 that reveals nothing about the flag.
        ConditionalOnProperty gate =
                InstanceRunGroupController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());
    }

    // ============================================================ preflight

    @Test
    void preflightPricesWithoutAKeyAndWithoutCreatingAnything() throws Exception {
        when(preflight.preflight(any())).thenReturn(estimate(List.of()));

        mvc.perform(post("/api/ai/admin/instances/run-groups/preflight")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptable").value(true))
                .andExpect(jsonPath("$.reservedMaximumUsd").value(0.04))
                .andExpect(jsonPath("$.members[0].costUsdMax").value(0.02));

        // No key was sent and none was demanded, because nothing was written.
        verifyNoInteractions(groupService);
        verify(groups, never()).saveAndFlush(any());
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void preflightCarriesTheBrainFromTheQueryRatherThanTheBody() throws Exception {
        when(preflight.preflight(any())).thenReturn(estimate(List.of()));

        mvc.perform(post("/api/ai/admin/instances/run-groups/preflight")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk());

        ArgumentCaptor<RunGroupCommand> sent = ArgumentCaptor.forClass(RunGroupCommand.class);
        verify(preflight).preflight(sent.capture());
        // There is no brain field in the request record, so a caller cannot name someone else's.
        assertEquals(BRAIN, sent.getValue().brainId());
        assertEquals(LabRunGroup.Mode.COMPARISON, sent.getValue().mode());
        assertEquals(LabRunGroup.ComparisonDimension.MODEL,
                sent.getValue().comparisonDimension());
        assertEquals(1, sent.getValue().members().size());
    }

    @Test
    void anUnrecognisedModeBecomesNullSoValidationReportsItRatherThanTheParserDoing() {
        RunGroupCommand command = InstanceRunGroupDtos.command(BRAIN,
                new InstanceRunGroupDtos.CreateRunGroupRequest("SIDEWAYS", "MOOD",
                        List.of(new InstanceRunGroupDtos.MemberRequest(
                                "income", RELEASE, REGISTRATION, SNAPSHOT))));

        // A 400 from Jackson would say "not a valid enum"; the service says which field is wrong.
        assertNull(command.mode());
        assertNull(command.comparisonDimension());
        assertEquals(1, command.members().size());
    }

    // ============================================================ create

    @Test
    void createRefusesWithoutAnIdempotencyKeyBeforeReachingTheService() throws Exception {
        mvc.perform(post("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mvc.perform(post("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        verifyNoInteractions(groupService);
    }

    @Test
    void aFreshGroupIs201AndAReplayIs200SoTheIdempotentCaseIsVisibleWithoutReadingTheBody()
            throws Exception {
        when(groupService.create(any(), anyString()))
                .thenReturn(new RunGroupService.CreatedRunGroup(GROUP, true, List.of(RUN_A, RUN_B)));

        mvc.perform(post("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(GROUP.toString()))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.memberRunIds.length()").value(2));

        when(groupService.create(any(), anyString()))
                .thenReturn(new RunGroupService.CreatedRunGroup(GROUP, false, List.of(RUN_A, RUN_B)));

        mvc.perform(post("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(GROUP.toString()))
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void aBlockedSubmissionIs422WithEveryReasonRatherThan400() throws Exception {
        when(groupService.create(any(), anyString())).thenThrow(
                new RunGroupService.RunGroupException(
                        RunGroupService.RunGroupException.Code.RUN_GROUP_BLOCKED,
                        List.of("BUDGET_EXCEEDED", "MODEL_NOT_IN_CATALOG")));

        // Not 400: the request was well-formed and the client could not have known it would be
        // refused, so the useful answer is the list of what to fix.
        mvc.perform(post("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_BLOCKED"))
                .andExpect(jsonPath("$.blockingCodes.length()").value(2));
    }

    @Test
    void aKeyBoundToADifferentSubmissionIsAConflict() throws Exception {
        when(groupService.create(any(), anyString())).thenThrow(
                new RunGroupService.RunGroupException(
                        RunGroupService.RunGroupException.Code.IDEMPOTENCY_KEY_REUSED));

        mvc.perform(post("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // ============================================================ listing

    @Test
    void aListingCarriesCountsAndStatusesAndNeverAResult() throws Exception {
        when(groups.findByBrainIdOrderByCreatedAtDesc(BRAIN)).thenReturn(List.of(group()));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(member(RUN_A, 0, LabRun.Status.SUCCEEDED),
                        member(RUN_B, 1, LabRun.Status.FAILED)));

        MvcResult listed = mvc.perform(get("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].groupId").value(GROUP.toString()))
                .andExpect(jsonPath("$[0].memberCount").value(2))
                .andExpect(jsonPath("$[0].status").value("PARTIAL"))
                .andReturn();

        // The load-bearing assertion for this surface: enumerating groups yields no answers, and
        // the listing never even reaches for a payload to decrypt.
        String json = listed.getResponse().getContentAsString();
        assertFalse(json.contains("result"), "a listing must carry no result field");
        assertFalse(json.contains("8,412.55"), "a listing must not carry an answer");
        verifyNoInteractions(payloads);
        verify(cipherProvider, never()).getIfAvailable();
    }

    @Test
    void aStatusFilterIsAppliedAndAnUnknownOneIsRejectedRatherThanIgnored() throws Exception {
        when(groups.findByBrainIdAndStatusOrderByCreatedAtDesc(BRAIN, LabRunGroup.Status.QUEUED))
                .thenReturn(List.of());

        mvc.perform(get("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).param("status", "QUEUED")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(groups).findByBrainIdAndStatusOrderByCreatedAtDesc(BRAIN, LabRunGroup.Status.QUEUED);

        // Silently ignoring it would answer with every group while the caller believed it was
        // looking at one status.
        mvc.perform(get("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).param("status", "ALMOST_DONE")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_REQUEST_INVALID"));
        verify(groups, never()).findByBrainIdOrderByCreatedAtDesc(BRAIN);
    }

    // ============================================================ detail

    @Test
    void detailDecryptsOnlyTheMembersThatSucceeded() throws Exception {
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group()));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(member(RUN_A, 0, LabRun.Status.SUCCEEDED),
                        member(RUN_B, 1, LabRun.Status.FAILED)));
        when(usage.findByRunIdInAndBrainId(List.of(RUN_A, RUN_B), BRAIN))
                .thenReturn(List.of(measured(RUN_A)));
        when(payloads.findByRunIdAndPayloadType(RUN_A, LabRunPayload.PayloadType.ANALYSIS_OUTPUT))
                .thenReturn(Optional.of(sealed()));
        when(cipher.open(any(), any(), any(), any(), any()))
                .thenReturn(ANSWER_JSON.getBytes(StandardCharsets.UTF_8));

        mvc.perform(get("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.memberCount").value(2))
                .andExpect(jsonPath("$.members[0].runId").value(RUN_A.toString()))
                .andExpect(jsonPath("$.members[0].result.reportMarkdown")
                        .value("# Qualifying income $8,412.55"))
                .andExpect(jsonPath("$.members[0].actualCostUsd").value(0.0125))
                .andExpect(jsonPath("$.members[0].usageQuality").value("REPORTED"))
                // A failed member has nothing to show and is never reached for.
                .andExpect(jsonPath("$.members[1].status").value("FAILED"))
                .andExpect(jsonPath("$.members[1].result").doesNotExist());

        verify(payloads, never())
                .findByRunIdAndPayloadType(RUN_B, LabRunPayload.PayloadType.ANALYSIS_OUTPUT);
    }

    @Test
    void aMemberWithNoUsageRowRendersAsNullsRatherThanZeros() throws Exception {
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group()));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(member(RUN_A, 0, LabRun.Status.QUEUED)));
        when(usage.findByRunIdInAndBrainId(List.of(RUN_A), BRAIN)).thenReturn(List.of());

        // "We do not know" must not render as "it was free"; that is the whole reason those are
        // separate states in the database.
        mvc.perform(get("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].actualCostUsd").doesNotExist())
                .andExpect(jsonPath("$.members[0].actualInputTokens").doesNotExist())
                .andExpect(jsonPath("$.members[0].usageQuality").doesNotExist());
    }

    @Test
    void anUnreadablePayloadCostsTheResultAndNotTheWholeResponse() throws Exception {
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group()));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(member(RUN_A, 0, LabRun.Status.SUCCEEDED)));
        when(usage.findByRunIdInAndBrainId(List.of(RUN_A), BRAIN)).thenReturn(List.of(measured(RUN_A)));
        when(payloads.findByRunIdAndPayloadType(RUN_A, LabRunPayload.PayloadType.ANALYSIS_OUTPUT))
                .thenReturn(Optional.of(sealed()));
        when(cipher.open(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("nonce mismatch on run " + RUN_A));

        // The member's status and cost are still worth showing, and the decryption failure's own
        // message carries the run's identifiers, so it is never surfaced.
        MvcResult result = mvc.perform(get("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.members[0].actualCostUsd").value(0.0125))
                .andExpect(jsonPath("$.members[0].result").doesNotExist())
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("nonce mismatch"));
    }

    @Test
    void withoutTheLabTheGroupStillReadsAndTheResultIsSimplyAbsent() throws Exception {
        // A deployment running instances without the Lab has no cipher bean. It must still list
        // and read groups; it simply cannot open a stored result.
        when(cipherProvider.getIfAvailable()).thenReturn(null);
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.of(group()));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(member(RUN_A, 0, LabRun.Status.SUCCEEDED)));
        when(usage.findByRunIdInAndBrainId(List.of(RUN_A), BRAIN)).thenReturn(List.of(measured(RUN_A)));

        mvc.perform(get("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.members[0].result").doesNotExist());

        verifyNoInteractions(payloads);
    }

    @Test
    void aGroupInAnotherBrainIsNotFoundRatherThanForbidden() throws Exception {
        when(groups.findByIdAndBrainId(GROUP, BRAIN)).thenReturn(Optional.empty());

        // 404, not 403: a 403 would confirm the group exists somewhere, which is itself a leak
        // across the brain boundary.
        mvc.perform(get("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_NOT_FOUND"));
    }

    // ============================================================ cancel

    @Test
    void cancelRequiresAKeyAndReportsWhatItCouldNotReach() throws Exception {
        mvc.perform(post("/api/ai/admin/instances/run-groups/" + GROUP + "/cancel")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(statusService);

        when(statusService.cancel(BRAIN, GROUP))
                .thenReturn(new RunGroupStatusService.CancellationOutcome(2, 1));

        // A member already talking to a provider cannot be un-called, so the honest answer names
        // it rather than reporting a clean cancellation.
        mvc.perform(post("/api/ai/admin/instances/run-groups/" + GROUP + "/cancel")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledMembers").value(2))
                .andExpect(jsonPath("$.stillProcessingMembers").value(1));
    }

    @Test
    void purgesATerminalGroupAndReportsCountsOnly() throws Exception {
        when(retention.purgeGroup(BRAIN, GROUP)).thenReturn(
                new InstanceRetentionService.GroupPurgeOutcome(
                        true, 2, 2, 2, 1, 3, 1, 2, true, 0));

        mvc.perform(delete("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.runsDeleted").value(2))
                .andExpect(jsonPath("$.connectorContextDeleted").value(true));
    }

    @Test
    void purgingAnActiveGroupAnswersConflictWithTheCodeOnly() throws Exception {
        when(retention.purgeGroup(BRAIN, GROUP)).thenThrow(
                new InstanceRetentionService.RetentionException(
                        InstanceRetentionService.RetentionException.Code.GROUP_STILL_ACTIVE));

        mvc.perform(delete("/api/ai/admin/instances/run-groups/" + GROUP)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_STILL_ACTIVE"));
    }

    @Test
    void cancellingAnAbsentGroupIsNotFound() throws Exception {
        when(statusService.cancel(BRAIN, GROUP)).thenThrow(new IllegalArgumentException(GROUP.toString()));

        mvc.perform(post("/api/ai/admin/instances/run-groups/" + GROUP + "/cancel")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_NOT_FOUND"));
    }

    // ============================================================ failures

    @Test
    void anUnexpectedFailureAnswersAStableCodeAndNeverTheCause() throws Exception {
        when(groups.findByBrainIdOrderByCreatedAtDesc(BRAIN))
                .thenThrow(new IllegalStateException(
                        "jdbc:postgresql://prod-db.internal:5432/rag?password=hunter2"));

        MvcResult result = mvc.perform(get("/api/ai/admin/instances/run-groups")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_REQUEST_FAILED"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("postgresql"), "a connection string is not an error code");
        assertFalse(json.contains("hunter2"));
        assertTrue(json.contains("RUN_GROUP_REQUEST_FAILED"));
    }

    @Test
    void anUnparseableBodyIsTheRoutesOwnInvalidCodeRatherThanAFrameworkError() throws Exception {
        mvc.perform(post("/api/ai/admin/instances/run-groups/preflight")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_REQUEST_INVALID"));

        mvc.perform(get("/api/ai/admin/instances/run-groups")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RUN_GROUP_REQUEST_INVALID"));
    }

    // ============================================================ fixtures

    private static String body() {
        return "{\"mode\":\"COMPARISON\",\"comparisonDimension\":\"MODEL\",\"members\":["
                + "{\"instanceSlug\":\"income\",\"releaseId\":\"" + RELEASE + "\","
                + "\"registrationId\":\"" + REGISTRATION + "\","
                + "\"corpusSnapshotId\":\"" + SNAPSHOT + "\"}]}";
    }

    private static RunGroupCommand.RunGroupPreflight estimate(List<String> blocking) {
        return new RunGroupCommand.RunGroupPreflight("a".repeat(64), "b".repeat(64),
                List.of(new RunGroupCommand.MemberPreflight(0, "income", RELEASE, REGISTRATION,
                        SNAPSHOT, "anthropic", "claude-opus-5", PRICING,
                        800, 1200, 100, 400,
                        new BigDecimal("0.008000"), new BigDecimal("0.020000"),
                        EstimateQuality.ESTIMATED_RANGE.name(), List.of())),
                new BigDecimal("0.040000"), new BigDecimal("1.000000"),
                new BigDecimal("0.000000"), new BigDecimal("25.000000"), true, blocking);
    }

    private static LabRunGroup group() {
        LabRunGroup group = new LabRunGroup();
        group.setId(GROUP);
        group.setBrainId(BRAIN);
        group.setMode(LabRunGroup.Mode.COMPARISON);
        group.setComparisonDimension(LabRunGroup.ComparisonDimension.MODEL);
        group.setIdempotencyKey(KEY);
        group.setRequestSha256("a".repeat(64));
        group.setStatus(LabRunGroup.Status.PARTIAL);
        group.setCreatedAt(OffsetDateTime.of(2026, 8, 20, 9, 0, 0, 0, ZoneOffset.UTC));
        return group;
    }

    private static LabRun member(UUID id, int index, LabRun.Status status) {
        LabRun run = new LabRun();
        run.setId(id);
        run.setBrainId(BRAIN);
        run.setInstanceSlug("income");
        run.setIdempotencyKey("group:" + GROUP + ":" + index);
        run.setReleaseId(RELEASE);
        run.setRegistrationId(REGISTRATION);
        run.setCorpusSnapshotId(SNAPSHOT);
        run.setRunGroupId(GROUP);
        run.setMemberIndex(index);
        run.setRequestedProvider("anthropic");
        run.setRequestedModel("claude-opus-5");
        run.setPricingVersionId(PRICING);
        run.setStatus(status);
        run.setCreatedAt(OffsetDateTime.of(2026, 8, 20, 9, 0, 0, 0, ZoneOffset.UTC));
        return run;
    }

    private static LabModelUsage measured(UUID runId) {
        LabModelUsage row = new LabModelUsage(runId, BRAIN, PRICING, "anthropic", "claude-opus-5",
                800, 1200, 100, 400, new BigDecimal("0.008000"), new BigDecimal("0.020000"),
                EstimateQuality.ESTIMATED_RANGE);
        row.report(UsageQuality.REPORTED, 900L, 300L, 120L, 1020L, new BigDecimal("0.012500"));
        return row;
    }

    private static LabRunPayload sealed() {
        LabRunPayload payload = new LabRunPayload();
        payload.setId(PAYLOAD);
        payload.setRunId(RUN_A);
        payload.setPayloadType(LabRunPayload.PayloadType.ANALYSIS_OUTPUT);
        payload.setNonce(new byte[12]);
        payload.setCiphertext(new byte[32]);
        return payload;
    }

    private static RagProperties properties() {
        return new RagProperties(new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin(ADMIN_KEY), new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }
}
