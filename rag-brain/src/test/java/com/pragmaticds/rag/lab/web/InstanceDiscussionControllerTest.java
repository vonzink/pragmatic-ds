package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.service.InstanceDiscussionService;
import com.pragmaticds.rag.lab.service.InstanceDiscussionService.DiscussionException;
import com.pragmaticds.rag.lab.service.InstanceDiscussionService.DiscussionView;
import com.pragmaticds.rag.lab.service.InstanceDiscussionService.Turn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for run-pinned discussion.
 *
 * <p><b>There is deliberately no field here for an input the run was not already pinned to.</b> A
 * question needing a different package, revision, or release is a new run, not a turn — so the
 * request body carries a question and nothing else, and no amount of crafting one can redirect
 * what the turn is answered from.
 *
 * <p><b>Both routes are scoped by group and member.</b> A run id alone is not an access path.
 */
class InstanceDiscussionControllerTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String KEY = "idempotency-key-1";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GROUP = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final String QUESTION = "Which paystub drove the variable income figure?";
    private static final String ANSWER = "The 2026-01-31 paystub; the W-2 only corroborates it.";

    private InstanceDiscussionService discussions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        discussions = mock(InstanceDiscussionService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new InstanceDiscussionController(discussions))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new InstanceDiscussionExceptionHandler())
                .build();
    }

    // ============================================================ gating

    @Test
    void bothRoutesAreAdminGatedAndTheSurfaceIsFeatureGated() throws Exception {
        mvc.perform(get(route()).param("brain", BRAIN.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(route()).param("brain", BRAIN.toString())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(QUESTION)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(discussions);

        ConditionalOnProperty gate =
                InstanceDiscussionController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());
    }

    @Test
    void aMemberIsOnlyReachableThroughItsOwnGroup() throws Exception {
        // The route shape is the access control: a bare run id has no mapping at all, so it can
        // never resolve to another group's transcript. Asserting on "not a transcript" rather
        // than on one status keeps this about the property and not about which 4xx Spring picks.
        int status = mvc.perform(get("/api/ai/admin/instances/run-groups/members/"
                        + RUN + "/messages")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andReturn().getResponse().getStatus();

        assertNotEquals(200, status, "a bare run id must not resolve to a transcript");
        verifyNoInteractions(discussions);
    }

    // ============================================================ reading

    @Test
    void theTranscriptCarriesTheBodiesSoAnAnswerIsActuallyReadable() throws Exception {
        when(discussions.read(BRAIN, RUN)).thenReturn(view());

        mvc.perform(get(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(RUN.toString()))
                .andExpect(jsonPath("$.turns[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.turns[0].question").value(QUESTION))
                .andExpect(jsonPath("$.turns[0].answer").value(ANSWER))
                .andExpect(jsonPath("$.turns[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("claude-opus-5"))
                .andExpect(jsonPath("$.usageQuality").value("REPORTED"))
                .andExpect(jsonPath("$.estimatedCostUsd").value(0.0125));
    }

    @Test
    void aFailedTurnShowsItsCodeAndNoBodies() throws Exception {
        when(discussions.read(BRAIN, RUN)).thenReturn(new DiscussionView(RUN,
                List.of(new Turn(1, null, null, "FAILED", "DISCUSSION_PROVIDER_FAILED")),
                "anthropic", "claude-opus-5", null, null));

        mvc.perform(get(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].status").value("FAILED"))
                .andExpect(jsonPath("$.turns[0].failureCode").value("DISCUSSION_PROVIDER_FAILED"))
                .andExpect(jsonPath("$.turns[0].answer").doesNotExist())
                // Absent, not zero: no measured turn means there is no total to report.
                .andExpect(jsonPath("$.estimatedCostUsd").doesNotExist())
                .andExpect(jsonPath("$.usageQuality").doesNotExist());
    }

    // ============================================================ asking

    @Test
    void askRefusesWithoutAnIdempotencyKeyBeforeReachingTheService() throws Exception {
        mvc.perform(post(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(QUESTION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCUSSION_REQUEST_INVALID"));

        mvc.perform(post(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "  ")
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(QUESTION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCUSSION_REQUEST_INVALID"));

        // Without the key there is nothing serializing turns, so a retry would bill twice.
        verify(discussions, never()).ask(any(), any(), anyString(), anyString());
    }

    @Test
    void theRequestBodyCarriesAQuestionAndNothingThatCouldRepointTheTurn() throws Exception {
        when(discussions.ask(eq(BRAIN), eq(RUN), anyString(), eq(KEY))).thenReturn(view());

        // Every extra field a caller might hope redirects the turn. The DTO has no member for any
        // of them, so they are read and discarded rather than honoured.
        mvc.perform(post(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\","
                                + "\"releaseId\":\"" + UUID.randomUUID() + "\","
                                + "\"registrationId\":\"" + UUID.randomUUID() + "\","
                                + "\"corpusSnapshotId\":\"" + UUID.randomUUID() + "\","
                                + "\"model\":\"claude-haiku-4-5\"}"))
                .andExpect(status().isOk());

        // The service is handed the question and the key. Nothing else crossed the boundary.
        verify(discussions).ask(BRAIN, RUN, QUESTION, KEY);
    }

    @Test
    void anAbsentBodyIsAnInvalidRequestRatherThanANullQuestion() throws Exception {
        mvc.perform(post(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCUSSION_REQUEST_INVALID"));
        verifyNoInteractions(discussions);
    }

    // ============================================================ the refusal taxonomy

    @Test
    void aContextOverBudgetIs422BecauseTheTurnWasNotAnsweredAtAll() throws Exception {
        when(discussions.ask(any(), any(), anyString(), anyString())).thenThrow(
                new DiscussionException(DiscussionException.Code.DISCUSSION_CONTEXT_TOO_LARGE));

        // Not 200-with-a-shorter-answer: the caller has to know the turn was not answered from
        // less material, it was not answered.
        mvc.perform(post(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(QUESTION)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DISCUSSION_CONTEXT_TOO_LARGE"));
    }

    @Test
    void everyRefusalCodeMapsToItsOwnStatus() throws Exception {
        assertStatus(DiscussionException.Code.DISCUSSION_REQUEST_INVALID, 400);
        assertStatus(DiscussionException.Code.DISCUSSION_RUN_NOT_FOUND, 404);
        assertStatus(DiscussionException.Code.RUN_NOT_SUCCEEDED, 409);
        assertStatus(DiscussionException.Code.RUN_PROVENANCE_ABSENT, 409);
        assertStatus(DiscussionException.Code.RUN_OUTPUT_ABSENT, 409);
        assertStatus(DiscussionException.Code.INSTANCE_RELEASE_NOT_PINNABLE, 409);
        assertStatus(DiscussionException.Code.DISCUSSION_CONTEXT_TOO_LARGE, 422);
        assertStatus(DiscussionException.Code.DISCUSSION_PROVIDER_FAILED, 502);
    }

    @Test
    void anUnexpectedFailureAnswersAStableCodeAndNeverTheCause() throws Exception {
        when(discussions.read(any(), any())).thenThrow(new IllegalStateException(
                "AES/GCM tag mismatch decrypting run " + RUN + " question: " + QUESTION));

        MvcResult result = mvc.perform(get(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DISCUSSION_REQUEST_FAILED"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("AES/GCM"), "a cipher failure is not an error code");
        assertFalse(json.contains("paystub"), "and it must not carry the question either");
    }

    // ============================================================ helpers

    private void assertStatus(DiscussionException.Code code, int expected) throws Exception {
        when(discussions.read(any(), any())).thenThrow(new DiscussionException(code));
        mvc.perform(get(route()).param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.code").value(code.name()));
        // Re-stub for the next code rather than stacking answers on one mock.
        discussions = mock(InstanceDiscussionService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new InstanceDiscussionController(discussions))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new InstanceDiscussionExceptionHandler())
                .build();
    }

    private static String route() {
        return "/api/ai/admin/instances/run-groups/" + GROUP + "/members/" + RUN + "/messages";
    }

    private static String askBody(String question) {
        return "{\"question\":\"" + question + "\"}";
    }

    private static DiscussionView view() {
        return new DiscussionView(RUN,
                List.of(new Turn(1, QUESTION, ANSWER, "SUCCEEDED", null)),
                "anthropic", "claude-opus-5", "REPORTED", new BigDecimal("0.012500"));
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
