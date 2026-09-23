package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.domain.BrainDailyUsage;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.model.InstanceCostEstimator;
import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunGroupPreflight;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunMemberCommand;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.repository.LabSpendReservationRepository;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The budget arithmetic, which decides whether a submission is allowed to spend money.
 *
 * <p><b>Three numbers, and leaving any one out is a different bug.</b> Today's committed spend is
 * what has already been billed; {@code held} is what other in-flight groups have reserved and not
 * yet settled; {@code reserved} is what this group could cost at its ceiling. Dropping
 * {@code held} is the classic version: every concurrent submission passes on its own because none
 * of them can see the others, and a brain quietly runs at several times its cap.
 *
 * <p>Reserving the sum of member <em>maxima</em> rather than midpoints is the same argument at a
 * smaller scale — a group that reserved its expected cost and then landed at its upper bound would
 * overshoot a budget it was admitted under.
 */
class RunGroupPreflightServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RELEASE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID REGISTRATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SNAPSHOT = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID PACKAGE = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID PRICING = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String INSTANCE = "income";

    private InstanceCostEstimator estimator;
    private SpendGuardService budgets;
    private BrainDailyUsageRepository dailyUsage;
    private LabSpendReservationRepository reservations;
    private RunGroupPreflightService preflight;

    @BeforeEach
    void setUp() {
        InstanceRegistryService registry = mock(InstanceRegistryService.class);
        InstanceReleaseResolver releases = mock(InstanceReleaseResolver.class);
        LabInstanceReleaseRepository releaseRows = mock(LabInstanceReleaseRepository.class);
        ParsedDataResolver parsedInputs = mock(ParsedDataResolver.class);
        CorpusSnapshotService snapshots = mock(CorpusSnapshotService.class);
        InstanceOutputSchemaRegistry schemas = mock(InstanceOutputSchemaRegistry.class);
        estimator = mock(InstanceCostEstimator.class);
        budgets = mock(SpendGuardService.class);
        dailyUsage = mock(BrainDailyUsageRepository.class);
        reservations = mock(LabSpendReservationRepository.class);

        // Both entities keep a protected no-arg constructor for JPA, so a fixture goes through
        // the public one — which is the right shape anyway: a slug and a purpose are what an
        // instance is, and a test that could build one without them would be describing a row
        // the registry cannot produce.
        LabInstance instance = new LabInstance(BRAIN, INSTANCE, "Income", "smoke fixture");
        instance.setState(LabInstance.State.ACTIVE);
        when(registry.require(any())).thenReturn(instance);
        when(releaseRows.findByIdAndBrainIdAndInstanceSlug(any(), any(), anyString()))
                .thenReturn(Optional.of(new LabInstanceRelease()));
        when(releases.byId(any(), any())).thenReturn(new ResolvedInstanceRelease(
                instance, new LabInstanceRelease(),
                new DecodedInstanceManifest.V2(manifest()), false));
        when(snapshots.require(any(), any())).thenReturn(
                new CorpusSnapshotService.FrozenCorpusSnapshot(
                        SNAPSHOT, BRAIN, "a".repeat(64), List.of(), List.of()));
        when(parsedInputs.review(any(), anyString(), any(), any())).thenReturn(compatibleParse());
        when(schemas.requireText(anyString(), anyString())).thenReturn("{}");

        // Nothing spent, nothing held, no cap, until a test says otherwise.
        when(dailyUsage.findByBrainIdAndUsageDate(any(), any())).thenReturn(Optional.empty());
        when(reservations.reservedTotalFor(any())).thenReturn(BigDecimal.ZERO);
        when(budgets.resolveBudget(BRAIN)).thenReturn(BigDecimal.ZERO);
        costing(new BigDecimal("1.00"));

        preflight = new RunGroupPreflightService(registry, releases, releaseRows, parsedInputs,
                snapshots, estimator, schemas, budgets, dailyUsage, reservations,
                Clock.fixed(Instant.parse("2026-02-20T12:00:00Z"), ZoneOffset.UTC));
    }

    // ============================================================ what gets reserved

    @Test
    void aGroupReservesTheSumOfItsMembersMaximaRatherThanTheirMidpoints() {
        costing(new BigDecimal("0.40"), new BigDecimal("2.60"));

        RunGroupPreflight result = preflight.preflight(group(2));

        // Maxima sum to 3.00 where midpoints would sum to 1.50, so the two answers are far
        // enough apart that the assertion says which one was taken. A group that reserved its
        // expected cost and then landed at its upper bound would overshoot a budget it was
        // admitted under.
        assertEquals(0, new BigDecimal("3.00").compareTo(result.reservedMaximumUsd()));
        assertEquals(2, result.members().size());
    }

    @Test
    void aMemberThatCouldNotBePricedContributesNothingRatherThanBeingGuessedAt() {
        when(estimator.estimate(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("no pricing row for this model"));

        RunGroupPreflight result = preflight.preflight(group(1));

        // Zero, and a blocker: an unpriceable member must not be silently admitted as free, and
        // inventing a number for it would admit it under a figure nothing supports.
        assertEquals(0, BigDecimal.ZERO.compareTo(result.reservedMaximumUsd()));
        assertTrue(result.members().get(0).blockingCodes().contains("MEMBER_ESTIMATE_FAILED"));
        assertFalse(result.acceptable());
    }

    // ============================================================ the three numbers

    @Test
    void aBudgetOfZeroOrLessIsThisDeploymentsUnlimited() {
        costing(new BigDecimal("500.00"));
        when(budgets.resolveBudget(BRAIN)).thenReturn(BigDecimal.ZERO);

        assertTrue(preflight.preflight(group(1)).withinBudget());

        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("-1.00"));
        assertTrue(preflight.preflight(group(1)).withinBudget());
    }

    @Test
    void aGroupThatExactlyFillsTheRemainingBudgetIsAdmitted() {
        spent("4.00");
        held("3.00");
        costing(new BigDecimal("3.00"));
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("10.00"));

        // The comparison is <=, so a budget is a ceiling rather than something to stay under.
        RunGroupPreflight result = preflight.preflight(group(1));

        assertTrue(result.withinBudget());
        assertFalse(result.blockingCodes().contains("GROUP_EXCEEDS_DAILY_BUDGET"));
    }

    @Test
    void oneCentOverTheCeilingIsBlocked() {
        spent("4.00");
        held("3.00");
        costing(new BigDecimal("3.01"));
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("10.00"));

        RunGroupPreflight result = preflight.preflight(group(1));

        assertFalse(result.withinBudget());
        assertTrue(result.blockingCodes().contains("GROUP_EXCEEDS_DAILY_BUDGET"));
        assertFalse(result.acceptable());
    }

    @Test
    void whatOtherGroupsAreHoldingCountsAgainstThisOneEvenThoughNothingIsBilledYet() {
        spent("0.00");
        held("9.50");
        costing(new BigDecimal("1.00"));
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("10.00"));

        // The load-bearing case. Nothing has been billed, so committed spend alone would admit
        // this group — and every other group submitted in the same window, each one reasoning
        // that the budget was untouched, until a brain runs at several times its cap.
        RunGroupPreflight result = preflight.preflight(group(1));

        assertFalse(result.withinBudget());
        assertEquals(0, new BigDecimal("9.50").compareTo(result.alreadyReservedUsd()));
    }

    @Test
    void nothingHeldReadsAsZeroRatherThanLosingTheSubmission() {
        when(reservations.reservedTotalFor(any())).thenReturn(null);
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("10.00"));

        // A brain with no reservation rows at all is the ordinary case, not an error.
        RunGroupPreflight result = preflight.preflight(group(1));

        assertTrue(result.withinBudget());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.alreadyReservedUsd()));
    }

    @Test
    void theBudgetIsReportedAlongsideTheThreeNumbersThatWereWeighedAgainstIt() {
        spent("2.50");
        held("1.25");
        costing(new BigDecimal("0.75"));
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("10.00"));

        RunGroupPreflight result = preflight.preflight(group(1));

        // An operator refused for spending has to be able to see which of the three numbers
        // used up the budget, not just that something did.
        assertEquals(0, new BigDecimal("2.50").compareTo(result.committedTodayUsd()));
        assertEquals(0, new BigDecimal("1.25").compareTo(result.alreadyReservedUsd()));
        assertEquals(0, new BigDecimal("0.75").compareTo(result.reservedMaximumUsd()));
        assertEquals(0, new BigDecimal("10.00").compareTo(result.dailyBudgetUsd()));
    }

    @Test
    void todaysSpendIsReadForTodayRatherThanForWhicheverRowComesBack() {
        spent("2.50");
        when(budgets.resolveBudget(BRAIN)).thenReturn(new BigDecimal("10.00"));

        preflight.preflight(group(1));

        // A daily cap read against yesterday's total is not a daily cap.
        org.mockito.Mockito.verify(dailyUsage)
                .findByBrainIdAndUsageDate(BRAIN, LocalDate.of(2026, 2, 20));
    }

    // ============================================================ shape

    @Test
    void aGroupWithNoMembersRunsNothingAndAHugeOneIsAMistakeRatherThanAnIntention() {
        assertFalse(preflight.preflight(group(0)).acceptable());
        assertFalse(preflight.preflight(group(RunGroupPreflightService.MAXIMUM_MEMBERS + 1))
                .acceptable());
        assertTrue(preflight.preflight(group(RunGroupPreflightService.MAXIMUM_MEMBERS))
                .acceptable(), "the ceiling itself is a legal group");
    }

    @Test
    void anUnusableCommandIsRefusedWithoutPricingAnything() {
        RunGroupPreflight result = preflight.preflight(
                new RunGroupCommand(null, LabRunGroup.Mode.INDEPENDENT, null, List.of()));

        assertFalse(result.acceptable());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.reservedMaximumUsd()));
        org.mockito.Mockito.verify(estimator, org.mockito.Mockito.never())
                .estimate(anyString(), anyString(), any());
    }

    // ============================================================ fixtures

    /** Prices each member in turn; the last figure repeats if there are more members. */
    private void costing(BigDecimal... costUsdMax) {
        var stub = when(estimator.estimate(anyString(), anyString(), any()))
                .thenReturn(estimate(costUsdMax[0]));
        for (int index = 1; index < costUsdMax.length; index++) {
            stub = stub.thenReturn(estimate(costUsdMax[index]));
        }
    }

    private void spent(String amount) {
        BrainDailyUsage usage = new BrainDailyUsage(BRAIN, LocalDate.of(2026, 2, 20),
                3, 12_000L, 3_000L, new BigDecimal(amount));
        when(dailyUsage.findByBrainIdAndUsageDate(any(), any())).thenReturn(Optional.of(usage));
    }

    private void held(String amount) {
        when(reservations.reservedTotalFor(any())).thenReturn(new BigDecimal(amount));
    }

    private static ModelEstimate estimate(BigDecimal costUsdMax) {
        return new ModelEstimate(PRICING, "anthropic", "claude-opus-5", 1000L, 2000L, 200L, 400L,
                costUsdMax.divide(new BigDecimal("2"), 4, java.math.RoundingMode.HALF_UP),
                costUsdMax, EstimateQuality.ESTIMATED_RANGE);
    }

    private static RunGroupCommand group(int members) {
        return new RunGroupCommand(BRAIN, LabRunGroup.Mode.INDEPENDENT, null,
                IntStream.range(0, members)
                        .mapToObj(index -> new RunMemberCommand(
                                INSTANCE, RELEASE, REGISTRATION, SNAPSHOT))
                        .toList());
    }

    private static ParsedDataResolver.VerifiedParsedInput compatibleParse() {
        return new ParsedDataResolver.VerifiedParsedInput(REGISTRATION, PACKAGE, 1, PACKAGE, 1,
                "1.0.0", "DOCENGINE-C14N-1", "b".repeat(64), 4096L, "c".repeat(64),
                List.of(PACKAGE), null, null,
                new ParsedDataCompatibilityService.CompatibilityDecision(
                        true, null, List.of(), 1));
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
