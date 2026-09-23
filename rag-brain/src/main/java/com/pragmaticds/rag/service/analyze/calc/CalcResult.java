package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of one deterministic calculation request. status is COMPUTED or ERROR;
 * an ERROR carries a message and a null value — never a defaulted zero.
 */
public record CalcResult(String status, BigDecimal value,
                         Map<String, Object> intermediates, String error) {

    public static CalcResult computed(BigDecimal value, Map<String, Object> intermediates) {
        // LinkedHashMap, not Map.copyOf: Map.copyOf returns an ImmutableCollections.MapN
        // whose iteration order is salted per JVM run, so the same inputs would persist
        // differently-ordered intermediates across restarts. Preserving insertion order
        // keeps the persisted findings byte-reproducible.
        return new CalcResult("COMPUTED", value,
                Collections.unmodifiableMap(new LinkedHashMap<>(intermediates)), null);
    }

    public static CalcResult error(String message) {
        return new CalcResult("ERROR", null, Map.of(), message);
    }

    @JsonIgnore
    public boolean isComputed() {
        return "COMPUTED".equals(status);
    }
}
