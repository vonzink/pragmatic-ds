package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.model.ModelEstimate.ActualCost;
import com.pragmaticds.rag.lab.model.ModelEstimate.ProviderUsage;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * What a run is recorded as having consumed, and what must never be invented.
 *
 * <p>The rule under test throughout is that <b>absent is not zero</b>. A provider that reports
 * nothing leaves a usage row carrying no numbers at all, and contributes nothing to the brain's
 * daily spend — because a fabricated zero is indistinguishable from a measured one the moment it
 * reaches a cost report, and it would then be defended as data.
 */
class InstanceUsageServiceTest {

    private static final UUID RUN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRICING = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String PROVIDER = "anthropic";
    private static final String MODEL = "claude-opus-5";

    private LabModelUsageRepository usage;
    private InstanceCostEstimator estimator;
    private SpendGuardService spend;
    private InstanceUsageService service;

    @BeforeEach
    void setUp() {
        usage = mock(LabModelUsageRepository.class);
        estimator = mock(InstanceCostEstimator.class);
        spend = mock(SpendGuardService.class);
        when(usage.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new InstanceUsageService(usage, estimator, spend,
                mock(InstanceControlMetrics.class), transactions);
    }

    // ============================================================ reading a provider response

    @Test
    void aProviderThatReportedNothingReadsAsThreeAbsences() {
        ProviderUsage read = InstanceUsageService.from(
                new AiResponse("answer", PROVIDER, MODEL, null, null, null));

        assertNull(read.inputTokens());
        assertNull(read.cachedInputTokens());
        assertNull(read.outputTokens());
        assertTrue(read.isAbsent(), "nothing reported is absent, not a measured zero");
    }

    @Test
    void aReportedZeroSurvivesAsAZeroAndNotAsAnAbsence() {
        // The distinction the whole class exists for: this provider DID answer the question, and
        // its answer was zero. Collapsing it into the absent case would discard a measurement.
        ProviderUsage read = InstanceUsageService.from(
                new AiResponse("answer", PROVIDER, MODEL, 0, 0, 0));

        assertEquals(0L, read.inputTokens());
        assertEquals(0L, read.outputTokens());
        assertEquals(0L, read.cachedInputTokens());
        assertFalse(read.isAbsent(), "a reported zero is a measurement");
    }

    @Test
    void theCachedCategoryIsAbsentOnItsOwnWhenOnlyTheTotalsWereReported() {
        // Per-category absence, not all-or-nothing: Spring AI's Usage surfaces prompt and
        // completion totals but not the cache split, which is the shape every current caller has.
        ProviderUsage read = InstanceUsageService.from(
                new AiResponse("answer", PROVIDER, MODEL, 900, 120));

        assertEquals(900L, read.inputTokens());
        assertEquals(120L, read.outputTokens());
        assertNull(read.cachedInputTokens(), "an unreported cache split stays unreported");
    }

    @Test
    void aNullResponseIsAbsentRatherThanAFailure() {
        assertTrue(InstanceUsageService.from(null).isAbsent());
    }

    // ============================================================ recording

    @Test
    void measuredUsageIsWrittenAndCountedAgainstTheBrainsDailySpendExactlyOnce() {
        LabModelUsage row = pending();
        when(usage.findByRunIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.of(row));
        when(estimator.priceActual(PRICING, PROVIDER, MODEL, reported()))
                .thenReturn(new ActualCost(new BigDecimal("0.012500"), 1020L,
                        UsageQuality.REPORTED));

        Optional<ActualCost> recorded = service.report(RUN, BRAIN, reported());

        assertTrue(recorded.isPresent());
        assertEquals(new BigDecimal("0.012500"), recorded.get().costUsd());
        assertEquals(UsageQuality.REPORTED, row.getUsageQuality());
        assertEquals(900L, row.getActualInputTokens());
        assertEquals(120L, row.getActualOutputTokens());
        assertEquals(300L, row.getActualCachedTokens());
        assertEquals(1020L, row.getActualTotalTokens());
        assertEquals(new BigDecimal("0.012500"), row.getActualCostUsd());
        assertNotNull(row.getReportedAt());
        verify(spend).recordSpend(BRAIN, MODEL, 900L, 120L);
    }

    @Test
    void aRetriedCompletionCannotDoubleCountTheBudget() {
        // The row is the gate. Without it a retry after a lost response would bill the brain twice
        // for one provider call, and the second charge would look exactly like the first.
        LabModelUsage row = pending();
        row.report(UsageQuality.REPORTED, 900L, 300L, 120L, 1020L, new BigDecimal("0.012500"));
        when(usage.findByRunIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.of(row));

        assertTrue(service.report(RUN, BRAIN, reported()).isEmpty());

        verifyNoInteractions(spend);
        verify(estimator, never()).priceActual(any(), anyString(), anyString(), any());
        verify(usage, never()).saveAndFlush(any());
    }

    @Test
    void anUnpriceableCallIsRecordedAsUnavailableAndAddsNothingToTheDailyTotal() {
        LabModelUsage row = pending();
        when(usage.findByRunIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.of(row));
        when(estimator.priceActual(eq(PRICING), eq(PROVIDER), eq(MODEL), any()))
                .thenReturn(new ActualCost(null, null, UsageQuality.UNAVAILABLE));

        Optional<ActualCost> recorded =
                service.report(RUN, BRAIN, new ProviderUsage(null, null, null));

        assertTrue(recorded.isPresent());
        assertEquals(UsageQuality.UNAVAILABLE, recorded.get().quality());
        assertEquals(UsageQuality.UNAVAILABLE, row.getUsageQuality());
        verify(usage).saveAndFlush(row);

        // The load-bearing assertion. A zero here would claim the call was free, and every daily
        // total downstream would carry that claim as though someone had measured it.
        assertNull(row.getActualInputTokens());
        assertNull(row.getActualOutputTokens());
        assertNull(row.getActualCachedTokens());
        assertNull(row.getActualTotalTokens());
        assertNull(row.getActualCostUsd());
        verifyNoInteractions(spend);
    }

    @Test
    void aRunWithNoUsageRowIsNotAnError() {
        // A run created before this path existed, or one whose group was purged. The run is
        // already terminal and its truth does not depend on a cost row appearing.
        when(usage.findByRunIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.empty());

        assertTrue(service.report(RUN, BRAIN, reported()).isEmpty());
        verifyNoInteractions(spend);
        verify(estimator, never()).priceActual(any(), anyString(), anyString(), any());
    }

    @Test
    void pricingUsesTheRunsOwnPinnedCatalogVersionRatherThanTodays() {
        // Pricing a finished run against a catalog that has since moved would silently restate
        // history every time a rate changed. The version comes off the row, not off the clock.
        LabModelUsage row = pending();
        when(usage.findByRunIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.of(row));
        when(estimator.priceActual(any(), anyString(), anyString(), any()))
                .thenReturn(new ActualCost(new BigDecimal("0.010000"), 1020L,
                        UsageQuality.REPORTED));

        service.report(RUN, BRAIN, reported());

        verify(estimator).priceActual(PRICING, PROVIDER, MODEL, reported());
    }

    // ============================================================ fixtures

    private static ProviderUsage reported() {
        return new ProviderUsage(900L, 300L, 120L);
    }

    private static LabModelUsage pending() {
        LabModelUsage row = new LabModelUsage(RUN, BRAIN, PRICING, PROVIDER, MODEL,
                800, 1200, 100, 400, new BigDecimal("0.008000"), new BigDecimal("0.020000"),
                EstimateQuality.ESTIMATED_RANGE);
        assertEquals(UsageQuality.PENDING, row.getUsageQuality());
        return row;
    }
}
