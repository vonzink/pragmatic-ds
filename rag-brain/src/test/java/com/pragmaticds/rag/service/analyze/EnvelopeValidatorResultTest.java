package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The engine-populated calculations[].result extension of envelope v2 (income slice). */
class EnvelopeValidatorResultTest {

    private final EnvelopeValidator validator = new EnvelopeValidator();
    private final ObjectMapper om = new ObjectMapper();

    private static final String BASE = """
            {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"r",
             "facts":[],"assumptions":[],"warnings":[],"recommendations":[],
             "calculations":[%s],"missingItems":[],"citations":[],"confidence":0.9}
            """;

    private JsonNode env(String calc) throws Exception {
        return om.readTree(BASE.formatted(calc));
    }

    @Test
    void computedResultIsValid() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1",
                 "inputs":{"rate":25.5,"frequency":"HOURLY","hoursPerWeek":40},
                 "result":{"status":"COMPUTED","value":4420.00,
                           "intermediates":{"annualized":53040.00},"rounding":"HALF_UP,2dp"}}
                """));
        assertEquals(List.of(), errors);
    }

    @Test
    void errorResultIsValid() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1",
                 "inputs":{},
                 "result":{"status":"ERROR","value":null,"error":"missing or non-numeric input: rate"}}
                """));
        assertEquals(List.of(), errors);
    }

    @Test
    void requestWithoutResultStillValid() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{}}
                """));
        assertEquals(List.of(), errors);
    }

    @Test
    void unknownResultKeyRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"COMPUTED","value":1,"llmOpinion":"looks right"}}
                """));
        assertFalse(errors.isEmpty());
    }

    @Test
    void resultWithoutStatusRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"value":1}}
                """));
        assertFalse(errors.isEmpty());
    }

    @Test
    void unknownResultStatusRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"MAYBE","value":1}}
                """));
        assertFalse(errors.isEmpty());
    }

    // The status-discriminated oneOf: a COMPUTED result must carry a number, an ERROR
    // must carry a message, and neither may borrow the other's fields. Without this the
    // engine could emit {"status":"COMPUTED"} with no value and still validate.

    @Test
    void computedWithoutValueRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"COMPUTED","rounding":"HALF_UP,2dp"}}
                """));
        assertFalse(errors.isEmpty());
    }

    @Test
    void computedWithNullValueRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"COMPUTED","value":null}}
                """));
        assertFalse(errors.isEmpty());
    }

    @Test
    void errorWithoutMessageRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"ERROR","value":null}}
                """));
        assertFalse(errors.isEmpty());
    }

    @Test
    void errorWithEmptyMessageRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"ERROR","value":null,"error":""}}
                """));
        assertFalse(errors.isEmpty());
    }

    @Test
    void errorCarryingAValueRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"ERROR","value":42,"error":"boom"}}
                """));
        assertFalse(errors.isEmpty());
    }

    /**
     * A serialized CalcResult carries {@code "error": null} on COMPUTED; the ERROR branch
     * requires a message and the COMPUTED branch forbids the property, so a naive
     * valueToTree(calcResult) is rejected rather than silently accepted. Task 3's executor
     * builds the node by hand for exactly this reason.
     */
    @Test
    void computedCarryingNullErrorRejected() throws Exception {
        List<String> errors = validator.validate(env("""
                {"id":"c1","name":"Base pay","method":"income.monthly_from_rate.v1","inputs":{},
                 "result":{"status":"COMPUTED","value":4420.00,"error":null}}
                """));
        assertFalse(errors.isEmpty());
    }
}
