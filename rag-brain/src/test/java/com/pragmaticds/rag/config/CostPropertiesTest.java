package com.pragmaticds.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Binds {@link CostProperties} straight from an {@link org.springframework.core.env.Environment}
 * via {@link Binder} — deliberately NO Spring test context and NO nested
 * {@code @SpringBootConfiguration}, matching LearningPropertiesTest: a nested
 * config class here would be picked up by Spring Boot's config detection for
 * other tests in this package, hijacking their repository scan base.
 */
class CostPropertiesTest {

    private static CostProperties bind(Map<String, String> properties) {
        MockEnvironment env = new MockEnvironment();
        properties.forEach(env::setProperty);
        return Binder.get(env).bind("ragbrain.rag.cost", CostProperties.class).get();
    }

    @Test
    void defaultsToDisabledWhenBudgetIsZero() {
        CostProperties props = bind(Map.of(
                "ragbrain.rag.cost.daily-budget-usd", "0",
                "ragbrain.rag.cost.fallback-price.input-per-million", "10.0",
                "ragbrain.rag.cost.fallback-price.output-per-million", "30.0"));

        assertEquals(0.0, props.dailyBudgetUsd(), 1e-9);
        assertEquals(10.0, props.fallbackPrice().inputPerMillion(), 1e-9);
        assertEquals(30.0, props.fallbackPrice().outputPerMillion(), 1e-9);
    }

    @Test
    void bindsBudgetAndPerModelPriceMap() {
        CostProperties props = bind(Map.of(
                "ragbrain.rag.cost.daily-budget-usd", "5.00",
                "ragbrain.rag.cost.model-prices.claude-haiku-4-5.input-per-million", "1.0",
                "ragbrain.rag.cost.model-prices.claude-haiku-4-5.output-per-million", "5.0",
                "ragbrain.rag.cost.model-prices.gpt-4o.input-per-million", "2.5",
                "ragbrain.rag.cost.model-prices.gpt-4o.output-per-million", "10.0",
                "ragbrain.rag.cost.fallback-price.input-per-million", "15.0",
                "ragbrain.rag.cost.fallback-price.output-per-million", "75.0"));

        assertEquals(5.00, props.dailyBudgetUsd(), 1e-9);

        CostProperties.ModelPrice haiku = props.modelPrices().get("claude-haiku-4-5");
        assertEquals(1.0, haiku.inputPerMillion(), 1e-9);
        assertEquals(5.0, haiku.outputPerMillion(), 1e-9);

        CostProperties.ModelPrice gpt4o = props.modelPrices().get("gpt-4o");
        assertEquals(2.5, gpt4o.inputPerMillion(), 1e-9);
        assertEquals(10.0, gpt4o.outputPerMillion(), 1e-9);

        assertEquals(15.0, props.fallbackPrice().inputPerMillion(), 1e-9);
        assertEquals(75.0, props.fallbackPrice().outputPerMillion(), 1e-9);
    }
}
