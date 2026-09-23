package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.ConstraintResult;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.Violation;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.WizardValidationScope;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PointerState;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PromotionCommand;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PromotionException;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.run.domain.LabInstancePointerEvent;
import com.pragmaticds.rag.lab.run.domain.LabReleaseEvaluation;
import com.pragmaticds.rag.lab.run.repository.LabInstancePointerEventRepository;
import com.pragmaticds.rag.lab.run.repository.LabReleaseEvaluationRepository;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The only path that changes what production answers with.
 *
 * <p>Two properties carry these tests. Compare-and-set means a caller working from a stale view is
 * told so rather than winning by arriving second, and the version matters independently of the
 * release id. And the gate re-runs every constraint at promotion time, because a release is
 * immutable but the corpus, credentials, and scenario sets around it are not.
 */
class InstancePromotionServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CANDIDATE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID LIVE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String INSTANCE = "income";
    private static final String ACTOR = "ops@example.com";
    private static final String REASON = "ship candidate 4";

    private InstanceRegistryService registry;
    private InstanceConstraintValidator validator;
    private LabInstanceReleaseRepository releases;
    private LabInstancePointerRepository pointers;
    private LabInstancePointerEventRepository pointerEvents;
    private LabReleaseEvaluationRepository evaluations;
    private LabAuditService audit;

    @BeforeEach
    void setUp() {
        registry = mock(InstanceRegistryService.class);
        validator = mock(InstanceConstraintValidator.class);
        releases = mock(LabInstanceReleaseRepository.class);
        pointers = mock(LabInstancePointerRepository.class);
        pointerEvents = mock(LabInstancePointerEventRepository.class);
        evaluations = mock(LabReleaseEvaluationRepository.class);
        audit = mock(LabAuditService.class);

        when(registry.require(any())).thenReturn(instance(LabInstance.State.ACTIVE));
        when(releases.findByIdAndBrainIdAndInstanceSlug(CANDIDATE, BRAIN, INSTANCE))
                .thenReturn(Optional.of(release()));
        when(validator.validate(eq(WizardValidationScope.COMPLETE), any()))
                .thenReturn(new ConstraintResult(List.of()));
        when(evaluations.findByReleaseIdAndScenarioSetIdAndScenarioSetVersion(
                CANDIDATE, "income-smoke", 1))
                .thenReturn(Optional.of(evaluation(true, new BigDecimal("1.0000"))));
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, INSTANCE)).thenReturn(Optional.empty());
        when(pointers.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    // ================================================================ the switch

    @Test
    void promotionAndRollbackRefuseWhileTheSwitchIsOffButTheGateStaysReadable() {
        InstancePromotionService service = service(false);

        for (var attempt : List.<org.junit.jupiter.api.function.Executable>of(
                () -> service.promote(firstPromotion()),
                () -> service.rollback(command(LIVE, 1)))) {
            PromotionException refused = assertThrows(PromotionException.class, attempt);
            assertEquals(PromotionException.Code.INSTANCE_PROMOTION_DISABLED, refused.code());
        }
        verifyNoInteractions(pointerEvents);
        verify(pointers, never()).saveAndFlush(any());

        // Knowing why a release cannot ship is useful even where shipping is not permitted.
        assertTrue(service.evaluateGate(firstPromotion()).allowed());
    }

    // ================================================================ compare-and-set

    @Test
    void aFirstPromotionInsertsThePointerAndAssertsNothingWasLive() {
        InstancePromotionService service = service(true);

        PointerState state = service.promote(firstPromotion());

        assertEquals(CANDIDATE, state.liveReleaseId());
        assertEquals(1L, state.pointerVersion());

        ArgumentCaptor<LabInstancePointer> pointer =
                ArgumentCaptor.forClass(LabInstancePointer.class);
        verify(pointers).saveAndFlush(pointer.capture());
        assertEquals(CANDIDATE, pointer.getValue().getProductionReleaseId());
        assertEquals(1L, pointer.getValue().getPointerVersion());

        // A caller who believed something was already live is working from a view that no longer
        // matches, which is the same failure as a stale version.
        PromotionException stale = assertThrows(PromotionException.class,
                () -> service(true).promote(command(LIVE, 0)));
        assertEquals(PromotionException.Code.LIVE_POINTER_CHANGED, stale.code());
    }

    @Test
    void aStaleReleaseOrAStaleVersionBothRefuseAndNeitherMovesThePointer() {
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, INSTANCE))
                .thenReturn(Optional.of(pointer(LIVE, 3)));
        InstancePromotionService service = service(true);

        // Right release, wrong version. Without the version, promote A, roll back to B, promote A
        // again would be indistinguishable from no change and this caller would wrongly win.
        assertEquals(PromotionException.Code.LIVE_POINTER_CHANGED,
                assertThrows(PromotionException.class,
                        () -> service.promote(command(LIVE, 2))).code());
        // Right version, wrong release.
        assertEquals(PromotionException.Code.LIVE_POINTER_CHANGED,
                assertThrows(PromotionException.class,
                        () -> service.promote(command(CANDIDATE, 3))).code());

        verify(pointers, never()).saveAndFlush(any());
        verifyNoInteractions(pointerEvents);
        verifyNoInteractions(audit);
    }

    @Test
    void aMatchingViewMovesThePointerAndAppendsExactlyOneEvent() {
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, INSTANCE))
                .thenReturn(Optional.of(pointer(LIVE, 3)));
        InstancePromotionService service = service(true);

        PointerState state = service.promote(command(LIVE, 3));

        assertEquals(4L, state.pointerVersion(), "the version increments so the next caller sees it");

        ArgumentCaptor<LabInstancePointerEvent> event =
                ArgumentCaptor.forClass(LabInstancePointerEvent.class);
        verify(pointerEvents).saveAndFlush(event.capture());
        assertEquals(LabInstancePointerEvent.Action.PROMOTE, event.getValue().getAction());
        assertEquals(LIVE, event.getValue().getFromReleaseId());
        assertEquals(CANDIDATE, event.getValue().getToReleaseId());
        assertEquals(4L, event.getValue().getPointerVersion());
        assertEquals(REASON, event.getValue().getChangeReason());
    }

    @Test
    void aRollbackTakesTheSameGateAndTheSameCompareAndSet() {
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, INSTANCE))
                .thenReturn(Optional.of(pointer(LIVE, 7)));
        InstancePromotionService service = service(true);

        PointerState state = service.rollback(command(LIVE, 7));

        ArgumentCaptor<LabInstancePointerEvent> event =
                ArgumentCaptor.forClass(LabInstancePointerEvent.class);
        verify(pointerEvents).saveAndFlush(event.capture());
        assertEquals(LabInstancePointerEvent.Action.ROLLBACK, event.getValue().getAction());
        assertEquals(8L, state.pointerVersion());

        // Rolling back points at what was already there; it never rewrites the target release.
        verify(releases, never()).saveAndFlush(any());
    }

    @Test
    void theAuditRowCarriesCountsAndTheReasonLivesOnlyOnTheImmutableEvent() {
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, INSTANCE))
                .thenReturn(Optional.of(pointer(LIVE, 3)));

        service(true).promote(command(LIVE, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> counts = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(BRAIN), eq(LabAuditService.RELEASE_POINTER_MOVED), any(), any(),
                eq(CANDIDATE), any(), counts.capture());
        assertEquals(Set.of("pointerVersion", "rollback"), counts.getValue().keySet());
        assertFalse(counts.getValue().toString().contains(REASON),
                "the reason is already on the pointer event and does not belong in a second place");
    }

    // ================================================================ the gate

    @Test
    void theGateRerunsEveryConstraintBecauseTheWorldMovesAroundAnImmutableRelease() {
        when(validator.validate(eq(WizardValidationScope.COMPLETE), any())).thenReturn(
                new ConstraintResult(List.of(
                        new Violation(WizardValidationScope.CORPUS, "CORPUS_COLLECTION_DISABLED"),
                        new Violation(WizardValidationScope.MODEL, "MODEL_NOT_CONFIGURED"))));
        InstancePromotionService service = service(true);

        // Every reason at once: fixing one blocker only to be shown the next is how a promotion
        // takes six attempts.
        var decision = service.evaluateGate(firstPromotion());
        assertFalse(decision.allowed());
        assertEquals(List.of("CORPUS_COLLECTION_DISABLED", "MODEL_NOT_CONFIGURED"),
                decision.blockingCodes());

        PromotionException blocked = assertThrows(PromotionException.class,
                () -> service.promote(firstPromotion()));
        assertEquals(PromotionException.Code.PROMOTION_BLOCKED, blocked.code());
        assertEquals(2, blocked.blockingCodes().size());
        verify(pointers, never()).saveAndFlush(any());
    }

    @Test
    void aDisabledInstanceCannotShipAnything() {
        when(registry.require(any())).thenReturn(instance(LabInstance.State.DISABLED));

        assertTrue(service(true).evaluateGate(firstPromotion()).blockingCodes()
                .contains("INSTANCE_DISABLED"));
    }

    @Test
    void aReleaseFromAnotherBrainOrInstanceIsReportedAbsentRatherThanForbidden() {
        when(releases.findByIdAndBrainIdAndInstanceSlug(any(), any(), anyString()))
                .thenReturn(Optional.empty());

        // An admin key for one brain must not be able to probe another brain's release ids.
        assertEquals(List.of("RELEASE_NOT_FOUND"),
                service(true).evaluateGate(firstPromotion()).blockingCodes());
    }

    @Test
    void anEvaluationMustExistForThisExactReleaseAndSetVersionAndMustHavePassed() {
        // Missing entirely.
        when(evaluations.findByReleaseIdAndScenarioSetIdAndScenarioSetVersion(any(), any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.empty());
        assertTrue(service(true).evaluateGate(firstPromotion()).blockingCodes()
                .contains("EVALUATION_MISSING"));

        // Present but failed.
        when(evaluations.findByReleaseIdAndScenarioSetIdAndScenarioSetVersion(any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.of(evaluation(false, new BigDecimal("1.0000"))));
        assertTrue(service(true).evaluateGate(firstPromotion()).blockingCodes()
                .contains("EVALUATION_FAILED"));

        // Passed, but under a minimum that was raised after the run.
        when(evaluations.findByReleaseIdAndScenarioSetIdAndScenarioSetVersion(any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.of(evaluation(true, new BigDecimal("0.5000"))));
        assertTrue(service(true).evaluateGate(firstPromotion()).blockingCodes()
                .contains("EVALUATION_BELOW_MINIMUM_SCORE"));
    }

    @Test
    void anActorOrReasonCarryingAControlCharacterIsRefusedBeforeAnythingIsWritten() {
        InstancePromotionService service = service(true);

        for (PromotionCommand bad : List.of(
                new PromotionCommand(BRAIN, INSTANCE, CANDIDATE, null, 0, "ops\nadmin", REASON),
                new PromotionCommand(BRAIN, INSTANCE, CANDIDATE, null, 0, ACTOR, "line\ninjected"),
                new PromotionCommand(BRAIN, INSTANCE, CANDIDATE, null, 0, ACTOR, "  "))) {
            // A newline in an audit export is a way to make it show a record that never happened.
            assertEquals(PromotionException.Code.PROMOTION_REQUEST_INVALID,
                    assertThrows(PromotionException.class, () -> service.promote(bad)).code());
        }
        verifyNoInteractions(pointerEvents);
    }

    // ================================================================ fixtures

    private InstancePromotionService service(boolean promotionEnabled) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        VerifiedInstanceReleaseReader manifests = mock(VerifiedInstanceReleaseReader.class);
        when(manifests.read(any(LabInstanceRelease.class)))
                .thenReturn(new DecodedInstanceManifest.V2(manifest()));
        return new DefaultInstancePromotionService(registry, validator, releases, pointers,
                pointerEvents, evaluations, manifests, audit, mock(InstanceControlMetrics.class),
                transactions, promotionEnabled);
    }

    private static PromotionCommand firstPromotion() {
        return new PromotionCommand(BRAIN, INSTANCE, CANDIDATE, null, 0, ACTOR, REASON);
    }

    private static PromotionCommand command(UUID expectedLive, long expectedVersion) {
        return new PromotionCommand(BRAIN, INSTANCE, CANDIDATE, expectedLive, expectedVersion,
                ACTOR, REASON);
    }

    private static LabInstancePointer pointer(UUID liveReleaseId, long version) {
        LabInstancePointer pointer = new LabInstancePointer();
        pointer.setBrainId(BRAIN);
        pointer.setInstanceSlug(INSTANCE);
        pointer.setProductionReleaseId(liveReleaseId);
        pointer.setPointerVersion(version);
        return pointer;
    }

    /**
     * A real instance, not a mock.
     *
     * <p>These helpers are called from inside {@code when(...).thenReturn(...)} arguments, which
     * Java evaluates while the outer stubbing is still open. A helper that stubbed its own mock
     * there would start a nested stubbing and Mockito would reject the whole statement. Building
     * the object outright sidesteps that, and the entity's own constructor is a better fixture
     * than three stubbed getters anyway.
     */
    private static LabInstance instance(LabInstance.State state) {
        LabInstance instance = new LabInstance(BRAIN, INSTANCE, "Income", "Analyze income.");
        instance.setState(state);
        return instance;
    }

    private static LabInstanceRelease release() {
        LabInstanceRelease release = new LabInstanceRelease();
        release.setId(CANDIDATE);
        release.setBrainId(BRAIN);
        release.setInstanceSlug(INSTANCE);
        release.setReleaseNumber(2);
        release.setProvenanceMode(LabInstanceRelease.ProvenanceMode.CANDIDATE);
        release.setManifest(Map.of());
        return release;
    }

    private static LabReleaseEvaluation evaluation(boolean passed, BigDecimal score) {
        return new LabReleaseEvaluation(BRAIN, CANDIDATE, "income-smoke", 1, score, passed,
                "ab".repeat(32));
    }

    private static InstanceReleaseManifest manifest() {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of()),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query",
                        new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.9500")));
    }
}
