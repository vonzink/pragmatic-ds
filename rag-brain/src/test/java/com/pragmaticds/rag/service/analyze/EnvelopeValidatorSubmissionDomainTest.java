package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The submission domain contract is opt-in by its own schemaVersion marker, validated against its
 * own file, and dispatched by marker so the income contract keeps validating exactly as before.
 */
class EnvelopeValidatorSubmissionDomainTest {

    private final EnvelopeValidator validator = new EnvelopeValidator();
    private final ObjectMapper om = new ObjectMapper();

    static final String VALID_DOMAIN = """
            {"schemaVersion":"submission-domain-v1",
             "documents":[
               {"documentId":"d1","kind":"CONTRACT","date":"2026-09-01","title":"Contract to Buy and Sell","signedBy":["Jane Doe","Sam Seller"]},
               {"documentId":"d2","kind":"COUNTER","date":"2026-09-02","title":"Counterproposal #1","signedBy":["Sam Seller","Jane Doe"]}
             ],
             "effectiveTerms":{
               "buyers":["Jane Doe"],"sellers":["Sam Seller"],
               "propertyAddress":"123 Main St, Denver, CO 80202",
               "salesPrice":412000,
               "earnestMoney":{"amount":5000,"holder":"Land Title Guarantee","dueDate":"2026-09-05"},
               "sellerCredits":{"amount":8000,"appliesTo":"closing costs and prepaids"},
               "closingDate":"2026-10-15",
               "financingContingency":{"present":true,"loanType":"Conventional","deadline":"2026-10-08"},
               "appraisalContingency":{"present":true,"deadline":"2026-10-06"},
               "inspectionContingency":{"present":true,"deadline":"2026-09-12"},
               "personalPropertyIncluded":["refrigerator","washer","dryer"],
               "homeWarranty":null,
               "assignable":false,
               "listingAgent":"A. Agent / Big Brokerage","sellingAgent":"B. Agent / Other Brokerage",
               "setBy":{"salesPrice":"d2","closingDate":"d1"}
             },
             "conflicts":[
               {"field":"salesPrice","status":"CONFLICT","applicationValue":"410000","documentValue":"412000","factId":"f1","severity":"HIGH","note":"Counter #1 raised the price to $412,000; the application still shows $410,000."},
               {"field":"propertyAddress","status":"MATCH","applicationValue":"123 Main St, Denver, CO 80202","documentValue":"123 Main St, Denver, CO 80202","factId":"f2","severity":null,"note":"Matches."},
               {"field":"earnestMoney","status":"NOT_COMPARABLE","applicationValue":null,"documentValue":"5000","factId":"f3","severity":null,"note":"Earnest money is not on the application."}
             ],
             "keyDates":[
               {"kind":"ACCEPTANCE","date":"2026-09-02","setBy":"d2","factId":"f4","note":null},
               {"kind":"INSPECTION_DEADLINE","date":"2026-09-12","setBy":"d1","factId":"f5","note":null},
               {"kind":"CLOSING","date":"2026-10-15","setBy":"d1","factId":"f6","note":"Counter #1 did not move closing."}
             ]}
            """;

    private JsonNode envelopeWithDomain(String domainJson) throws Exception {
        ObjectNode env = om.createObjectNode();
        env.set("domain", om.readTree(domainJson));
        return env;
    }

    @Test
    void aValidSubmissionDomainPasses() throws Exception {
        assertEquals(List.of(), validator.validateDomain(envelopeWithDomain(VALID_DOMAIN)));
    }

    @Test
    void theIncomeContractStillValidatesAgainstItsOwnSchema() throws Exception {
        // Dispatch by marker: the income fixture must not be judged by the submission schema.
        assertEquals(List.of(), validator.validateDomain(
                envelopeWithDomain(EnvelopeValidatorIncomeDomainTest.VALID_DOMAIN)));
    }

    @Test
    void anUnknownMarkerIsExactlyOneError() throws Exception {
        List<String> errors = validator.validateDomain(
                envelopeWithDomain("{\"schemaVersion\":\"submission-domain-v9\"}"));
        assertEquals(List.of("$.domain.schemaVersion: unknown domain schema 'submission-domain-v9'"), errors);
    }

    @Test
    void aBadConflictFieldIsRootedAtTheRow() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain/conflicts/0")).put("field", "purchasePrice");
        List<String> errors = validator.validateDomain(env);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.domain.conflicts[0].field")), errors.toString());
    }

    @Test
    void aConflictWithoutSeverityIsRejected() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain/conflicts/0")).putNull("severity");
        assertFalse(validator.validateDomain(env).isEmpty());
    }

    @Test
    void aMatchCarryingASeverityIsRejected() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain/conflicts/1")).put("severity", "LOW");
        assertFalse(validator.validateDomain(env).isEmpty());
    }

    @Test
    void duplicateConflictFieldsAreRejected() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain/conflicts/1")).put("field", "salesPrice");
        List<String> errors = validator.validateDomain(env);
        assertTrue(errors.contains("$.domain.conflicts: duplicate field 'salesPrice'"), errors.toString());
    }

    @Test
    void duplicateKeyDateKindsAreRejected() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain/keyDates/1")).put("kind", "CLOSING");
        List<String> errors = validator.validateDomain(env);
        assertTrue(errors.contains("$.domain.keyDates: duplicate kind 'CLOSING'"), errors.toString());
    }

    @Test
    void unknownMembersAreRejected() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain")).put("extra", 1);
        assertFalse(validator.validateDomain(env).isEmpty());
    }

    @Test
    void aDateMustBeIsoFormatted() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain/keyDates/0")).put("date", "9/2/2026");
        assertFalse(validator.validateDomain(env).isEmpty());
    }

    @Test
    void aRequiredMarkerPassesWithTheValidFixture() throws Exception {
        assertEquals(List.of(), validator.validateDomain(
                envelopeWithDomain(VALID_DOMAIN), "submission-domain-v1"));
    }

    @Test
    void aRequiredMarkerIsMissingWhenNoDomainIsSent() {
        ObjectNode env = om.createObjectNode();
        List<String> errors = validator.validateDomain(env, "submission-domain-v1");
        assertEquals(List.of("$.domain.schemaVersion: required — this analyzer declares the "
                + "submission-domain-v1 contract"), errors);
    }

    @Test
    void aRequiredMarkerRejectsADifferentDomain() throws Exception {
        List<String> errors = validator.validateDomain(
                envelopeWithDomain(EnvelopeValidatorIncomeDomainTest.VALID_DOMAIN),
                "submission-domain-v1");
        assertEquals(List.of("$.domain.schemaVersion: must be 'submission-domain-v1' "
                + "for this analyzer but was 'income-domain-v2'"), errors);
    }

    @Test
    void aNullRequiredMarkerBehavesAsTheLegacyOneArgMethod() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        assertEquals(validator.validateDomain(env), validator.validateDomain(env, null));
    }

    @Test
    void aSetByKeyOutsideTheTermListIsRejected() throws Exception {
        JsonNode env = envelopeWithDomain(VALID_DOMAIN);
        ((ObjectNode) env.at("/domain/effectiveTerms/setBy")).put("slaesPrice", "d2");
        assertFalse(validator.validateDomain(env).isEmpty());
    }
}
