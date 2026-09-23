package com.pragmaticds.rag.service.cost;

import com.pragmaticds.rag.config.CostProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainDailyUsage;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the spend-cap circuit breaker: budget resolution (brain override
 * vs. global default), the over-budget gate, cost estimation, and spend
 * recording. Opt-in/OFF-by-default is the load-bearing safety behavior here —
 * a budget of 0 (unset) must never block a request.
 */
class SpendGuardServiceTest {

    private static final UUID BRAIN_ID = UUID.randomUUID();
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);

    private CostProperties props(double globalBudget) {
        return new CostProperties(globalBudget, Map.of(
                "claude-haiku-4-5", new CostProperties.ModelPrice(1.0, 5.0)),
                new CostProperties.ModelPrice(15.0, 75.0));
    }

    private Brain brainWithBudget(BigDecimal override) {
        Brain brain = new Brain(BRAIN_ID, "slug", "Display");
        brain.setDailyCostBudgetUsd(override);
        return brain;
    }

    // ---- resolveBudget ----

    @Test
    void resolveBudgetUsesGlobalDefaultWhenBrainOverrideIsNull() {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.of(brainWithBudget(null)));
        SpendGuardService guard = new SpendGuardService(
                props(5.0), brains, mock(BrainDailyUsageRepository.class), FIXED_CLOCK);

        assertEquals(0, BigDecimal.valueOf(5.0).compareTo(guard.resolveBudget(BRAIN_ID)));
    }

    @Test
    void resolveBudgetPrefersBrainOverrideWhenSet() {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.of(brainWithBudget(BigDecimal.valueOf(2.50))));
        SpendGuardService guard = new SpendGuardService(
                props(5.0), brains, mock(BrainDailyUsageRepository.class), FIXED_CLOCK);

        assertEquals(0, BigDecimal.valueOf(2.50).compareTo(guard.resolveBudget(BRAIN_ID)));
    }

    @Test
    void resolveBudgetReturnsZeroWhenBrainUnknown() {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.empty());
        SpendGuardService guard = new SpendGuardService(
                props(5.0), brains, mock(BrainDailyUsageRepository.class), FIXED_CLOCK);

        assertEquals(0, BigDecimal.ZERO.compareTo(guard.resolveBudget(BRAIN_ID)));
    }

    // ---- isOverBudget: off-by-default ----

    @Test
    void isOverBudgetIsFalseWhenGlobalAndBrainBudgetAreBothUnset() {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.of(brainWithBudget(null)));
        BrainDailyUsageRepository usage = mock(BrainDailyUsageRepository.class);
        SpendGuardService guard = new SpendGuardService(props(0.0), brains, usage, FIXED_CLOCK);

        assertFalse(guard.isOverBudget(BRAIN_ID), "budget of 0 (unset) must mean unlimited");
        // Off-by-default must short-circuit before touching usage data at all.
        verify(usage, never()).findByBrainIdAndUsageDate(any(), any());
    }

    @Test
    void isOverBudgetIsFalseWhenUnderTheResolvedBudget() {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.of(brainWithBudget(null)));
        BrainDailyUsageRepository usage = mock(BrainDailyUsageRepository.class);
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        when(usage.findByBrainIdAndUsageDate(BRAIN_ID, today)).thenReturn(Optional.of(
                new BrainDailyUsage(BRAIN_ID, today, 3, 1000, 500, BigDecimal.valueOf(1.00))));
        SpendGuardService guard = new SpendGuardService(props(5.0), brains, usage, FIXED_CLOCK);

        assertFalse(guard.isOverBudget(BRAIN_ID));
    }

    @Test
    void isOverBudgetIsTrueWhenTodaysSpendMeetsOrExceedsBudget() {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.of(brainWithBudget(null)));
        BrainDailyUsageRepository usage = mock(BrainDailyUsageRepository.class);
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        when(usage.findByBrainIdAndUsageDate(BRAIN_ID, today)).thenReturn(Optional.of(
                new BrainDailyUsage(BRAIN_ID, today, 10, 5000, 5000, BigDecimal.valueOf(5.00))));
        SpendGuardService guard = new SpendGuardService(props(5.0), brains, usage, FIXED_CLOCK);

        assertTrue(guard.isOverBudget(BRAIN_ID), "spend meeting the budget must trip the breaker");
    }

    @Test
    void isOverBudgetIsFalseWhenNoUsageRowExistsYetForToday() {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.of(brainWithBudget(null)));
        BrainDailyUsageRepository usage = mock(BrainDailyUsageRepository.class);
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        when(usage.findByBrainIdAndUsageDate(BRAIN_ID, today)).thenReturn(Optional.empty());
        SpendGuardService guard = new SpendGuardService(props(5.0), brains, usage, FIXED_CLOCK);

        assertFalse(guard.isOverBudget(BRAIN_ID));
    }

    // ---- estimateCost: pure function ----

    @Test
    void estimateCostUsesKnownModelPricePerMillionTokens() {
        SpendGuardService guard = new SpendGuardService(
                props(5.0), mock(BrainRepository.class), mock(BrainDailyUsageRepository.class), FIXED_CLOCK);

        // 1,000,000 prompt tokens @ $1.00/M + 1,000,000 completion tokens @ $5.00/M = $6.00
        BigDecimal cost = guard.estimateCost("claude-haiku-4-5", 1_000_000L, 1_000_000L);

        assertEquals(0, BigDecimal.valueOf(6.00).compareTo(cost));
    }

    @Test
    void estimateCostFallsBackToConservativePriceForUnknownModel() {
        SpendGuardService guard = new SpendGuardService(
                props(5.0), mock(BrainRepository.class), mock(BrainDailyUsageRepository.class), FIXED_CLOCK);

        // 1,000,000 prompt @ $15/M + 1,000,000 completion @ $75/M = $90.00 (fallback price)
        BigDecimal cost = guard.estimateCost("some-unrecognized-model", 1_000_000L, 1_000_000L);

        assertEquals(0, BigDecimal.valueOf(90.00).compareTo(cost));
    }

    @Test
    void estimateCostScalesLinearlyWithSmallTokenCounts() {
        SpendGuardService guard = new SpendGuardService(
                props(5.0), mock(BrainRepository.class), mock(BrainDailyUsageRepository.class), FIXED_CLOCK);

        // 1000 prompt + 1000 completion tokens @ (1.0, 5.0) per million.
        BigDecimal cost = guard.estimateCost("claude-haiku-4-5", 1000L, 1000L);

        assertEquals(0, BigDecimal.valueOf(0.006).compareTo(cost));
    }

    @Test
    void estimateCostTreatsNullModelAsUnknown() {
        SpendGuardService guard = new SpendGuardService(
                props(5.0), mock(BrainRepository.class), mock(BrainDailyUsageRepository.class), FIXED_CLOCK);

        BigDecimal cost = guard.estimateCost(null, 1_000_000L, 1_000_000L);

        assertEquals(0, BigDecimal.valueOf(90.00).compareTo(cost));
    }

    // ---- recordSpend ----

    @Test
    void recordSpendUpsertsEstimatedCostForToday() {
        BrainDailyUsageRepository usage = mock(BrainDailyUsageRepository.class);
        SpendGuardService guard = new SpendGuardService(
                props(5.0), mock(BrainRepository.class), usage, FIXED_CLOCK);

        guard.recordSpend(BRAIN_ID, "claude-haiku-4-5", 1000L, 1000L);

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        verify(usage).upsertSpend(eq(BRAIN_ID), eq(today), eq(1000L), eq(1000L),
                eq(BigDecimal.valueOf(0.006).setScale(6, RoundingMode.HALF_UP)));
    }

    @Test
    void recordSpendTreatsNullTokenCountsAsZero() {
        BrainDailyUsageRepository usage = mock(BrainDailyUsageRepository.class);
        SpendGuardService guard = new SpendGuardService(
                props(5.0), mock(BrainRepository.class), usage, FIXED_CLOCK);

        guard.recordSpend(BRAIN_ID, "claude-haiku-4-5", null, null);

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        verify(usage).upsertSpend(eq(BRAIN_ID), eq(today), eq(0L), eq(0L),
                eq(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)));
    }

    @Test
    void recordSpendStillAccumulatesUsageWhenBudgetIsUnsetGlobally() {
        // Usage tracking (for the admin dashboard) is independent of enforcement:
        // recordSpend always accumulates today's usage row even when no budget is
        // configured, so isOverBudget/dashboard data works retroactively the
        // moment an admin sets a budget without losing prior-day history.
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(BRAIN_ID)).thenReturn(Optional.of(brainWithBudget(null)));
        BrainDailyUsageRepository usage = mock(BrainDailyUsageRepository.class);
        SpendGuardService guard = new SpendGuardService(props(0.0), brains, usage, FIXED_CLOCK);

        guard.recordSpend(BRAIN_ID, "claude-haiku-4-5", 1000L, 1000L);

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        verify(usage).upsertSpend(eq(BRAIN_ID), eq(today), eq(1000L), eq(1000L),
                eq(BigDecimal.valueOf(0.006).setScale(6, RoundingMode.HALF_UP)));
    }
}
