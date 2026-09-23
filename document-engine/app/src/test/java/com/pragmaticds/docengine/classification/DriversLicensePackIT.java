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
 * The DRIVERS_LICENSE pack (V11, Spec 3 T8): trips on the card fixture, stays silent on a
 * paystub, and cannot qualify on its non-exclusive anchors alone — the V10 exclusivity
 * invariant (non-exclusive weights sum to at most 5 of targetScore 10), generalized to
 * every Spec 3 pack.
 */
class DriversLicensePackIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    /**
     * Anchor ids a NON-license document could plausibly carry: DOB/EXP/CLASS/ISS are field
     * abbreviations, not proof the page IS a license. Their weights must not reach the
     * pack's own threshold — the DL analogue of W2PackExclusivityIT.
     */
    private static final Set<String> NON_EXCLUSIVE_ANCHORS = Set.of("dob", "exp", "class", "iss");

    private ParserPort.StageOutcome classify(UUID packageId) {
        return parserPort.run(
                new ParserPort.StageRequest(
                        UUID.randomUUID(), packageId, ProcessingStatus.CLASSIFYING, 1, "dl-pack-idem"));
    }

    private Map<String, Object> currentResult(UUID pageId) {
        return jdbc.queryForMap(
                "SELECT * FROM classification_result WHERE subject_id = ? AND is_current", pageId);
    }

    private RulePack seededPack() {
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals("DRIVERS_LICENSE"))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("no active DRIVERS_LICENSE pack for the dev org"));
    }

    private JsonNode packScore(JsonNode evidence, String packType) {
        return java.util.stream.StreamSupport.stream(evidence.get("scores").spliterator(), false)
                .filter(node -> node.get("packType").asText().equals(packType))
                .findFirst()
                .orElseThrow(() -> new AssertionError(packType + " pack absent from the score list"));
    }

    /** Every evidence anchor must resolve to real text_span rows on the page, box for box. */
    private void assertEvidenceResolves(UUID pageId, JsonNode evidence) {
        JsonNode anchors = evidence.get("anchors");
        assertThat(anchors.size()).isGreaterThan(0);
        for (JsonNode anchor : anchors) {
            JsonNode spanIds = anchor.get("spanIds");
            assertThat(spanIds.size()).isGreaterThan(0);
            assertThat(anchor.get("boxes").size()).isEqualTo(spanIds.size());
            for (int i = 0; i < spanIds.size(); i++) {
                Map<String, Object> span =
                        jdbc.queryForMap("SELECT * FROM text_span WHERE id = ?", spanIds.get(i).asLong());
                assertThat(span.get("page_id")).isEqualTo(pageId);
            }
            // Offsets and ids only — never matched text.
            assertThat(anchor.has("text")).isFalse();
        }
    }

    @Test
    void the_drivers_license_fixture_classifies_DRIVERS_LICENSE_with_resolvable_evidence()
            throws Exception {
        UUID packageId = insertPackage("dl-trip-it");
        List<UUID> pageIds = insertFixturePages(packageId, "drivers_license");

        assertThat(classify(packageId).success()).isTrue();

        Map<String, Object> result = currentResult(pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("DRIVERS_LICENSE");
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
        assertThat(result.get("method")).isEqualTo("RULE_ANCHOR");
        assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
        assertEvidenceResolves(pageIds.get(0), JSON.readTree(result.get("evidence").toString()));
    }

    @Test
    void a_paystub_page_does_not_qualify_against_the_DRIVERS_LICENSE_pack() throws Exception {
        UUID packageId = insertPackage("dl-notrip-it");
        List<UUID> pageIds = insertFixturePages(packageId, "native_paystub");

        assertThat(classify(packageId).success()).isTrue();

        Map<String, Object> result = currentResult(pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("PAYSTUB");

        JsonNode dlScore =
                packScore(JSON.readTree(result.get("evidence").toString()), "DRIVERS_LICENSE");
        double score = dlScore.get("score").asDouble();
        double minConfidence = dlScore.get("minConfidence").asDouble();
        // The EXACT qualification predicate (PageClassifier#decide) — >= mirrored deliberately.
        assertThat(score > 0 && score >= minConfidence)
                .as("DRIVERS_LICENSE must not qualify on a paystub (score %s)", score)
                .isFalse();
    }

    @Test
    void non_exclusive_anchors_cannot_reach_the_packs_threshold_alone() {
        RulePack pack = seededPack();
        // Guards the test itself: a pack that renamed these anchors away would sum 0 and
        // pass vacuously (the W2PackExclusivityIT lesson).
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
