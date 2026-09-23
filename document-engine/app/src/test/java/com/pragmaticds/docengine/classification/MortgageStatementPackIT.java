package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The MORTGAGE_STATEMENT pack (V11, Spec 3 T8) against its riskiest confusable neighbor,
 * BANK_STATEMENT (design section 4): the servicing statement trips its own pack, the bank
 * fixture does not qualify against it, the mortgage fixture does not qualify against the
 * bank pack, and the exclusivity invariant holds (non-exclusive weights sum to at most 5).
 */
class MortgageStatementPackIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    /** Shared billing vocabulary: HOI mortgagee clauses print loan numbers; any statement
     * has a payment due date and a rate. Must not reach the threshold alone. */
    private static final Set<String> NON_EXCLUSIVE_ANCHORS =
            Set.of("loan-number", "payment-due", "interest-rate");

    private ParserPort.StageOutcome classify(UUID packageId) {
        return parserPort.run(
                new ParserPort.StageRequest(
                        UUID.randomUUID(), packageId, ProcessingStatus.CLASSIFYING, 1, "ms-pack-idem"));
    }

    private Map<String, Object> currentResult(UUID pageId) {
        return jdbc.queryForMap(
                "SELECT * FROM classification_result WHERE subject_id = ? AND is_current", pageId);
    }

    private RulePack seededPack() {
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals("MORTGAGE_STATEMENT"))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("no active MORTGAGE_STATEMENT pack for the dev org"));
    }

    private JsonNode packScore(JsonNode evidence, String packType) {
        return java.util.stream.StreamSupport.stream(evidence.get("scores").spliterator(), false)
                .filter(node -> node.get("packType").asText().equals(packType))
                .findFirst()
                .orElseThrow(() -> new AssertionError(packType + " pack absent from the score list"));
    }

    /** The exact qualification predicate from PageClassifier#decide, negated. */
    private void assertDoesNotQualify(JsonNode evidence, String packType) {
        JsonNode score = packScore(evidence, packType);
        double s = score.get("score").asDouble();
        double min = score.get("minConfidence").asDouble();
        assertThat(s > 0 && s >= min)
                .as("%s must not qualify here (score %s, minConfidence %s)", packType, s, min)
                .isFalse();
    }

    @Test
    void the_mortgage_statement_fixture_classifies_MORTGAGE_STATEMENT() throws Exception {
        UUID packageId = insertPackage("ms-trip-it");
        List<UUID> pageIds = insertFixturePages(packageId, "mortgage_statement");

        assertThat(classify(packageId).success()).isTrue();

        Map<String, Object> result = currentResult(pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("MORTGAGE_STATEMENT");
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
        assertThat(result.get("method")).isEqualTo("RULE_ANCHOR");
        assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
    }

    @Test
    void no_bank_statement_page_qualifies_against_the_MORTGAGE_STATEMENT_pack() throws Exception {
        UUID packageId = insertPackage("ms-notrip-bank-it");
        List<UUID> pageIds = insertFixturePages(packageId, "bank_statement");

        assertThat(classify(packageId).success()).isTrue();

        for (UUID pageId : pageIds) {
            Map<String, Object> result = currentResult(pageId);
            assertThat(result.get("document_type_code")).isEqualTo("BANK_STATEMENT");
            assertDoesNotQualify(
                    JSON.readTree(result.get("evidence").toString()), "MORTGAGE_STATEMENT");
        }
    }

    @Test
    void the_mortgage_statement_fixture_does_not_qualify_against_the_BANK_STATEMENT_pack()
            throws Exception {
        UUID packageId = insertPackage("ms-notrip-reverse-it");
        List<UUID> pageIds = insertFixturePages(packageId, "mortgage_statement");

        assertThat(classify(packageId).success()).isTrue();

        Map<String, Object> result = currentResult(pageIds.get(0));
        // Both halves matter: the page must land in ITS type, and the neighbor pack's own
        // recorded score must fail the predicate — UNKNOWN-by-tie would prove nothing.
        assertThat(result.get("document_type_code")).isEqualTo("MORTGAGE_STATEMENT");
        assertDoesNotQualify(JSON.readTree(result.get("evidence").toString()), "BANK_STATEMENT");
    }

    @Test
    void non_exclusive_anchors_cannot_reach_the_packs_threshold_alone() {
        RulePack pack = seededPack();
        assertThat(
                        pack.anchors().stream()
                                .map(Anchor::id)
                                .filter(NON_EXCLUSIVE_ANCHORS::contains)
                                .toList())
                .containsExactlyInAnyOrderElementsOf(NON_EXCLUSIVE_ANCHORS);

        double shared =
                pack.anchors().stream()
                        .filter(anchor -> NON_EXCLUSIVE_ANCHORS.contains(anchor.id()))
                        .mapToDouble(Anchor::weight)
                        .sum();
        assertThat(Math.min(1.0, shared / pack.targetScore())).isLessThan(pack.minConfidence());
    }
}
