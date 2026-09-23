package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The income domain contract is opt-in by its own schemaVersion marker, so every run of a release
 * promoted before this contract existed keeps validating exactly as it did.
 */
class EnvelopeValidatorIncomeDomainTest {

    private final EnvelopeValidator validator = new EnvelopeValidator();
    private final ObjectMapper om = new ObjectMapper();

    static final String VALID_DOMAIN = """
            {"schemaVersion":"income-domain-v2",
             "borrowers":[{"name":"Borrower 1","sources":[
               {"type":"W-2 base","employer":"ACME WIDGETS LLC","factIds":["f1"],
                "calcIds":["src1-monthly-rate"],"qualifyingCalcId":"src1-monthly-rate",
                "monthly":null,"urlaStatedMonthly":9000,"varianceCalcId":null,
                "concerns":[],"confidence":"HIGH"}]}],
             "totalCalcId":null,"reconciliationCalcId":null,"opportunities":[],"gaps":[]}
            """;

    private JsonNode envelopeWithDomain(String domainJson) throws Exception {
        ObjectNode env = om.createObjectNode();
        env.set("domain", om.readTree(domainJson));
        return env;
    }

    private ObjectNode source(JsonNode env) {
        return (ObjectNode) env.at("/domain/borrowers/0/sources/0");
    }

    @Test
    void aValidDomainPasses() throws Exception {
        assertEquals(List.of(), validator.validateDomain(envelopeWithDomain(VALID_DOMAIN)));
    }

    @Test
    void aDomainWithoutTheMarkerIsNotValidated() throws Exception {
        // The shape the currently promoted release's prompt produces: no schemaVersion, no confidence.
        String legacy = """
                {"borrowers":[{"name":"B","sources":[{"type":"W-2 base","calcIds":["c1"]}]}],
                 "totalCalcId":"t"}
                """;
        assertEquals(List.of(), validator.validateDomain(envelopeWithDomain(legacy)));
        assertEquals(List.of(), validator.validateDomain(om.createObjectNode()));
    }

    @Test
    void aMissingConfidenceIsAShapeErrorRootedAtDomain() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        source(env).remove("confidence");
        List<String> errors = validator.validateDomain(env);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("$.domain.borrowers[0].sources[0]"), errors.get(0));
        assertTrue(errors.get(0).contains("confidence"), errors.get(0));
    }

    @Test
    void confidenceMustBeALevelNotAScore() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        source(env).put("confidence", 0.9);
        assertFalse(validator.validateDomain(env).isEmpty());
    }

    @Test
    void aModelTypedMonthlyWithNoQualifyingCalculationIsAccepted() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        source(env).putNull("qualifyingCalcId");
        source(env).put("monthly", 8733.33);
        assertEquals(List.of(), validator.validateDomain(env));
    }

    @Test
    void aDanglingCalculationIdIsNotAShapeError() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        source(env).put("qualifyingCalcId", "never-requested");
        assertEquals(List.of(), validator.validateDomain(env));
    }

    @Test
    void theWrongMarkerAndUnknownMembersAreRejected() throws Exception {
        JsonNode wrong = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) wrong.get("domain")).put("schemaVersion", "income-domain-v9");
        assertFalse(validator.validateDomain(wrong).isEmpty());

        JsonNode extra = envelopeWithDomain(VALID_DOMAIN);
        source(extra).put("monthlyIncome", 1);
        assertFalse(validator.validateDomain(extra).isEmpty());
    }

    @Test
    void engineWrittenMembersAreAllowedSoAnEnrichedDomainStillValidates() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ObjectNode computed = source(env).putObject("computed");
        computed.put("monthly", 10119.83);
        computed.put("method", "income.monthly_from_rate.v1");
        computed.put("frequency", "BIWEEKLY");
        computed.put("frequencyInferred", false);
        computed.put("basis", "CALCULATOR");
        ((ObjectNode) env.get("domain")).put("computedTotalMonthly", 10119.83);
        assertEquals(List.of(), validator.validateDomain(env));
    }

    @Test
    void duplicateEmploymentMembersAreEngineWrittenAndValidate() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ObjectNode computed = source(env).putObject("computed");
        computed.put("monthly", 10892.72);
        computed.put("method", "income.monthly_from_annual.v1");
        computed.put("frequency", "ANNUAL");
        computed.put("frequencyInferred", false);
        computed.put("basis", "CALCULATOR");
        computed.put("duplicateOf", 1);
        ((ObjectNode) env.get("domain")).put("computedTotalMonthly", 12387.48);
        ((ObjectNode) env.get("domain")).put("totalAdjustedForDuplicates", true);
        ((ObjectNode) env.get("domain")).putObject("adjustedReconciliation").put("method", "income.variance.v1");
        assertEquals(List.of(), validator.validateDomain(env));
        computed.put("duplicateOf", -1);
        assertFalse(validator.validateDomain(env).isEmpty());
    }
}
