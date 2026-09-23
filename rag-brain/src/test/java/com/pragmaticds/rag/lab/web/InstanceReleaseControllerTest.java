package com.pragmaticds.rag.lab.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService;
import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.EvaluationResult;
import com.pragmaticds.rag.lab.instance.InstanceCandidateService;
import com.pragmaticds.rag.lab.instance.InstanceCandidateService.CandidateRelease;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.ConstraintResult;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.CreateInstanceCommand;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.Violation;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.WizardValidationScope;
import com.pragmaticds.rag.lab.instance.InstancePromotionService;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PointerState;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PromotionCommand;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PromotionDecision;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for authoring, evaluating, and shipping releases.
 *
 * <p><b>A manifest goes in; nothing about it comes back out.</b> Creating a candidate accepts the
 * complete definition — prompts included — and answers with identifiers and digests. A validation
 * error that quoted a prompt would put configured content into a log line, so a violation is a
 * section plus a code: enough for a wizard to mark the right field, and not enough to reveal what
 * was typed into it.
 *
 * <p><b>The caller's expectation is part of a pointer move's identity.</b> Promoting B believing A
 * is live is a different request from promoting B believing C is, and the same key cannot replay
 * one as the other.
 */
class InstanceReleaseControllerTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String KEY = "idempotency-key-1";
    private static final String INSTANCE = "income";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INSTANCE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RELEASE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PREDECESSOR = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVALUATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID COLLECTION = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String SCHEMA_SHA = "cd".repeat(32);
    private static final String MANIFEST_SHA = "ef".repeat(32);

    /** Configured content. None of it may appear in any response this controller writes. */
    private static final String SYSTEM_PROMPT =
            "You are an underwriter. Never disclose the seller concession threshold of 6%.";

    private InstanceCandidateService candidates;
    private InstanceEvaluationService evaluations;
    private InstancePromotionService promotions;
    private LabIdempotencyService idempotency;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        candidates = mock(InstanceCandidateService.class);
        evaluations = mock(InstanceEvaluationService.class);
        promotions = mock(InstancePromotionService.class);
        idempotency = mock(LabIdempotencyService.class);
        doAnswer(call -> call.<LabIdempotencyService.IdempotentCommand<Object>>getArgument(0)
                .action().get()).when(idempotency).execute(any());

        mvc = MockMvcBuilders
                .standaloneSetup(new InstanceReleaseController(
                        candidates, evaluations, promotions, idempotency))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new InstanceReleaseExceptionHandler())
                .build();
    }

    // ============================================================ gating

    @Test
    void everyRouteIsAdminGatedAndTheWholeSurfaceIsFeatureGated() throws Exception {
        for (String route : List.of(
                "/api/ai/admin/instances/validate",
                "/api/ai/admin/instances",
                "/api/ai/admin/instances/" + INSTANCE + "/candidates",
                "/api/ai/admin/instances/" + INSTANCE + "/releases/" + RELEASE + "/evaluate",
                "/api/ai/admin/instances/" + INSTANCE + "/releases/" + RELEASE + "/apply-to-live",
                "/api/ai/admin/instances/" + INSTANCE + "/releases/" + RELEASE + "/rollback")) {
            mvc.perform(post(route).param("brain", BRAIN.toString())
                            .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(candidates, evaluations, promotions, idempotency);

        ConditionalOnProperty gate =
                InstanceReleaseController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());
    }

    // ============================================================ validation

    @Test
    void validationWritesNothingAndSoTakesNoKey() throws Exception {
        when(candidates.validate(eq(WizardValidationScope.COMPLETE), any()))
                .thenReturn(new ConstraintResult(List.of()));

        mvc.perform(post("/api/ai/admin/instances/validate")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.violations.length()").value(0));

        // A wizard calls this per keystroke-ish; demanding a key would push clients into minting
        // one per call, which is worse than not having idempotency at all.
        verifyNoInteractions(idempotency);
        verify(candidates, never()).create(any());
    }

    @Test
    void aViolationNamesTheSectionAndACodeAndNeverTheValue() throws Exception {
        when(candidates.validate(any(), any())).thenReturn(new ConstraintResult(List.of(
                new Violation(WizardValidationScope.BEHAVIOR, "SYSTEM_PROMPT_REQUIRED"),
                new Violation(WizardValidationScope.LIMITS, "OUTPUT_CEILING_EXCEEDS_MODEL"))));

        MvcResult result = mvc.perform(post("/api/ai/admin/instances/validate")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.violations[0].section").value("BEHAVIOR"))
                .andExpect(jsonPath("$.violations[0].code").value("SYSTEM_PROMPT_REQUIRED"))
                .andExpect(jsonPath("$.violations[1].section").value("LIMITS"))
                .andReturn();

        // Enough for a wizard to mark the right field; not enough to echo what was typed in it.
        assertNoConfiguredContent(result);
    }

    @Test
    void aSectionScopeIsPassedThroughAndAnUnknownOneIsRejected() throws Exception {
        when(candidates.validate(any(), any())).thenReturn(new ConstraintResult(List.of()));

        mvc.perform(post("/api/ai/admin/instances/validate")
                        .param("brain", BRAIN.toString()).param("scope", "MODEL")
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isOk());
        verify(candidates).validate(eq(WizardValidationScope.MODEL), any());

        // Silently falling back to COMPLETE would answer a question the caller did not ask.
        mvc.perform(post("/api/ai/admin/instances/validate")
                        .param("brain", BRAIN.toString()).param("scope", "VIBES")
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RELEASE_REQUEST_INVALID"));
    }

    // ============================================================ create and candidates

    @Test
    void createAndCandidateBothRefuseWithoutAKeyBeforeReachingTheService() throws Exception {
        mvc.perform(post("/api/ai/admin/instances")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/candidates")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        verifyNoInteractions(candidates, idempotency);
    }

    @Test
    void aCreatedCandidateAnswersWithIdentifiersAndDigestsAndNothingFromTheManifest()
            throws Exception {
        when(candidates.create(any())).thenReturn(
                new CandidateRelease(INSTANCE_ID, RELEASE, 1, MANIFEST_SHA, null));

        MvcResult result = mvc.perform(post("/api/ai/admin/instances")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instanceId").value(INSTANCE_ID.toString()))
                .andExpect(jsonPath("$.releaseId").value(RELEASE.toString()))
                .andExpect(jsonPath("$.releaseNumber").value(1))
                .andExpect(jsonPath("$.manifestSha256").value(MANIFEST_SHA))
                // This surface has no path that writes PRODUCTION, and saying so is cheaper than
                // a reader having to know it.
                .andExpect(jsonPath("$.provenanceMode").value("CANDIDATE"))
                .andReturn();

        assertNoConfiguredContent(result);
    }

    @Test
    void aCandidateCarriesTheSlugFromThePathRatherThanTheBody() throws Exception {
        when(candidates.addCandidate(any())).thenReturn(
                new CandidateRelease(INSTANCE_ID, RELEASE, 2, MANIFEST_SHA, PREDECESSOR));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/candidates")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("some-other-instance")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.predecessorReleaseId").value(PREDECESSOR.toString()));

        ArgumentCaptor<CreateInstanceCommand> sent =
                ArgumentCaptor.forClass(CreateInstanceCommand.class);
        verify(candidates).addCandidate(sent.capture());
        // A body slug naming a different instance must not redirect the write.
        assertEquals(INSTANCE, sent.getValue().slug());
        assertEquals(BRAIN, sent.getValue().brainId());
    }

    @Test
    void anInvalidDefinitionIs422WithEveryViolationSoOneRoundOfFixesSuffices() throws Exception {
        when(candidates.create(any())).thenThrow(new InstanceCandidateService.CandidateException(
                InstanceCandidateService.CandidateException.Code.INSTANCE_DEFINITION_INVALID,
                List.of("BEHAVIOR:SYSTEM_PROMPT_REQUIRED", "LIMITS:OUTPUT_CEILING_EXCEEDS_MODEL")));

        MvcResult result = mvc.perform(post("/api/ai/admin/instances")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSTANCE_DEFINITION_INVALID"))
                .andExpect(jsonPath("$.blockingCodes.length()").value(2))
                .andReturn();

        assertNoConfiguredContent(result);
    }

    @Test
    void aDuplicateSlugIsAConflictAndAnUnknownInstanceIsNotFound() throws Exception {
        when(candidates.create(any())).thenThrow(new InstanceCandidateService.CandidateException(
                InstanceCandidateService.CandidateException.Code.INSTANCE_ALREADY_EXISTS));
        mvc.perform(post("/api/ai/admin/instances")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTANCE_ALREADY_EXISTS"));

        when(candidates.addCandidate(any())).thenThrow(
                new InstanceCandidateService.CandidateException(
                        InstanceCandidateService.CandidateException.Code.INSTANCE_NOT_FOUND));
        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/candidates")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INSTANCE_NOT_FOUND"));
    }

    // ============================================================ evaluation

    @Test
    void anEvaluationReportsItsVerdictByDigestAndNeverReturnsTheReport() throws Exception {
        when(evaluations.evaluate(BRAIN, INSTANCE, RELEASE)).thenReturn(new EvaluationResult(
                EVALUATION, RELEASE, "income-smoke", 1, new BigDecimal("0.9600"), true,
                "ab".repeat(32), 20, 19));

        MvcResult result = mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/evaluate")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluationId").value(EVALUATION.toString()))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.score").value(0.96))
                .andExpect(jsonPath("$.scenariosRun").value(20))
                .andExpect(jsonPath("$.scenariosPassed").value(19))
                .andExpect(jsonPath("$.reportSha256").value("ab".repeat(32)))
                .andReturn();

        // A report contains model output over fixtures. It is referenced, never returned.
        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("report\":\"") || json.contains("\"report\":{"),
                "the report body itself must not be on the wire");
    }

    @Test
    void replayingAnEvaluationKeyIsRefusedRatherThanReturningAStaleVerdictAsFresh()
            throws Exception {
        // An evaluation is a model run against fixtures. Re-running it would bill twice; handing
        // back the old verdict as though it were fresh would be worse than saying the key is spent.
        doAnswer(call -> call.<LabIdempotencyService.IdempotentCommand<Object>>getArgument(0)
                .replay().apply(new LabIdempotencyService.IdempotencyResult(
                        "INSTANCE_EVALUATION", EVALUATION, 1L)))
                .when(idempotency).execute(any());

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/evaluate")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_REQUEST_REPLAYED"));

        verifyNoInteractions(evaluations);
    }

    @Test
    void aFixtureNothingCanServeIsUnavailableRatherThanQuietlyPassing() throws Exception {
        // A gate that passed because it could not run would certify every release it never
        // checked. A deployment gap, not something the operator can correct from here.
        assertEvaluationRefusal(InstanceEvaluationService.EvaluationException.Code
                .EVALUATION_RUNNER_UNAVAILABLE, status().isServiceUnavailable());
    }

    @Test
    void aBrainOverItsDailyBudgetIsToldSoRatherThanThatTheServiceIsDown() throws Exception {
        // The brain's own quota, working exactly as configured. A 503 would tell an operator to
        // page someone about a service that is fine.
        assertEvaluationRefusal(InstanceEvaluationService.EvaluationException.Code
                .EVALUATION_BUDGET_EXHAUSTED, status().isTooManyRequests());
    }

    @Test
    void aReleasePinningCollectionsThisBrainLacksIsAConflictNotAnOutage() throws Exception {
        // Fixed by changing the release or the brain's corpus, never by retrying the request —
        // which is exactly what separates it from the two above.
        assertEvaluationRefusal(InstanceEvaluationService.EvaluationException.Code
                .EVALUATION_CORPUS_UNAVAILABLE, status().isConflict());
    }

    private void assertEvaluationRefusal(
            InstanceEvaluationService.EvaluationException.Code code,
            org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        when(evaluations.evaluate(any(), anyString(), any())).thenThrow(
                new InstanceEvaluationService.EvaluationException(code));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/evaluate")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY))
                .andExpect(expected)
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    // ============================================================ promotion

    @Test
    void thePromotionCheckMovesNothingAndNeedsNoKey() throws Exception {
        when(promotions.evaluateGate(any())).thenReturn(
                new PromotionDecision(false, List.of("EVALUATION_MISSING", "RELEASE_NOT_CANDIDATE")));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/promotion-check")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockingCodes.length()").value(2));

        verify(promotions, never()).promote(any());
        verify(promotions, never()).rollback(any());
        verifyNoInteractions(idempotency);
    }

    @Test
    void promotingCarriesBothTheExpectedReleaseAndTheExpectedPointerVersion() throws Exception {
        when(promotions.promote(any())).thenReturn(new PointerState(RELEASE, 8L));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/apply-to-live")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveReleaseId").value(RELEASE.toString()))
                .andExpect(jsonPath("$.pointerVersion").value(8));

        ArgumentCaptor<PromotionCommand> sent = ArgumentCaptor.forClass(PromotionCommand.class);
        verify(promotions).promote(sent.capture());
        // Release id alone is not enough: a promote/rollback/promote sequence returns the pointer
        // to the same release at a higher version, and a stale caller must not win that race.
        assertEquals(PREDECESSOR, sent.getValue().expectedLiveReleaseId());
        assertEquals(7L, sent.getValue().expectedPointerVersion());
        assertEquals(RELEASE, sent.getValue().candidateReleaseId());
        assertEquals(INSTANCE, sent.getValue().instanceSlug());
    }

    @Test
    void anOmittedPointerVersionFailsClosedRatherThanMatchingWhateverIsLive() throws Exception {
        when(promotions.promote(any())).thenReturn(new PointerState(RELEASE, 1L));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/apply-to-live")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"ops\",\"changeReason\":\"ship it\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<PromotionCommand> sent = ArgumentCaptor.forClass(PromotionCommand.class);
        verify(promotions).promote(sent.capture());
        // 0 asserts "I believe nothing has been promoted yet", which the compare-and-set refuses
        // against any live pointer. An omitted expectation must never behave like a wildcard.
        assertEquals(0L, sent.getValue().expectedPointerVersion());
    }

    @Test
    void aStalePointerViewIsAConflictAndABlockedGateIs422() throws Exception {
        when(promotions.promote(any())).thenThrow(new InstancePromotionService.PromotionException(
                InstancePromotionService.PromotionException.Code.LIVE_POINTER_CHANGED));
        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/apply-to-live")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(7L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIVE_POINTER_CHANGED"));

        when(promotions.rollback(any())).thenThrow(new InstancePromotionService.PromotionException(
                InstancePromotionService.PromotionException.Code.PROMOTION_BLOCKED,
                List.of("EVALUATION_MISSING")));
        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/rollback")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(7L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROMOTION_BLOCKED"))
                .andExpect(jsonPath("$.blockingCodes[0]").value("EVALUATION_MISSING"));
    }

    @Test
    void promotionBeingSwitchedOffIsForbiddenRatherThanNotFound() throws Exception {
        // The flag defaults off. A 404 would suggest the route does not exist and send an operator
        // hunting for a deployment problem instead of a setting.
        when(promotions.promote(any())).thenThrow(new InstancePromotionService.PromotionException(
                InstancePromotionService.PromotionException.Code.INSTANCE_PROMOTION_DISABLED));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/apply-to-live")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(7L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSTANCE_PROMOTION_DISABLED"));
    }

    @Test
    void rollbackTakesTheSameGateAndIsAddressedByItsOwnOperation() throws Exception {
        when(promotions.rollback(any())).thenReturn(new PointerState(PREDECESSOR, 9L));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/rollback")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveReleaseId").value(PREDECESSOR.toString()))
                .andExpect(jsonPath("$.pointerVersion").value(9));

        verify(promotions, never()).promote(any());
    }

    // ============================================================ idempotency identity

    @Test
    void theSameKeyCannotReplayAPromotionAsADifferentDecision() throws Exception {
        when(promotions.promote(any())).thenReturn(new PointerState(RELEASE, 8L));
        Set<String> hashes = new HashSet<>();
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LabIdempotencyService.IdempotentCommand> sent = captor();

        // Same key, same release, three different beliefs about what is live and why.
        move(moveBody(7L, PREDECESSOR, "ship it"));
        move(moveBody(8L, PREDECESSOR, "ship it"));
        move(moveBody(7L, RELEASE, "ship it"));
        move(moveBody(7L, PREDECESSOR, "revert the revert"));

        verify(idempotency, times(4)).execute(sent.capture());
        sent.getAllValues().forEach(command -> hashes.add(command.requestSha256()));

        // Four distinct decisions, so four distinct canonical requests: the key can replay any one
        // of them, and none of them as another.
        assertEquals(4, hashes.size());
        sent.getAllValues().forEach(command ->
                assertEquals("instance.promote", command.operation()));
    }

    @Test
    void promoteAndRollbackAreDifferentOperationsUnderTheSameKey() throws Exception {
        when(promotions.promote(any())).thenReturn(new PointerState(RELEASE, 8L));
        when(promotions.rollback(any())).thenReturn(new PointerState(PREDECESSOR, 9L));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LabIdempotencyService.IdempotentCommand> sent = captor();

        move(moveBody(7L));
        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/rollback")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(7L)))
                .andExpect(status().isOk());

        verify(idempotency, times(2)).execute(sent.capture());
        assertEquals("instance.promote", sent.getAllValues().get(0).operation());
        assertEquals("instance.rollback", sent.getAllValues().get(1).operation());
        assertNotEquals(sent.getAllValues().get(0).requestSha256(),
                sent.getAllValues().get(1).requestSha256());
    }

    @Test
    void aDifferentManifestUnderTheSameKeyIsADifferentRequest() throws Exception {
        when(candidates.create(any())).thenReturn(
                new CandidateRelease(INSTANCE_ID, RELEASE, 1, MANIFEST_SHA, null));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LabIdempotencyService.IdempotentCommand> sent = captor();

        create(createBody());
        create(createBody(INSTANCE, "a completely different system prompt"));

        verify(idempotency, times(2)).execute(sent.capture());
        assertNotEquals(sent.getAllValues().get(0).requestSha256(),
                sent.getAllValues().get(1).requestSha256(),
                "the definition is part of what the key is bound to");
    }

    // ============================================================ failures

    @Test
    void anUnexpectedFailureAnswersAStableCodeAndNeverTheCause() throws Exception {
        when(candidates.validate(any(), any()))
                .thenThrow(new IllegalStateException("prompt was: " + SYSTEM_PROMPT));

        MvcResult result = mvc.perform(post("/api/ai/admin/instances/validate")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RELEASE_REQUEST_FAILED"))
                .andReturn();

        // The reason the taxonomy exists: an exception message is an uncontrolled channel, and
        // this one is carrying a configured prompt.
        assertNoConfiguredContent(result);
    }

    @Test
    void anUnparseableBodyIsTheRoutesOwnInvalidCode() throws Exception {
        mvc.perform(post("/api/ai/admin/instances/validate")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RELEASE_REQUEST_INVALID"));

        mvc.perform(post("/api/ai/admin/instances/validate")
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RELEASE_REQUEST_INVALID"));
    }

    // ============================================================ helpers

    private void create(String body) throws Exception {
        mvc.perform(post("/api/ai/admin/instances")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void move(String body) throws Exception {
        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE
                        + "/releases/" + RELEASE + "/apply-to-live")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @SuppressWarnings("rawtypes")
    private static ArgumentCaptor<LabIdempotencyService.IdempotentCommand> captor() {
        return ArgumentCaptor.forClass(LabIdempotencyService.IdempotentCommand.class);
    }

    /** Nothing the caller configured may appear in a response, on any path. */
    private static void assertNoConfiguredContent(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("underwriter"), "a system prompt is not an error message");
        assertFalse(json.contains("seller concession"));
        assertFalse(json.contains("6%"));
        assertTrue(json.startsWith("{"), "the body is still a structured answer");
    }

    // ============================================================ fixtures

    private static String createBody() {
        return createBody(INSTANCE, SYSTEM_PROMPT);
    }

    private static String createBody(String slug) {
        return createBody(slug, SYSTEM_PROMPT);
    }

    private static String createBody(String slug, String systemPrompt) {
        try {
            return new ObjectMapper().writeValueAsString(
                    new InstanceReleaseDtos.CreateInstanceRequest(slug, "Income Analysis",
                            "Qualifying income from paystubs and W-2s", manifest(systemPrompt)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String moveBody(long expectedVersion) {
        return moveBody(expectedVersion, PREDECESSOR, "ship it");
    }

    private static String moveBody(long expectedVersion, UUID expectedLive, String reason) {
        return "{\"expectedLiveReleaseId\":\"" + expectedLive + "\","
                + "\"expectedPointerVersion\":" + expectedVersion + ","
                + "\"actorId\":\"ops\",\"changeReason\":\"" + reason + "\"}";
    }

    private static InstanceReleaseManifest manifest(String systemPrompt) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB", "W2"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(COLLECTION, 4))),
                new InstanceReleaseManifest.BehaviorContract(systemPrompt, "task", "query",
                        new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", SCHEMA_SHA),
                new InstanceReleaseManifest.LimitContract(100_000, 20_000, 8_000, 4_000, 2,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.9500")));
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
