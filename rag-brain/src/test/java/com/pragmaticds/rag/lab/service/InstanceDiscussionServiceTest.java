package com.pragmaticds.rag.lab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabDiscussionMessage;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.model.InstanceCostEstimator;
import com.pragmaticds.rag.lab.model.InstanceTokenEstimator;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.domain.LabDiscussionModelUsage;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import com.pragmaticds.rag.lab.run.repository.LabDiscussionModelUsageRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A follow-up question is answered from one run's saved context, and from nothing else.
 *
 * <p>The claim these tests exist to defend is <b>zero re-execution</b>: no Document Engine call, no
 * compatibility check, no embedding, no retrieval, no deterministic tool, no snapshot freeze, no
 * re-analysis. A question about an answer must be answered from the material that produced it, or
 * it is a new run wearing a conversation's clothes — and it would drift silently as the corpus
 * changed underneath it.
 *
 * <p>The second claim is that the turn <b>refuses rather than trims</b>. An answer grounded in less
 * evidence than the run was, while still presenting itself as being about that run, is worse than
 * no answer.
 */
class InstanceDiscussionServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BRAIN = UUID.fromString("1a1a1a1a-1a1a-4a1a-8a1a-1a1a1a1a1a1a");
    private static final UUID RUN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RELEASE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EXCHANGE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PRICING = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PAYLOAD = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID PROVENANCE = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID COLLECTION = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID MESSAGE_Q = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID MESSAGE_A = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private static final String KEY = "idempotency-key-1";
    private static final String QUESTION = "Which paystub drove the variable income figure?";
    private static final String ANSWER = "The 2026-01-31 paystub; the W-2 only corroborates it.";
    private static final String ANSWER_JSON =
            "{\"reportMarkdown\":\"# Qualifying income $8,412.55\",\"citations\":[]}";
    private static final String EVIDENCE = "YTD gross through 2026-01-31: 8412.55";

    private LabRunRepository runs;
    private LabRunPayloadRepository payloads;
    private LabDiscussionExchangeRepository exchanges;
    private LabDiscussionMessageRepository messages;
    private LabDiscussionModelUsageRepository discussionUsage;
    private LabRunTransactionService transactions;
    private LabPayloadCipher cipher;
    private InstanceReleaseResolver releases;
    private ModelRouterService router;
    private InstanceCostEstimator estimator;
    private InstanceDiscussionService service;

    @BeforeEach
    void setUp() {
        runs = mock(LabRunRepository.class);
        payloads = mock(LabRunPayloadRepository.class);
        exchanges = mock(LabDiscussionExchangeRepository.class);
        messages = mock(LabDiscussionMessageRepository.class);
        discussionUsage = mock(LabDiscussionModelUsageRepository.class);
        transactions = mock(LabRunTransactionService.class);
        cipher = mock(LabPayloadCipher.class);
        releases = mock(InstanceReleaseResolver.class);
        router = mock(ModelRouterService.class);
        estimator = mock(InstanceCostEstimator.class);

        service = new InstanceDiscussionService(runs, payloads, exchanges, messages,
                discussionUsage, transactions, cipher, releases, router, estimator,
                new InstanceTokenEstimator(), json());

        when(runs.findById(RUN)).thenReturn(Optional.of(run(LabRun.Status.SUCCEEDED)));
        when(releases.byId(new InstanceKey(BRAIN, "income"), RELEASE))
                .thenReturn(release(manifest(4_000)));
        when(discussionUsage.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        stubSealedRun();
    }

    // ============================================================ the central claim

    @Test
    void aTurnTouchesNothingButWhatTheRunAlreadySaved() {
        stubFreshClaim();
        stubProvider(new AiResponse(ANSWER, "anthropic", "claude-opus-5", 900, 120, 300));
        when(estimator.priceActual(any(), anyString(), anyString(), any()))
                .thenReturn(new ModelEstimate.ActualCost(
                        new BigDecimal("0.012500"), 1020L, UsageQuality.REPORTED));
        stubTranscript();

        service.ask(BRAIN, RUN, QUESTION, KEY);

        // Everything the run consumed came from its own sealed provenance and output. Nothing here
        // re-reads a document, re-runs retrieval, re-freezes a snapshot, or re-analyzes.
        verify(payloads).findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.RUN_PROVENANCE);
        verify(payloads).findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.ANALYSIS_OUTPUT);
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void theTurnGoesToTheRunsOwnProviderAndModelWithFallbackRefused() {
        stubFreshClaim();
        stubProvider(new AiResponse(ANSWER, "anthropic", "claude-opus-5", 900, 120, 300));
        when(estimator.priceActual(any(), anyString(), anyString(), any()))
                .thenReturn(new ModelEstimate.ActualCost(
                        new BigDecimal("0.012500"), 1020L, UsageQuality.REPORTED));
        stubTranscript();

        service.ask(BRAIN, RUN, QUESTION, KEY);

        ArgumentCaptor<AiRequest> sent = ArgumentCaptor.forClass(AiRequest.class);
        ArgumentCaptor<ModelRouterService.FallbackPolicy> policy =
                ArgumentCaptor.forClass(ModelRouterService.FallbackPolicy.class);
        verify(router).generateSanitized(sent.capture(), eq(BRAIN), anyString(), policy.capture());

        // A substitution would let a turn be answered as something the run was never pinned to,
        // and the transcript would carry no sign of it.
        assertEquals(ModelRouterService.FallbackPolicy.NONE, policy.getValue());
        assertEquals("anthropic", sent.getValue().provider());
        assertEquals("claude-opus-5", sent.getValue().model());
        assertTrue(sent.getValue().hasProviderPair(),
                "a model without its provider would be ignored by the router");
        // The release's own output ceiling, not a default the discussion path invented.
        assertEquals(8_000, sent.getValue().maxTokens());
    }

    @Test
    void thePromptCarriesTheSavedAnswerAndEvidenceAndTheQuestionAndNothingElse() {
        stubFreshClaim();
        stubProvider(new AiResponse(ANSWER, "anthropic", "claude-opus-5", 900, 120, 300));
        when(estimator.priceActual(any(), anyString(), anyString(), any()))
                .thenReturn(new ModelEstimate.ActualCost(
                        new BigDecimal("0.012500"), 1020L, UsageQuality.REPORTED));
        stubTranscript();

        service.ask(BRAIN, RUN, QUESTION, KEY);

        ArgumentCaptor<AiRequest> sent = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generateSanitized(sent.capture(), any(), anyString(), any());
        String prompt = sent.getValue().prompt();

        assertTrue(prompt.contains(ANSWER_JSON), "the run's own answer is the ground");
        assertTrue(prompt.contains(EVIDENCE), "the run's own evidence is the ground");
        assertTrue(prompt.contains(QUESTION));
        assertTrue(prompt.contains("do not introduce new facts"));
        assertTrue(prompt.contains("You are an underwriter."), "the release's own system prompt");
        // No media: a discussion never re-reads a document, so there is nothing to attach.
        assertTrue(sent.getValue().media().isEmpty());
    }

    // ============================================================ refuse rather than trim

    @Test
    void aContextOverTheReleaseBudgetIsRefusedRatherThanTrimmed() {
        // A ceiling low enough that the saved answer plus its evidence cannot fit.
        when(releases.byId(any(), any())).thenReturn(release(manifest(4)));

        InstanceDiscussionService.DiscussionException refused =
                assertThrows(InstanceDiscussionService.DiscussionException.class,
                        () -> service.ask(BRAIN, RUN, QUESTION, KEY));

        assertEquals(InstanceDiscussionService.DiscussionException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                refused.code());
        // The refusal happens before anything is claimed or called: dropping evidence to fit would
        // ground the answer in less than the run was, while still claiming to be about that run.
        verifyNoInteractions(router);
        verify(transactions, never()).claimExchange(any(), any(), anyString(), any());
    }

    @Test
    void theBudgetCoversTheQuestionAndNotOnlyTheSavedContext() {
        // Sized so the saved context alone fits and the same context plus a long question does not.
        when(releases.byId(any(), any())).thenReturn(release(manifest(100)));
        stubFreshClaim();
        stubProvider(new AiResponse(ANSWER, "anthropic", "claude-opus-5", 900, 120, 300));
        when(estimator.priceActual(any(), anyString(), anyString(), any()))
                .thenReturn(new ModelEstimate.ActualCost(
                        new BigDecimal("0.012500"), 1020L, UsageQuality.REPORTED));
        stubTranscript();
        service.ask(BRAIN, RUN, "short?", KEY);

        // Checking only the context would let a long question push the turn past a ceiling the
        // release actually set.
        assertThrows(InstanceDiscussionService.DiscussionException.class,
                () -> service.ask(BRAIN, RUN, "why ".repeat(200), KEY));
    }

    // ============================================================ what may be discussed

    @Test
    void onlyASucceededRunHasAnAnswerToAskAbout() {
        for (LabRun.Status status : List.of(LabRun.Status.QUEUED, LabRun.Status.PROCESSING,
                LabRun.Status.FAILED, LabRun.Status.CANCELLED)) {
            when(runs.findById(RUN)).thenReturn(Optional.of(run(status)));
            InstanceDiscussionService.DiscussionException refused =
                    assertThrows(InstanceDiscussionService.DiscussionException.class,
                            () -> service.ask(BRAIN, RUN, QUESTION, KEY));
            assertEquals(InstanceDiscussionService.DiscussionException.Code.RUN_NOT_SUCCEEDED,
                    refused.code(), "status " + status);
        }
        verifyNoInteractions(router);
    }

    @Test
    void aRunInAnotherBrainIsNotFoundRatherThanForbidden() {
        // Not a 403-shaped answer: confirming the run exists somewhere is itself a leak across
        // the brain boundary.
        InstanceDiscussionService.DiscussionException refused =
                assertThrows(InstanceDiscussionService.DiscussionException.class,
                        () -> service.ask(OTHER_BRAIN, RUN, QUESTION, KEY));

        assertEquals(InstanceDiscussionService.DiscussionException.Code.DISCUSSION_RUN_NOT_FOUND,
                refused.code());
        verifyNoInteractions(router, payloads);
    }

    @Test
    void aRunThatPredatesPinnedProvenanceHasNoContextToAnswerFrom() {
        when(payloads.findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.RUN_PROVENANCE))
                .thenReturn(Optional.empty());

        assertEquals(InstanceDiscussionService.DiscussionException.Code.RUN_PROVENANCE_ABSENT,
                assertThrows(InstanceDiscussionService.DiscussionException.class,
                        () -> service.ask(BRAIN, RUN, QUESTION, KEY)).code());
        verifyNoInteractions(router);
    }

    @Test
    void aBlankQuestionOrKeyIsRefusedBeforeAnythingIsRead() {
        for (String[] input : List.of(new String[] {null, KEY}, new String[] {"  ", KEY},
                new String[] {QUESTION, null}, new String[] {QUESTION, "  "})) {
            assertEquals(InstanceDiscussionService.DiscussionException.Code
                            .DISCUSSION_REQUEST_INVALID,
                    assertThrows(InstanceDiscussionService.DiscussionException.class,
                            () -> service.ask(BRAIN, RUN, input[0], input[1])).code());
        }
        verifyNoInteractions(router, payloads, transactions);
    }

    // ============================================================ idempotency and failure

    @Test
    void theSameKeyReturnsTheExistingTurnWithoutASecondProviderCall() {
        LabDiscussionExchange existing = exchange(LabRun.Status.SUCCEEDED, null);
        when(transactions.claimExchange(eq(BRAIN), eq(RUN), eq(KEY), any()))
                .thenReturn(new LabRunTransactionService.ExchangeClaim(existing, false));
        stubTranscript();

        InstanceDiscussionService.DiscussionView view = service.ask(BRAIN, RUN, QUESTION, KEY);

        // Re-running would bill a second provider call for one question.
        verifyNoInteractions(router);
        verify(transactions, never()).completeExchange(any(), any(), any(), any(), any());
        assertEquals(1, view.turns().size());
    }

    @Test
    void aProviderFailureIsRecordedAsACodeAndNeverAsItsMessage() {
        stubFreshClaim();
        when(router.generateSanitized(any(), any(), anyString(), any())).thenThrow(
                new IllegalStateException(
                        "POST https://api.anthropic.com/v1/messages -> 400 {\"error\":\""
                                + QUESTION + "\"}"));

        InstanceDiscussionService.DiscussionException failed =
                assertThrows(InstanceDiscussionService.DiscussionException.class,
                        () -> service.ask(BRAIN, RUN, QUESTION, KEY));

        assertEquals(InstanceDiscussionService.DiscussionException.Code.DISCUSSION_PROVIDER_FAILED,
                failed.code());
        assertNull(failed.getCause(), "a provider cause carries the request URI and the body");
        assertFalse(failed.getMessage().contains("api.anthropic.com"));
        assertFalse(failed.getMessage().contains(QUESTION));

        // The exchange is marked failed with the same value-free code, and no body is stored.
        verify(transactions).failExchange(EXCHANGE, "DISCUSSION_PROVIDER_FAILED");
        verify(transactions, never()).completeExchange(any(), any(), any(), any(), any());
    }

    @Test
    void aRefusalMidTurnFailsTheExchangeWithItsOwnCode() {
        stubFreshClaim();
        // Nothing to price the turn against: the pinned catalog version no longer carries the
        // model. The turn must not silently become a different failure.
        when(router.generateSanitized(any(), any(), anyString(), any())).thenThrow(
                new InstanceDiscussionService.DiscussionException(
                        InstanceDiscussionService.DiscussionException.Code.RUN_OUTPUT_ABSENT));

        assertEquals(InstanceDiscussionService.DiscussionException.Code.RUN_OUTPUT_ABSENT,
                assertThrows(InstanceDiscussionService.DiscussionException.class,
                        () -> service.ask(BRAIN, RUN, QUESTION, KEY)).code());
        verify(transactions).failExchange(EXCHANGE, "RUN_OUTPUT_ABSENT");
    }

    // ============================================================ cost

    @Test
    void aTurnIsPricedAgainstTheRunsOwnCatalogVersion() {
        stubFreshClaim();
        stubProvider(new AiResponse(ANSWER, "anthropic", "claude-opus-5", 900, 120, 300));
        when(estimator.priceActual(any(), anyString(), anyString(), any()))
                .thenReturn(new ModelEstimate.ActualCost(
                        new BigDecimal("0.012500"), 1020L, UsageQuality.REPORTED));
        stubTranscript();

        service.ask(BRAIN, RUN, QUESTION, KEY);

        // Today's catalog would silently restate a finished turn's cost every time a rate moved.
        verify(estimator).priceActual(eq(PRICING), eq("anthropic"), eq("claude-opus-5"), any());

        ArgumentCaptor<LabDiscussionModelUsage> written =
                ArgumentCaptor.forClass(LabDiscussionModelUsage.class);
        verify(discussionUsage).saveAndFlush(written.capture());
        assertEquals(UsageQuality.REPORTED, written.getValue().getUsageQuality());
        assertEquals(new BigDecimal("0.012500"), written.getValue().getActualCostUsd());
    }

    @Test
    void aProviderThatReportedNothingLeavesTheTurnsCostUnavailableRatherThanZero() {
        stubFreshClaim();
        stubProvider(new AiResponse(ANSWER, "anthropic", "claude-opus-5", null, null, null));
        when(estimator.priceActual(any(), anyString(), anyString(), any()))
                .thenReturn(new ModelEstimate.ActualCost(null, null, UsageQuality.UNAVAILABLE));
        stubTranscript();

        service.ask(BRAIN, RUN, QUESTION, KEY);

        ArgumentCaptor<LabDiscussionModelUsage> written =
                ArgumentCaptor.forClass(LabDiscussionModelUsage.class);
        verify(discussionUsage).saveAndFlush(written.capture());
        assertEquals(UsageQuality.UNAVAILABLE, written.getValue().getUsageQuality());
        assertNull(written.getValue().getActualCostUsd(), "a zero would claim the turn was free");
        assertNull(written.getValue().getActualTotalTokens());
    }

    // ============================================================ reading the transcript

    @Test
    void theTranscriptCarriesTheBodiesSoAnAskActuallyHandsBackItsAnswer() {
        stubTranscript();
        when(discussionUsage.findByExchangeIdInAndBrainId(List.of(EXCHANGE), BRAIN))
                .thenReturn(List.of(measured()));

        InstanceDiscussionService.DiscussionView view = service.read(BRAIN, RUN);

        assertEquals(1, view.turns().size());
        assertEquals(QUESTION, view.turns().get(0).question());
        assertEquals(ANSWER, view.turns().get(0).answer());
        assertEquals("SUCCEEDED", view.turns().get(0).status());
        assertEquals("anthropic", view.provider());
        assertEquals("claude-opus-5", view.model());
        assertEquals(new BigDecimal("0.012500"), view.estimatedCostUsd());
        assertEquals("REPORTED", view.usageQuality());
    }

    @Test
    void aFailedTurnAppearsWithItsCodeAndNoBodies() {
        LabDiscussionExchange failed = exchange(LabRun.Status.FAILED, "DISCUSSION_PROVIDER_FAILED");
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(List.of(failed));
        // failExchange stores neither body, so there is nothing to open.
        when(messages.findByExchangeIdInOrderByOrdinalAsc(List.of(EXCHANGE)))
                .thenReturn(List.of());
        when(discussionUsage.findByExchangeIdInAndBrainId(List.of(EXCHANGE), BRAIN))
                .thenReturn(List.of());

        InstanceDiscussionService.DiscussionView view = service.read(BRAIN, RUN);

        // Visible rather than hidden: a caller needs to see the turn was attempted.
        assertEquals("FAILED", view.turns().get(0).status());
        assertEquals("DISCUSSION_PROVIDER_FAILED", view.turns().get(0).failureCode());
        assertNull(view.turns().get(0).question());
        assertNull(view.turns().get(0).answer());
        assertNull(view.estimatedCostUsd(), "no measured turn means no total, not a zero");
        assertNull(view.usageQuality());
    }

    @Test
    void anUnmeasuredTurnMakesTheWholeTotalReportAsUnavailable() {
        stubTranscript();
        LabDiscussionModelUsage unmeasured = new LabDiscussionModelUsage(EXCHANGE, BRAIN, PRICING,
                "anthropic", "claude-opus-5");
        unmeasured.unavailable();
        when(discussionUsage.findByExchangeIdInAndBrainId(List.of(EXCHANGE), BRAIN))
                .thenReturn(List.of(measured(), unmeasured));

        InstanceDiscussionService.DiscussionView view = service.read(BRAIN, RUN);

        // The sum omits the unmeasured turn, so it is an understatement and must say so rather
        // than presenting a partial figure as a complete one.
        assertEquals(new BigDecimal("0.012500"), view.estimatedCostUsd());
        assertEquals("UNAVAILABLE", view.usageQuality());
    }

    @Test
    void theServiceIsFeatureGatedOnInstancesAlone() {
        ConditionalOnProperty gate =
                InstanceDiscussionService.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());
    }

    // ============================================================ stubs

    private void stubFreshClaim() {
        when(transactions.claimExchange(eq(BRAIN), eq(RUN), eq(KEY), any()))
                .thenReturn(new LabRunTransactionService.ExchangeClaim(
                        exchange(LabRun.Status.PROCESSING, null), true));
    }

    private void stubProvider(AiResponse response) {
        when(router.generateSanitized(any(), any(), anyString(), any()))
                .thenReturn(new ModelRouterService.SanitizedResponse(response,
                        new ModelRouterService.Resolution("anthropic", "claude-opus-5", true,
                                "anthropic", "claude-opus-5", "anthropic", "claude-opus-5",
                                false)));
    }

    /** The run's two sealed payloads, opened by record type so a mix-up would fail the test. */
    private void stubSealedRun() {
        when(payloads.findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.RUN_PROVENANCE))
                .thenReturn(Optional.of(payload(PROVENANCE,
                        LabRunPayload.PayloadType.RUN_PROVENANCE)));
        when(payloads.findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.ANALYSIS_OUTPUT))
                .thenReturn(Optional.of(payload(PAYLOAD,
                        LabRunPayload.PayloadType.ANALYSIS_OUTPUT)));
        when(cipher.open(eq(BRAIN), eq(RUN), eq(PROVENANCE),
                eq(LabPayloadCipher.RecordType.RUN_PROVENANCE), any()))
                .thenReturn(provenanceJson());
        when(cipher.open(eq(BRAIN), eq(RUN), eq(PAYLOAD),
                eq(LabPayloadCipher.RecordType.ANALYSIS_OUTPUT), any()))
                .thenReturn(ANSWER_JSON.getBytes(StandardCharsets.UTF_8));
    }

    private void stubTranscript() {
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN))
                .thenReturn(List.of(exchange(LabRun.Status.SUCCEEDED, null)));
        when(messages.findByExchangeIdInOrderByOrdinalAsc(List.of(EXCHANGE)))
                .thenReturn(List.of(
                        message(MESSAGE_Q, LabDiscussionMessage.Role.USER, 1),
                        message(MESSAGE_A, LabDiscussionMessage.Role.ASSISTANT, 2)));
        when(cipher.open(eq(BRAIN), eq(RUN), eq(MESSAGE_Q),
                eq(LabPayloadCipher.RecordType.DISCUSSION_USER), any()))
                .thenReturn(QUESTION.getBytes(StandardCharsets.UTF_8));
        when(cipher.open(eq(BRAIN), eq(RUN), eq(MESSAGE_A),
                eq(LabPayloadCipher.RecordType.DISCUSSION_ASSISTANT), any()))
                .thenReturn(ANSWER.getBytes(StandardCharsets.UTF_8));
    }

    // ============================================================ fixtures

    /**
     * The mapper the app actually gets.
     *
     * <p>Provenance carries a {@link LocalDate}, so a bare {@code ObjectMapper} cannot read or
     * write it — Spring Boot's auto-configured one registers JSR-310, and a test that skipped it
     * would be exercising a serializer the deployment never uses.
     */
    private static ObjectMapper json() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private byte[] provenanceJson() {
        try {
            return json().writeValueAsBytes(new InstanceRunProvenance(
                    RUN, BRAIN, "income", RELEASE, "ab".repeat(32),
                    new InstanceRunProvenance.ParsedInputDescriptor(
                            UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                            "1.0.0", "cd".repeat(32), 2048L, "ef".repeat(32), List.of()),
                    UUID.randomUUID(), "12".repeat(32),
                    List.of(new InstanceRunProvenance.RetrievedEvidence(
                            UUID.randomUUID(), UUID.randomUUID(), "34".repeat(32),
                            "paystub.pdf", "January paystub",
                            LocalDate.of(2026, 1, 31), EVIDENCE)),
                    List.of(new InstanceRunProvenance.ExecutedToolRecord(
                            "income.average", "1.0.0", "56".repeat(32), "78".repeat(32),
                            "SUCCEEDED")),
                    new InstanceRunProvenance.ModelResolution("anthropic", "claude-opus-5",
                            "anthropic", "claude-opus-5", "anthropic", "claude-opus-5", false),
                    "9a".repeat(32), 4200L));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static LabRun run(LabRun.Status status) {
        LabRun run = new LabRun();
        run.setId(RUN);
        run.setBrainId(BRAIN);
        run.setInstanceSlug("income");
        run.setIdempotencyKey("run-key");
        run.setReleaseId(RELEASE);
        run.setRequestedProvider("anthropic");
        run.setRequestedModel("claude-opus-5");
        run.setPricingVersionId(PRICING);
        run.setStatus(status);
        return run;
    }

    private static LabDiscussionExchange exchange(LabRun.Status status, String failureCode) {
        LabDiscussionExchange exchange = new LabDiscussionExchange();
        exchange.setId(EXCHANGE);
        exchange.setRunId(RUN);
        exchange.setIdempotencyKey(KEY);
        exchange.setSequenceNumber(1);
        exchange.setUserOrdinal(1);
        exchange.setAssistantOrdinal(2);
        exchange.setStatus(status);
        exchange.setFailureCode(failureCode);
        return exchange;
    }

    private static LabDiscussionMessage message(UUID id, LabDiscussionMessage.Role role,
                                                int ordinal) {
        LabDiscussionMessage message = new LabDiscussionMessage();
        message.setId(id);
        message.setExchangeId(EXCHANGE);
        message.setRole(role);
        message.setOrdinal(ordinal);
        message.setNonce(new byte[12]);
        message.setCiphertext(new byte[48]);
        return message;
    }

    private static LabRunPayload payload(UUID id, LabRunPayload.PayloadType type) {
        LabRunPayload payload = new LabRunPayload();
        payload.setId(id);
        payload.setRunId(RUN);
        payload.setPayloadType(type);
        payload.setNonce(new byte[12]);
        payload.setCiphertext(new byte[64]);
        return payload;
    }

    private static LabDiscussionModelUsage measured() {
        LabDiscussionModelUsage row = new LabDiscussionModelUsage(EXCHANGE, BRAIN, PRICING,
                "anthropic", "claude-opus-5");
        row.report(UsageQuality.REPORTED, null, null, null, 1020L, new BigDecimal("0.012500"));
        return row;
    }

    private static ResolvedInstanceRelease release(InstanceReleaseManifest manifest) {
        LabInstance instance = new LabInstance(BRAIN, "income", "Income Analysis",
                "Qualifying income from paystubs and W-2s");
        LabInstanceRelease release = new LabInstanceRelease();
        release.setId(RELEASE);
        release.setBrainId(BRAIN);
        return new ResolvedInstanceRelease(instance, release,
                new DecodedInstanceManifest.V2(manifest), true);
    }

    private static InstanceReleaseManifest manifest(int maximumDiscussionTokens) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB", "W2"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(COLLECTION, 4))),
                new InstanceReleaseManifest.BehaviorContract(
                        "You are an underwriter.", "task", "query", new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", "cd".repeat(32)),
                new InstanceReleaseManifest.LimitContract(100_000, 20_000, 8_000,
                        maximumDiscussionTokens, 2, new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.9500")));
    }
}
