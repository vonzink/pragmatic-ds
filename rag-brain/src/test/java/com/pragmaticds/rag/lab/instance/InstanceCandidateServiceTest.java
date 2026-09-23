package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceCandidateService.CandidateRelease;
import com.pragmaticds.rag.lab.instance.InstanceCandidateService.CandidateException;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.ConstraintResult;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.CreateInstanceCommand;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.Violation;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.WizardValidationScope;
import com.pragmaticds.rag.lab.release.EncodedManifest;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authoring, and the two things it must never be able to do.
 *
 * <p>This service is the only writer of releases that an administrator can reach without the
 * promotion switch, which makes two properties load-bearing rather than incidental. It can only
 * ever write CANDIDATE — a deployment where nothing may go live must still let someone build and
 * evaluate — and it appends rather than edits, so the chain of what was proposed stays readable
 * even for versions that never shipped. Both are one line in the implementation and neither is
 * visible from the controller, which mocks this service away.
 *
 * <p>The refusals matter for a duller reason: a create is validated as a whole and reports every
 * blocker at once, because being shown one reason per attempt is how a create takes six attempts.
 */
class InstanceCandidateServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INSTANCE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RELEASE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PREVIOUS = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String SLUG = "income";
    private static final String DIGEST = "a".repeat(64);

    private InstanceConstraintValidator validator;
    private InstanceManifestCodec codec;
    private LabInstanceRepository instances;
    private LabInstanceReleaseRepository releases;
    private PlatformTransactionManager transactions;
    private LabInstance persisted;
    private InstanceCandidateService candidates;

    @BeforeEach
    void setUp() {
        validator = mock(InstanceConstraintValidator.class);
        codec = mock(InstanceManifestCodec.class);
        instances = mock(LabInstanceRepository.class);
        releases = mock(LabInstanceReleaseRepository.class);
        transactions = mock(PlatformTransactionManager.class);

        // A real instance carrying the id the database would have assigned, rather than a mock.
        // The id is the only thing this needs that a constructor cannot give it, and a mock built
        // and stubbed inside one of the when(...) arguments below would nest a stubbing inside an
        // open one — Mockito rejects that in @BeforeEach, so the whole class fails for one reason.
        persisted = new LabInstance(BRAIN, SLUG, "Income", "Analyze income.") {
            @Override
            public UUID getId() {
                return INSTANCE_ID;
            }
        };

        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(validator.validate(any(), any())).thenReturn(new ConstraintResult(List.of()));
        when(codec.encode(any())).thenReturn(new EncodedManifest(Map.of("version", 2), DIGEST));
        when(instances.findByBrainIdAndSlug(BRAIN, SLUG)).thenReturn(Optional.empty());
        when(instances.lockByBrainIdAndSlug(BRAIN, SLUG)).thenReturn(Optional.of(persisted));
        when(instances.saveAndFlush(any())).thenReturn(persisted);
        when(releases.findFirstByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(BRAIN, SLUG))
                .thenReturn(Optional.empty());
        when(releases.saveAndFlush(any())).thenAnswer(call -> {
            LabInstanceRelease saved = call.getArgument(0);
            saved.setId(RELEASE_ID);
            return saved;
        });

        candidates = new DefaultInstanceCandidateService(
                validator, codec, instances, releases, transactions);
    }

    // ============================================================ what it may write

    @Test
    void neitherCreatingNorEditingCanWriteAProductionRelease() {
        candidates.create(command());
        candidates.addCandidate(command());

        // The whole reason authoring and shipping are separate authorities. If this service could
        // write PRODUCTION, the promotion switch would guard a door with no wall around it.
        ArgumentCaptor<LabInstanceRelease> written =
                ArgumentCaptor.forClass(LabInstanceRelease.class);
        verify(releases, times(2)).saveAndFlush(written.capture());
        for (LabInstanceRelease release : written.getAllValues()) {
            assertEquals(LabInstanceRelease.ProvenanceMode.CANDIDATE, release.getProvenanceMode());
        }
    }

    @Test
    void theFirstCandidateIsReleaseOneWithNothingBehindIt() {
        CandidateRelease result = candidates.create(command());

        LabInstanceRelease release = written();
        assertEquals(1, result.releaseNumber());
        assertNull(result.predecessorReleaseId(), "the first release supersedes nothing");
        assertEquals(INSTANCE_ID, result.instanceId());
        assertEquals(RELEASE_ID, result.releaseId());
        assertEquals(1, release.getReleaseNumber());
        assertNull(release.getPredecessorReleaseId());
    }

    @Test
    void editingAppendsTheNextNumberAndPointsBackAtWhatItSupersedes() {
        when(releases.findFirstByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(BRAIN, SLUG))
                .thenReturn(Optional.of(existingRelease(3)));

        CandidateRelease result = candidates.addCandidate(command());

        // Appended, not edited: release 3 is still there and still says what it said.
        LabInstanceRelease release = written();
        assertEquals(4, result.releaseNumber());
        assertEquals(PREVIOUS, result.predecessorReleaseId());
        assertEquals(4, release.getReleaseNumber());
        assertEquals(PREVIOUS, release.getPredecessorReleaseId());
    }

    @Test
    void anInstanceWithNoReleasesYetStartsAtOne() {
        CandidateRelease result = candidates.addCandidate(command());

        assertEquals(1, result.releaseNumber());
        assertNull(result.predecessorReleaseId());
    }

    @Test
    void theStoredDigestIsTheCodecsRatherThanOneComputedHere() {
        candidates.create(command());

        // Every reader recomputes the manifest hash and compares. A digest produced any other way
        // than through the shared codec would make the release fail its own drift check.
        LabInstanceRelease release = written();
        assertEquals(DIGEST, release.getManifestSha256());
        assertEquals(Map.of("version", 2), release.getManifest());
        verify(codec).encode(any());
    }

    @Test
    void theInstanceIsWrittenWithTheDefinitionItWasGivenRatherThanADefaultedOne() {
        candidates.create(command());

        ArgumentCaptor<LabInstance> saved = ArgumentCaptor.forClass(LabInstance.class);
        verify(instances).saveAndFlush(saved.capture());
        assertEquals(BRAIN, saved.getValue().getBrainId());
        assertEquals(SLUG, saved.getValue().getSlug());
        assertEquals("Income", saved.getValue().getDisplayName());
        assertEquals("Analyze income.", saved.getValue().getPurpose());
    }

    // ============================================================ what it refuses

    @Test
    void anInvalidDefinitionIsRefusedWithEveryReasonAtOnceRatherThanTheFirst() {
        when(validator.validate(eq(WizardValidationScope.COMPLETE), any()))
                .thenReturn(new ConstraintResult(List.of(
                        new Violation(WizardValidationScope.MODEL, "MODEL_NOT_IN_CATALOG"),
                        new Violation(WizardValidationScope.CORPUS, "CORPUS_COLLECTION_MISSING"),
                        new Violation(WizardValidationScope.LIMITS, "BUDGET_NOT_POSITIVE"))));

        CandidateException refused =
                assertThrows(CandidateException.class, () -> candidates.create(command()));

        assertEquals(CandidateException.Code.INSTANCE_DEFINITION_INVALID, refused.code());
        assertEquals(List.of("MODEL_NOT_IN_CATALOG", "CORPUS_COLLECTION_MISSING",
                "BUDGET_NOT_POSITIVE"), refused.violations());
    }

    @Test
    void aDefinitionRefusedByTheGateNeverOpensATransaction() {
        when(validator.validate(eq(WizardValidationScope.COMPLETE), any()))
                .thenReturn(new ConstraintResult(
                        List.of(new Violation(WizardValidationScope.MODEL, "MODEL_NOT_IN_CATALOG"))));

        assertThrows(CandidateException.class, () -> candidates.create(command()));
        assertThrows(CandidateException.class, () -> candidates.addCandidate(command()));

        // Validating inside the transaction would open and roll back one per rejected form, which
        // is the common case while someone is filling the wizard in.
        verify(transactions, never()).getTransaction(any());
        verify(instances, never()).saveAndFlush(any());
        verify(releases, never()).saveAndFlush(any());
    }

    @Test
    void creatingASlugTheBrainAlreadyUsesWritesNoInstanceAndNoRelease() {
        when(instances.findByBrainIdAndSlug(BRAIN, SLUG)).thenReturn(Optional.of(persisted));

        CandidateException refused =
                assertThrows(CandidateException.class, () -> candidates.create(command()));

        assertEquals(CandidateException.Code.INSTANCE_ALREADY_EXISTS, refused.code());
        assertEquals(List.of(), refused.violations());
        verify(instances, never()).saveAndFlush(any());
        verify(releases, never()).saveAndFlush(any());
    }

    @Test
    void editingAnInstanceThisBrainDoesNotHaveCreatesNothing() {
        when(instances.lockByBrainIdAndSlug(BRAIN, SLUG)).thenReturn(Optional.empty());

        CandidateException refused =
                assertThrows(CandidateException.class, () -> candidates.addCandidate(command()));

        // An edit is not a create with a different name. Falling through to create here would let
        // a typo'd slug quietly bring a second instance into existence.
        assertEquals(CandidateException.Code.INSTANCE_NOT_FOUND, refused.code());
        verify(instances, never()).saveAndFlush(any());
        verify(releases, never()).saveAndFlush(any());
    }

    // ============================================================ how it reads

    @Test
    void anEditLocksTheInstanceRatherThanReadingIt() {
        candidates.addCandidate(command());

        // Two administrators saving at the same moment would otherwise both compute the same next
        // release number, and one insert would lose to the uniqueness constraint rather than
        // simply queueing behind the other.
        verify(instances).lockByBrainIdAndSlug(BRAIN, SLUG);
        verify(instances, never()).findByBrainIdAndSlug(any(), any());
    }

    @Test
    void aCreateIsValidatedAsAWholeRatherThanAsTheStepInFrontOfSomeone() {
        candidates.create(command());

        // There is no server-side draft, so a create carries the complete definition or none.
        verify(validator).validate(eq(WizardValidationScope.COMPLETE), any());
    }

    @Test
    void aPerStepValidationIsPassedThroughAsTheStepItAskedAbout() {
        candidates.validate(WizardValidationScope.MODEL, command());

        // The wizard's per-step check and its whole-form check are the same call, so quietly
        // upgrading the scope here would make step four report step six's blockers.
        verify(validator).validate(eq(WizardValidationScope.MODEL), any());
    }

    @Test
    void validatingWritesNothing() {
        candidates.validate(WizardValidationScope.COMPLETE, command());

        verify(transactions, never()).getTransaction(any());
        verify(instances, never()).saveAndFlush(any());
        verify(releases, never()).saveAndFlush(any());
    }

    // ============================================================ fixtures

    /** The release this service just wrote. */
    private LabInstanceRelease written() {
        ArgumentCaptor<LabInstanceRelease> captor =
                ArgumentCaptor.forClass(LabInstanceRelease.class);
        verify(releases).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static CreateInstanceCommand command() {
        return new CreateInstanceCommand(BRAIN, SLUG, "Income", "Analyze income.", manifest());
    }

    private static LabInstanceRelease existingRelease(int number) {
        LabInstanceRelease release = new LabInstanceRelease();
        release.setId(PREVIOUS);
        release.setBrainId(BRAIN);
        release.setInstanceSlug(SLUG);
        release.setReleaseNumber(number);
        release.setProvenanceMode(LabInstanceRelease.ProvenanceMode.CANDIDATE);
        release.setManifest(Map.of());
        return release;
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
