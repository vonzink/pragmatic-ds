package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Trip and no-trip proof for the V44 {@code SCHEDULE_D} and {@code SCHEDULE_F} packs.
 *
 * <p>The anchor sets were verified exclusive against ten other blank forms before the packs were
 * written, which settles one half of the question and not the other: a GENERATED fixture is not a
 * real form, and a pack can pass verification against the official PDF while matching nothing at
 * all on the page a test actually feeds it. {@link #every_verified_anchor_actually_fires_on_its_own_fixture}
 * is that missing half — it asserts the anchor IDS, not a score, so a fixture that drifts away
 * from the form's wording says WHICH phrase it stopped printing rather than just failing a number.
 *
 * <p>The no-trip test is Schedule C against Schedule F. The two forms share 648 phrases and a
 * masthead shape ("Profit or Loss From …"), which is the specific confusion this pack pair exists
 * to survive; {@link com.pragmaticds.docengine.classification.CrossConfusionIT} holds the whole matrix,
 * and this names the one cell that drove every weighting decision in V44 §2.
 */
class ScheduleDAndFPackTripIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    private void runStage(UUID packageId, ProcessingStatus stage, String idem) {
        ParserPort.StageOutcome outcome =
                parserPort.run(
                        new ParserPort.StageRequest(UUID.randomUUID(), packageId, stage, 1, idem));
        assertThat(outcome.success()).isTrue();
    }

    private Map<String, Object> currentResult(UUID pageId) {
        return jdbc.queryForMap(
                "SELECT * FROM classification_result WHERE subject_id = ? AND is_current", pageId);
    }

    private List<String> matchedAnchors(Map<String, Object> result) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode anchor : JSON.readTree(result.get("evidence").toString()).get("anchors")) {
            ids.add(anchor.get("anchorId").asText());
        }
        return ids;
    }

    /** The recorded score entry for {@code packType} — present on wins AND unknowns. */
    private JsonNode scoreEntry(Map<String, Object> result, String packType) throws Exception {
        JsonNode evidence = JSON.readTree(result.get("evidence").toString());
        for (JsonNode score : evidence.get("scores")) {
            if (score.get("packType").asText().equals(packType)) {
                return score;
            }
        }
        throw new AssertionError(packType + " absent from the evidence score list");
    }

    private RulePack seededPack(String type) {
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active " + type + " pack for the dev org"));
    }

    private List<UUID> classifyFixture(String fixture) {
        UUID packageId = insertPackage(fixture + "-df-it");
        List<UUID> pageIds = insertFixturePages(packageId, fixture);
        runStage(packageId, ProcessingStatus.CLASSIFYING, "df-classify-" + packageId);
        return pageIds;
    }

    // ── trip ────────────────────────────────────────────────────────────────

    @Test
    void the_schedule_d_fixture_classifies_SCHEDULE_D() {
        for (UUID pageId : classifyFixture("schedule_d")) {
            Map<String, Object> result = currentResult(pageId);
            assertThat(result.get("document_type_code")).isEqualTo("SCHEDULE_D");
            assertThat((BigDecimal) result.get("confidence"))
                    .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
            assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
        }
    }

    @Test
    void the_schedule_f_fixture_classifies_SCHEDULE_F() {
        for (UUID pageId : classifyFixture("schedule_f")) {
            Map<String, Object> result = currentResult(pageId);
            assertThat(result.get("document_type_code")).isEqualTo("SCHEDULE_F");
            assertThat((BigDecimal) result.get("confidence"))
                    .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
            assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
        }
    }

    @Test
    void every_verified_anchor_actually_fires_on_its_own_fixture() throws Exception {
        // The gap the plan names: verification ran against docs/reference-forms, and the
        // fixture is generated. Named, not counted — an anchor lost to a paraphrase in
        // generate.py prints as a missing ID here, which is the finding.
        Map<String, Object> scheduleD = currentResult(classifyFixture("schedule_d").get(0));
        assertThat(matchedAnchors(scheduleD))
                .as("SCHEDULE_D anchors the schedule_d fixture prints")
                .containsExactlyInAnyOrder(
                        "sd-form", "sd-title", "sd-short", "sd-long", "sd-8949", "sd-carryover");

        Map<String, Object> scheduleF = currentResult(classifyFixture("schedule_f").get(0));
        assertThat(matchedAnchors(scheduleF))
                .as("SCHEDULE_F anchors the schedule_f fixture prints")
                .containsExactlyInAnyOrder(
                        "sf-form", "sf-title", "sf-custom", "sf-fert", "sf-vet", "sf-accrual");
    }

    @Test
    void no_anchor_of_weight_three_or_more_is_shared_between_the_two_packs() {
        // The V10 rule as a standing assertion rather than a one-off script run: the
        // heavy anchors are the ones that can hand a page to the wrong type on their own.
        Set<String> heavyD = heavyPatterns("SCHEDULE_D");
        Set<String> heavyF = heavyPatterns("SCHEDULE_F");
        assertThat(heavyD).isNotEmpty();
        assertThat(heavyF).isNotEmpty();
        assertThat(heavyD).doesNotContainAnyElementsOf(heavyF);
    }

    private Set<String> heavyPatterns(String type) {
        return seededPack(type).anchors().stream()
                .filter(anchor -> anchor.weight() >= 3)
                .map(Anchor::pattern)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ── no-trip ─────────────────────────────────────────────────────────────

    @Test
    void the_schedule_c_fixture_stays_far_below_the_SCHEDULE_F_bar() throws Exception {
        // 648 shared phrases, and this is what they are worth: nothing. Every one of
        // them is unanchored in V44 §2 precisely so this number cannot creep.
        Map<String, Object> result = currentResult(classifyFixture("schedule_c").get(0));
        assertThat(result.get("document_type_code")).isEqualTo("SCHEDULE_C");
        assertThat(scoreEntry(result, "SCHEDULE_F").get("score").asDouble())
                .as("SCHEDULE_F's score on a Schedule C page")
                .isLessThan(seededPack("SCHEDULE_F").minConfidence());
    }

    @Test
    void the_schedule_f_fixture_stays_far_below_the_SCHEDULE_C_bar() throws Exception {
        // The other direction, and the one that needed proving: schedule_f prints
        // "Name of proprietor" verbatim — a real SCHEDULE_C anchor — and "(Form 1040)"
        // and "Attach to Form 1040" besides. Worth 0.10 together, against a 0.60 bar.
        Map<String, Object> result = currentResult(classifyFixture("schedule_f").get(0));
        assertThat(result.get("document_type_code")).isEqualTo("SCHEDULE_F");
        assertThat(scoreEntry(result, "SCHEDULE_C").get("score").asDouble())
                .as("SCHEDULE_C's score on a Schedule F page")
                .isLessThan(seededPack("SCHEDULE_C").minConfidence());
    }

    @Test
    void the_tax_return_fixture_never_qualifies_for_either_new_pack() throws Exception {
        // The reason both title anchors are the PARENTHESIZED-form regex: the 1040
        // prints "Attach Schedule D if required" on page 1 of every return ever filed,
        // and a bare "Schedule D" literal would have made this test impossible to pass.
        Map<String, Object> result = currentResult(classifyFixture("tax_return").get(0));
        assertThat(result.get("document_type_code")).isEqualTo("TAX_RETURN");
        assertThat(scoreEntry(result, "SCHEDULE_D").get("score").asDouble())
                .isLessThan(seededPack("SCHEDULE_D").minConfidence());
        assertThat(scoreEntry(result, "SCHEDULE_F").get("score").asDouble())
                .isLessThan(seededPack("SCHEDULE_F").minConfidence());
    }
}
