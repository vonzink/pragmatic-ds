package com.pragmaticds.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Binds {@link LearningProperties} straight from an {@link org.springframework.core.env.Environment}
 * via {@link Binder} — deliberately NO Spring test context and NO nested
 * {@code @SpringBootConfiguration}. A nested config class here would be picked up
 * by Spring Boot's config detection for other tests in this package (e.g.
 * DefaultBrainSeederTest), hijacking their repository scan base — so this test
 * stays context-free.
 */
class LearningPropertiesTest {

    private static LearningProperties bind(Map<String, String> properties) {
        MockEnvironment env = new MockEnvironment();
        properties.forEach(env::setProperty);
        return Binder.get(env).bind("ragbrain.rag.learning", LearningProperties.class).get();
    }

    @Test
    void bindsAllTunablesFromKebabCaseKeys() {
        LearningProperties props = bind(Map.of(
                "ragbrain.rag.learning.weight-min", "0.8",
                "ragbrain.rag.learning.weight-max", "1.2",
                "ragbrain.rag.learning.max-delta-per-run", "0.05",
                "ragbrain.rag.learning.min-evidence", "5",
                "ragbrain.rag.learning.admin-vote-weight", "3",
                "ragbrain.rag.learning.review-threshold", "0.10",
                "ragbrain.rag.learning.decay", "0.98"));

        assertEquals(0.8, props.weightMin(), 1e-9);
        assertEquals(1.2, props.weightMax(), 1e-9);
        assertEquals(0.05, props.maxDeltaPerRun(), 1e-9);
        assertEquals(5, props.minEvidence());
        assertEquals(3, props.adminVoteWeight());
        assertEquals(0.10, props.reviewThreshold(), 1e-9);
        assertEquals(0.98, props.decay(), 1e-9);
    }

    @Test
    void rejectsInvalidWeightBounds() {
        // The compact constructor guards the clamp bounds (min>0, max>=min).
        assertThrows(IllegalArgumentException.class,
                () -> new LearningProperties(0.0, 1.2, 0.05, 5, 3, 0.10, 0.98));
        assertThrows(IllegalArgumentException.class,
                () -> new LearningProperties(1.3, 1.2, 0.05, 5, 3, 0.10, 0.98));
    }
}
