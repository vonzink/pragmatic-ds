package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.model.InstanceTokenEstimator.TokenRange;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The estimator's job is to be honest about not knowing.
 *
 * <p>Most of these tests are about the label rather than the number. A band that is slightly wrong
 * costs budget headroom; a band presented as an exact figure costs the reader's ability to tell the
 * difference, which is the failure that matters.
 */
class InstanceTokenEstimatorTest {

    private final InstanceTokenEstimator estimator = new InstanceTokenEstimator();

    @Test
    void measuredTextIsAlwaysABandAndAlwaysSaysSo() {
        TokenRange range = estimator.estimate("Qualifying income is averaged over 24 months.");

        assertEquals(EstimateQuality.ESTIMATED_RANGE, range.quality(),
                "no provider tokenizer ships, so a measured count can never claim to be exact");
        assertTrue(range.max() > range.min(),
                "an estimate whose bounds are equal is indistinguishable from a real count");
    }

    @Test
    void theBandBracketsTheFourCharactersPerTokenRuleOfThumbOnBothSides() {
        String text = "x".repeat(4000);
        TokenRange range = estimator.estimate(text);

        // Roughly a thousand tokens by the usual rule of thumb; the band must contain it rather
        // than sit to one side, or it is not a bound on the thing it claims to bound.
        assertTrue(range.min() < 1000, "the lower bound must allow sparser tokenization");
        assertTrue(range.max() > 1000, "the upper bound must allow denser tokenization");
        assertEquals(800, range.min());
        assertEquals(2000, range.max());
    }

    @Test
    void theUpperBoundIsDeliberatelyTheConservativeSide() {
        // A budget reserves against the maximum, so the maximum is the number that must not be
        // optimistic. Two characters per token covers JSON, code, and non-Latin scripts.
        assertEquals(2.0, InstanceTokenEstimator.MIN_CHARS_PER_TOKEN);
        assertEquals(5.0, InstanceTokenEstimator.MAX_CHARS_PER_TOKEN);

        TokenRange json = estimator.estimate("{\"a\":1,\"b\":2}");
        assertEquals(13, "{\"a\":1,\"b\":2}".length());
        assertEquals(7, json.max(), "dense structured text must not be under-counted");
    }

    @Test
    void absentTextCostsNothingRatherThanThrowing() {
        for (TokenRange range : List.of(estimator.estimate(null), estimator.estimate(""))) {
            assertEquals(0, range.min());
            assertEquals(0, range.max());
        }
    }

    @Test
    void aCountThatIsExactByConstructionSaysSo() {
        // An allowance and a ceiling are already token counts. Calling those estimates would be
        // as misleading in the other direction.
        TokenRange allowance = TokenRange.exactly(4096);

        assertEquals(4096, allowance.min());
        assertEquals(4096, allowance.max());
        assertEquals(EstimateQuality.EXACT_TOKENIZER, allowance.quality());
    }

    @Test
    void addingAnExactCountToAMeasuredOneStillYieldsAnEstimate() {
        TokenRange combined = TokenRange.exactly(4096).plus(estimator.estimate("some prompt text"));

        assertEquals(EstimateQuality.ESTIMATED_RANGE, combined.quality(),
                "a sum is only as good as its worst part");
        assertTrue(combined.min() > 4096);
    }

    @Test
    void severalTextsCombineIntoOneBandAndAnEmptyListIsExactlyZero() {
        TokenRange body = estimator.estimateAll(List.of("first block", "second block"));
        TokenRange first = estimator.estimate("first block");
        TokenRange second = estimator.estimate("second block");

        assertEquals(first.min() + second.min(), body.min());
        assertEquals(first.max() + second.max(), body.max());

        TokenRange nothing = estimator.estimateAll(List.of());
        assertEquals(0, nothing.max());
        assertEquals(EstimateQuality.EXACT_TOKENIZER, nothing.quality(),
                "no text really is exactly no tokens");
    }

    @Test
    void anUnorderedOrNegativeBandCannotBeConstructedAtAll() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenRange(10, 5, EstimateQuality.ESTIMATED_RANGE));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenRange(-1, 5, EstimateQuality.ESTIMATED_RANGE));
    }
}
