package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.CatalogModel;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.ModelCatalogException;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.ResolvedCatalog;
import com.pragmaticds.rag.lab.model.ModelEstimate.ActualCost;
import com.pragmaticds.rag.lab.model.ModelEstimate.EstimateInput;
import com.pragmaticds.rag.lab.model.ModelEstimate.ProviderUsage;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import com.pragmaticds.rag.lab.run.domain.LabModelCatalogEntry;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pricing a run before it happens, and what it actually cost afterwards.
 *
 * <p>The two rules that separate this from the legacy spend guard both show up as tests: there is
 * no fallback price for an unknown model, and an absent provider usage report never becomes a zero.
 */
class InstanceCostEstimatorTest {

    private static final UUID VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String PROVIDER = "anthropic";
    private static final String MODEL = "claude-opus-5";

    private InstanceModelCatalogService catalog;
    private InstanceCostEstimator estimator;

    @BeforeEach
    void setUp() {
        catalog = mock(InstanceModelCatalogService.class);
        CatalogModel priced = new CatalogModel(PROVIDER, MODEL, 1_000_000, 128_000,
                LabModelCatalogEntry.TokenizerStrategy.CONSERVATIVE_RANGE,
                new BigDecimal("5.00"), new BigDecimal("0.50"), new BigDecimal("25.00"));
        when(catalog.require(PROVIDER, MODEL)).thenReturn(priced);
        when(catalog.active()).thenReturn(new ResolvedCatalog(VERSION, "a".repeat(64),
                List.of(priced)));
        when(catalog.findInVersion(VERSION, PROVIDER, MODEL)).thenReturn(Optional.of(priced));
        estimator = new InstanceCostEstimator(catalog, new InstanceTokenEstimator());
    }

    @Test
    void everythingThatOccupiesTheContextWindowIsCounted() {
        ModelEstimate bare = estimator.estimate(PROVIDER, MODEL, input("", "", "", 0, List.of(), "", 0));

        // Each component in turn must move the input bound, or it is not in the estimate at all.
        assertTrue(more(bare, input("system prompt text", "", "", 0, List.of(), "", 0)));
        assertTrue(more(bare, input("", "task prompt text", "", 0, List.of(), "", 0)));
        assertTrue(more(bare, input("", "", "rendered parsed facts", 0, List.of(), "", 0)));
        assertTrue(more(bare, input("", "", "", 4096, List.of(), "", 0)));
        assertTrue(more(bare, input("", "", "", 0, List.of("{\"tool\":1}"), "", 0)));
        assertTrue(more(bare, input("", "", "", 0, List.of(), "{\"schema\":1}", 0)));
        // Prior discussion occupies the window too; the output ceiling does not, and is checked
        // against the output bound in its own test rather than smuggled in here.
        assertTrue(more(bare, conversation(2048)));
        // A fact block nobody has rendered yet arrives as a token bound rather than as text.
        assertTrue(more(bare, parsedAllowance(4096)));
    }

    @Test
    void theOutputBoundIsTheLowerOfTheReleaseCeilingAndTheModelsOwn() {
        ModelEstimate withinModel = estimator.estimate(PROVIDER, MODEL,
                input("", "", "", 0, List.of(), "", 4_000));
        assertEquals(4_000, withinModel.outputTokensMax());

        // A release cannot ask for more output than the model can produce.
        ModelEstimate beyondModel = estimator.estimate(PROVIDER, MODEL,
                input("", "", "", 0, List.of(), "", 500_000));
        assertEquals(128_000, beyondModel.outputTokensMax());

        // Zero floor: a refusal or an empty envelope really can produce almost nothing, and a
        // floor the run might not reach is useless as a floor.
        assertEquals(0, beyondModel.outputTokensMin());
    }

    @Test
    void anEstimateContainingMeasuredTextIsNeverLabelledExact() {
        ModelEstimate measured = estimator.estimate(PROVIDER, MODEL,
                input("system", "task", "facts", 4096, List.of(), "", 1000));

        assertEquals(EstimateQuality.ESTIMATED_RANGE, measured.quality());
        assertTrue(measured.inputTokensMax() > measured.inputTokensMin());
        assertTrue(measured.costUsdMax().compareTo(measured.costUsdMin()) > 0);
    }

    @Test
    void moneyIsRoundedToTheSixDecimalsTheColumnStores() {
        ModelEstimate estimate = estimator.estimate(PROVIDER, MODEL,
                input("", "", "", 1, List.of(), "", 1));

        assertEquals(InstanceCostEstimator.COST_SCALE, estimate.costUsdMin().scale());
        assertEquals(InstanceCostEstimator.COST_SCALE, estimate.costUsdMax().scale());
        // One input token at $5/M and one output token at $25/M.
        assertEquals(new BigDecimal("0.000005"), estimate.costUsdMin());
        assertEquals(new BigDecimal("0.000030"), estimate.costUsdMax());
    }

