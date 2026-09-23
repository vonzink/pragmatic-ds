package com.pragmaticds.rag.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningEnumsTest {

    @Test
    void feedbackRatingNames() {
        assertEquals("UP", FeedbackRating.UP.name());
        assertEquals("DOWN", FeedbackRating.DOWN.name());
    }

    @Test
    void feedbackRatingParseIsCaseSensitiveExactMatch() {
        assertEquals(FeedbackRating.UP, FeedbackRating.parse("UP"));
        assertTrue(FeedbackRating.isValid("DOWN"));
        assertFalse(FeedbackRating.isValid("up"));
        assertFalse(FeedbackRating.isValid("MAYBE"));
        assertFalse(FeedbackRating.isValid(null));
        assertThrows(IllegalArgumentException.class, () -> FeedbackRating.parse("bogus"));
    }

    @Test
    void feedbackSourceNames() {
        assertEquals("END_USER", FeedbackSource.END_USER.name());
        assertEquals("ADMIN", FeedbackSource.ADMIN.name());
    }

    @Test
    void weightEventStatusCoversAllFive() {
        assertEquals("APPLIED", WeightEventStatus.APPLIED.name());
        assertEquals("PENDING", WeightEventStatus.PENDING.name());
        assertEquals("APPROVED", WeightEventStatus.APPROVED.name());
        assertEquals("REJECTED", WeightEventStatus.REJECTED.name());
        assertEquals("REVERTED", WeightEventStatus.REVERTED.name());
        assertEquals(5, WeightEventStatus.values().length);
    }
}
