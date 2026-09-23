package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The CLASSIFYING stage against fixture truth spans: each seeded type classifies on its own
 * fixture with evidence that resolves to REAL text_span rows; the ambiguous fixture lands
 * UNKNOWN with BOTH weak matches recorded; a brand-new org-scoped type + pack classifies with NO
 * code change (plan acceptance criterion 4); an org pack shadows the global one; and
 * reclassification supersedes append-only.
 */
class ClassificationStageIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    private StageOutcome runStage(UUID packageId, ProcessingStatus stage) {
        return parserPort.run(
                new ParserPort.StageRequest(UUID.randomUUID(), packageId, stage, 1, "it-idem"));
    }

    private Map<String, Object> currentResult(UUID pageId) {
        return jdbc.queryForMap(
                "SELECT * FROM classification_result WHERE subject_id = ? AND is_current", pageId);
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
                JsonNode box = anchor.get("boxes").get(i);
                assertThat((BigDecimal) span.get("x")).isEqualByComparingTo(box.get("x").decimalValue());
                assertThat((BigDecimal) span.get("y")).isEqualByComparingTo(box.get("y").decimalValue());
                assertThat((BigDecimal) span.get("width"))
                        .isEqualByComparingTo(box.get("width").decimalValue());
                assertThat((BigDecimal) span.get("height"))
                        .isEqualByComparingTo(box.get("height").decimalValue());
            }
            // Offsets and ids only — never matched text.
            assertThat(anchor.has("text")).isFalse();
            assertThat(anchor.get("range").has("start")).isTrue();
        }
    }

    private void assertClassified(String fixture, String expectedType) throws Exception {
        assertClassified(fixture, expectedType, "1.0.0");
    }

    /**
     * @param expectedPackVersion the pack that must have DECIDED — recorded per result, so it is
     *     asserted rather than assumed. W2 is on 1.1.0 since V10 reweighted it by anchor
     *     exclusivity, and PAYSTUB since V34 added the Oracle-HCM payroll vocabulary (a real stub
     *     from that provider scored 0.40 on 1.0.0); the other built-ins are still on their V6
     *     1.0.0.
     */
    private void assertClassified(String fixture, String expectedType, String expectedPackVersion)
            throws Exception {
        UUID packageId = insertPackage(fixture + "-it");
        List<UUID> pageIds = insertFixturePages(packageId, fixture);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.CLASSIFYING);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.outputDigest()).isNotNull();

        for (UUID pageId : pageIds) {
            Map<String, Object> result = currentResult(pageId);
            assertThat(result.get("document_type_code")).isEqualTo(expectedType);
            assertThat((BigDecimal) result.get("confidence"))
                    .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
            assertThat(result.get("subject_type")).isEqualTo("PAGE");
            assertThat(result.get("method")).isEqualTo("RULE_ANCHOR");
            assertThat(result.get("rule_pack_version")).isEqualTo(expectedPackVersion);
            assertThat(result.get("org_id")).isEqualTo(ORG_DEV);
            assertEvidenceResolves(pageId, JSON.readTree(result.get("evidence").toString()));
        }
    }

    @Test
    void the_w2_fixture_classifies_W2_with_resolvable_evidence() throws Exception {
        assertClassified("w2_form", "W2", "1.1.0");
    }

    @Test
    void every_bank_statement_page_classifies_BANK_STATEMENT() throws Exception {
        // 1.1.0 since V38 superseded the pack with the vocabulary real banks print; the synthetic
        // fixture still qualifies on the anchors it keeps, and the DECIDING version is asserted.
        assertClassified("bank_statement", "BANK_STATEMENT", "1.1.0");
    }

    @Test
    void the_paystub_fixture_classifies_PAYSTUB() throws Exception {
        assertClassified("native_paystub", "PAYSTUB", "1.2.0");
    }

    @Test
    void the_ambiguous_page_lands_UNKNOWN_with_both_weak_matches_as_evidence() throws Exception {
        UUID packageId = insertPackage("ambiguous-it");
        List<UUID> pageIds = insertFixturePages(packageId, "ambiguous");

        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();

        Map<String, Object> result = currentResult(pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("UNKNOWN");
        // The best loser's confidence — recorded, and below every pack's threshold.
        assertThat((BigDecimal) result.get("confidence")).isEqualByComparingTo("0.2");
        assertThat(result.get("rule_pack_version")).isNull();

        JsonNode evidence = JSON.readTree(result.get("evidence").toString());
        List<String> anchorIds = new java.util.ArrayList<>();
        evidence.get("anchors").forEach(anchor -> anchorIds.add(anchor.get("anchorId").asText()));
        // The reviewer sees WHY it was ambiguous: the paystub match AND the bank match.
        assertThat(anchorIds).containsExactlyInAnyOrder("net-pay", "end-balance");
        assertEvidenceResolves(pageIds.get(0), evidence);
    }

    @Test
    void a_new_org_scoped_type_and_pack_classify_letters_with_no_code_change() throws Exception {
        // Plan acceptance criterion 4: adding a fourth document type is a document_type row plus
        // a rule-pack row — data, not Java.
        jdbc.update(
                """
                INSERT INTO document_type (org_id, code, display_name, category)
                VALUES (?, 'LETTER', 'Correspondence Letter', NULL)
                """,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (org_id, document_type_code, version, min_confidence, definition)
                VALUES (?, 'LETTER', '1.0.0', 0.6, '{
                  "targetScore": 4,
                  "anchors": [
                    {"id": "dear-homeowner", "kind": "literal", "pattern": "Dear Homeowner", "weight": 3},
                    {"id": "sincerely",      "kind": "literal", "pattern": "Sincerely",      "weight": 1}
                  ]}'::jsonb)
                """,
                ORG_DEV);
        rulePackLoader.invalidate(ORG_DEV);
        try {
            // The letter pages of the combined fixture — UNKNOWN under the built-ins alone.
            JsonNode combined = truth("combined_package").get("pages");
            ArrayNode letters = JSON.createArrayNode();
            letters.add(combined.get(12)).add(combined.get(13));
            UUID packageId = insertPackage("letter-it");
            List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, letters);

            assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();

            for (UUID pageId : pageIds) {
                Map<String, Object> result = currentResult(pageId);
                assertThat(result.get("document_type_code")).isEqualTo("LETTER");
                assertThat((BigDecimal) result.get("confidence"))
                        .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
                assertEvidenceResolves(pageId, JSON.readTree(result.get("evidence").toString()));
            }
        } finally {
            jdbc.update("DELETE FROM classification_rule_pack WHERE document_type_code = 'LETTER'");
            jdbc.update("DELETE FROM document_type WHERE code = 'LETTER'");
            rulePackLoader.invalidateAll();
        }
    }

    @Test
    void an_org_pack_shadows_the_global_one_and_its_threshold_applies() {
        // An org-private PAYSTUB pack whose demands the fixture cannot meet: if the GLOBAL pack
        // were still consulted the page would score 1.0 and win — landing UNKNOWN proves the org
        // pack replaced it AND that its absurd threshold is enforced.
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (org_id, document_type_code, version, min_confidence, definition)
                VALUES (?, 'PAYSTUB', '1.1.0', 0.99, '{
                  "targetScore": 100,
                  "anchors": [
                    {"id": "pay-period", "kind": "literal", "pattern": "Pay Period", "weight": 3}
                  ]}'::jsonb)
                """,
                ORG_DEV);
        rulePackLoader.invalidate(ORG_DEV);
        try {
            UUID packageId = insertPackage("shadow-it");
            List<UUID> pageIds = insertFixturePages(packageId, "native_paystub");

            assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();

            Map<String, Object> result = currentResult(pageIds.get(0));
            assertThat(result.get("document_type_code")).isEqualTo("UNKNOWN");
            assertThat((BigDecimal) result.get("confidence")).isLessThan(new BigDecimal("0.99"));
        } finally {
            jdbc.update(
                    "DELETE FROM classification_rule_pack WHERE org_id = ? AND version = '1.1.0'",
                    ORG_DEV);
            rulePackLoader.invalidateAll();
        }
    }

    @Test
    void reclassification_supersedes_append_only_with_exactly_one_current_row() {
        UUID packageId = insertPackage("supersede-it");
        List<UUID> pageIds = insertFixturePages(packageId, "native_paystub");

        StageOutcome first = runStage(packageId, ProcessingStatus.CLASSIFYING);
        StageOutcome second = runStage(packageId, ProcessingStatus.CLASSIFYING);
        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        // Same spans, same packs — the digest is deterministic across the retry.
        assertThat(second.outputDigest()).isEqualTo(first.outputDigest());

        UUID pageId = pageIds.get(0);
        Integer total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM classification_result WHERE subject_id = ?",
                        Integer.class,
                        pageId);
        Integer current =
                jdbc.queryForObject(
                        "SELECT count(*) FROM classification_result WHERE subject_id = ? AND is_current",
                        Integer.class,
                        pageId);
        assertThat(total).isEqualTo(2);
        assertThat(current).isEqualTo(1);
    }

    @Test
    void an_unparseable_org_pack_fails_the_stage_with_INTERNAL_and_the_pack_id_only() {
        // Valid jsonb (the column enforces that) but not a valid pack: no anchors to load.
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (org_id, document_type_code, version, min_confidence, definition)
                VALUES (?, 'PAYSTUB', '9.9.9', 0.6, '{"anchors": "nope"}'::jsonb)
                """,
                ORG_DEV);
        rulePackLoader.invalidate(ORG_DEV);
        try {
            UUID packageId = insertPackage("broken-pack-it");
            insertFixturePages(packageId, "native_paystub");

            StageOutcome outcome = runStage(packageId, ProcessingStatus.CLASSIFYING);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.errorCode())
                    .isEqualTo(com.pragmaticds.docengine.platform.error.ErrorCode.INTERNAL);
            // The pack id is the only pack detail — never the definition body.
            assertThat(outcome.detail()).containsKey("rulePackId");
            assertThat(outcome.detail().toString()).doesNotContain("anchors");
        } finally {
            jdbc.update(
                    "DELETE FROM classification_rule_pack WHERE org_id = ? AND version = '9.9.9'",
                    ORG_DEV);
            rulePackLoader.invalidateAll();
        }
    }

    @Test
    void blank_and_duplicate_pages_are_skipped_entirely() {
        UUID packageId = insertPackage("skip-it");
        List<UUID> pageIds = insertFixturePages(packageId, "combined_package");

        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();

        // Truth marks page 9 BLANK and pages 10-11 DUPLICATE: no result rows at all for them.
        for (int skipped : new int[] {9, 10, 11}) {
            Integer count =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM classification_result WHERE subject_id = ?",
                            Integer.class,
                            pageIds.get(skipped));
            assertThat(count).isZero();
        }
    }
}