    @Test
    void anUnknownModelIsRefusedRatherThanPricedAtAGuess() {
        when(catalog.require(eq(PROVIDER), anyString())).thenThrow(
                new ModelCatalogException(ModelCatalogException.Code.MODEL_NOT_IN_CATALOG));

        // The legacy spend guard falls back to a conservative price because its call already ran.
        // Here the estimate happens before dispatch, so refusing is both possible and correct.
        assertThrows(ModelCatalogException.class, () -> estimator.estimate(PROVIDER, "unknown",
                input("", "", "", 0, List.of(), "", 0)));
    }

    @Test
    void reportedUsageIsPricedAgainstTheVersionTheRunWasPinnedTo() {
        ActualCost cost = estimator.priceActual(VERSION, PROVIDER, MODEL,
                new ProviderUsage(1_000_000L, null, 100_000L));

        // $5.00 for a million input tokens plus $2.50 for a hundred thousand output tokens.
        assertEquals(new BigDecimal("7.500000"), cost.costUsd());
        assertEquals(1_100_000L, cost.totalTokens());
        assertEquals(UsageQuality.REPORTED, cost.quality());
    }

    @Test
    void cachedInputIsChargedOnceAtItsOwnRate() {
        // Providers report cached tokens as a subset of input tokens, so charging both rates on
        // the same tokens would double-count them.
        ActualCost cost = estimator.priceActual(VERSION, PROVIDER, MODEL,
                new ProviderUsage(1_000_000L, 800_000L, 0L));

        // 200k uncached at $5/M plus 800k cached at $0.50/M.
        assertEquals(new BigDecimal("1.400000"), cost.costUsd());
    }

    @Test
    void aProviderReportingMoreCachedThanInputIsClampedRatherThanCredited() {
        ActualCost cost = estimator.priceActual(VERSION, PROVIDER, MODEL,
                new ProviderUsage(100_000L, 500_000L, 0L));

        // Everything cached, nothing left to charge at full rate, and never a negative charge.
        assertEquals(new BigDecimal("0.050000"), cost.costUsd());
        assertTrue(cost.costUsd().signum() >= 0);
    }

    @Test
    void aModelWithNoSeparateCachedRatePaysTheFullInputRateForCachedTokens() {
        CatalogModel noCachedRate = new CatalogModel(PROVIDER, "flat", 1_000_000, 128_000,
                LabModelCatalogEntry.TokenizerStrategy.CONSERVATIVE_RANGE,
                new BigDecimal("5.00"), null, new BigDecimal("25.00"));
        when(catalog.findInVersion(VERSION, PROVIDER, "flat")).thenReturn(Optional.of(noCachedRate));

        ActualCost cost = estimator.priceActual(VERSION, PROVIDER, "flat",
                new ProviderUsage(1_000_000L, 1_000_000L, 0L));

        // Null means "no separate cached rate", not "cached input is free"; charging zero would
        // invent a discount the provider never offered.
        assertEquals(new BigDecimal("5.000000"), cost.costUsd());
    }

    @Test
    void absentUsageIsUnavailableAndCarriesNoNumbersAtAll() {
        ActualCost absent = estimator.priceActual(VERSION, PROVIDER, MODEL,
                new ProviderUsage(null, null, null));

        assertEquals(UsageQuality.UNAVAILABLE, absent.quality());
        assertNull(absent.costUsd(), "a zero here would be indistinguishable from a free call");
        assertNull(absent.totalTokens());
    }

    @Test
    void aRunWhoseModelIsAbsentFromItsPinnedVersionIsNotPricedByGuessing() {
        when(catalog.findInVersion(any(), anyString(), anyString())).thenReturn(Optional.empty());

        ActualCost cost = estimator.priceActual(VERSION, PROVIDER, MODEL,
                new ProviderUsage(1000L, null, 100L));

        // The run's pinned identity and the catalog disagree; picking a winner is not an option.
        assertEquals(UsageQuality.UNAVAILABLE, cost.quality());
        assertNull(cost.costUsd());
    }

    @Test
    void oneMissingCategoryStillPricesTheCategoriesThatWereReported() {
        ActualCost outputOnly = estimator.priceActual(VERSION, PROVIDER, MODEL,
                new ProviderUsage(null, null, 100_000L));

        assertEquals(UsageQuality.REPORTED, outputOnly.quality());
        assertEquals(new BigDecimal("2.500000"), outputOnly.costUsd());
    }

    // ================================================================ fixtures

    private boolean more(ModelEstimate baseline, EstimateInput richer) {
        return estimator.estimate(PROVIDER, MODEL, richer).inputTokensMax()
                > baseline.inputTokensMax();
    }

    private static EstimateInput input(String system, String task, String facts, long allowance,
                                       List<String> toolSchemas, String outputSchema,
                                       int outputCeiling) {
        return new EstimateInput(system, task, facts, allowance, toolSchemas, outputSchema,
                0L, 0L, outputCeiling);
    }

    private static EstimateInput conversation(long priorTokens) {
        return new EstimateInput("", "", "", 0, List.of(), "", priorTokens, 0L, 0);
    }

    private static EstimateInput parsedAllowance(long tokens) {
        return new EstimateInput("", "", "", 0, List.of(), "", 0L, tokens, 0);
    }
}
