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
 * Trip and no-trip proof for the three T9 packs (HOI_DECLARATION, PURCHASE_CONTRACT,
 * TAX_RETURN) — V10's exclusivity discipline applied from birth:
 *
 * <ul>
 *   <li>each fixture page classifies its own type at or above threshold;
 *   <li>the HOI mortgagee clause (a mortgage lender's name, deliberately drawn) never
 *       qualifies the MORTGAGE_STATEMENT pack;
 *   <li>the 1040's "Form(s) W-2" + "Federal income tax withheld" pair never qualifies the
 *       W2 pack — the exact pair that nearly mislabelled a real 1040 (V10);
 *   <li>the w2_form fixture never qualifies TAX_RETURN in return;
 *   <li>no T9 pack's shared-vocabulary anchors can reach its own threshold alone
 *       (the invariant W2PackExclusivityIT pins for W2).
 * </ul>
 */
class HoiPurchaseTaxPackTripIT extends AbstractClassificationIT {

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
        UUID packageId = insertPackage(fixture + "-t9-it");
        List<UUID> pageIds = insertFixturePages(packageId, fixture);
        runStage(packageId, ProcessingStatus.CLASSIFYING, fixture + "-t9-idem");
        return pageIds;
    }

    // ── trip ────────────────────────────────────────────────────────────────

    @Test
    void the_hoi_declaration_page_classifies_HOI_DECLARATION() {
        List<UUID> pageIds = classifyFixture("hoi_declaration");
        Map<String, Object> result = currentResult(pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("HOI_DECLARATION");
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
        assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
    }

    @Test
    void both_purchase_contract_variants_classify_PURCHASE_CONTRACT_on_both_pages() {
        for (String fixture : List.of("purchase_contract_signed", "purchase_contract_unsigned")) {
            for (UUID pageId : classifyFixture(fixture)) {
                Map<String, Object> result = currentResult(pageId);
                assertThat(result.get("document_type_code")).as(fixture).isEqualTo("PURCHASE_CONTRACT");
                assertThat((BigDecimal) result.get("confidence"))
                        .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
            }
        }
    }

    @Test
    void every_tax_return_page_classifies_TAX_RETURN_and_page_two_sits_exactly_at_threshold() {
        List<UUID> pageIds = classifyFixture("tax_return");
        for (UUID pageId : pageIds) {
            assertThat(currentResult(pageId).get("document_type_code")).isEqualTo("TAX_RETURN");
        }
        // Page 2 carries the dated footer (weight 4) plus the "Form 1040" literal inside it
        // (weight 2): 6/10 — EXACTLY min_confidence, qualifying via the deliberate `>=`
        // (the paystub-continuation precedent). If this assertion breaks after a pack edit,
        // it is the threshold contract that broke, not this test.
        assertThat((BigDecimal) currentResult(pageIds.get(1)).get("confidence"))
                .isEqualByComparingTo("0.6");
    }

    @Test
    void the_two_tax_return_pages_split_into_one_logical_document() {
        // The 1040's two pages are one TAX_RETURN document via existing splitting. (Until issue
        // #60 a Schedule 2 page rode as page 3 to prove D4 — schedules join the 1040. D4 is
        // reversed: Schedule 2 is its own type now, see TaxSchedulesAndUntypedPagesIT.)
        UUID packageId = insertPackage("tax-split-t9-it");
        insertFixturePages(packageId, "tax_return");
        runStage(packageId, ProcessingStatus.CLASSIFYING, "tax-split-classify-idem");
        runStage(packageId, ProcessingStatus.SPLITTING, "tax-split-split-idem");

        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT * FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        packageId);
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).get("document_type_code")).isEqualTo("TAX_RETURN");
        Integer pages =
                jdbc.queryForObject(
                        "SELECT count(*) FROM logical_document_page WHERE logical_document_id = ?",
                        Integer.class,
                        documents.get(0).get("id"));
        assertThat(pages).isEqualTo(2);
        // Run confidence is the MIN member's — page 2's exactly-at-threshold 0.6.
        assertThat((BigDecimal) documents.get(0).get("classification_confidence"))
                .isEqualByComparingTo("0.6");
    }

    // ── no-trip ─────────────────────────────────────────────────────────────

    @Test
    void the_hoi_mortgagee_clause_never_qualifies_the_MORTGAGE_STATEMENT_pack() throws Exception {
        List<UUID> pageIds = classifyFixture("hoi_declaration");
        JsonNode score = scoreEntry(currentResult(pageIds.get(0)), "MORTGAGE_STATEMENT");
        assertThat(score.get("score").asDouble()).isLessThan(score.get("minConfidence").asDouble());
    }

    @Test
    void the_1040_pages_carrying_W2_references_never_qualify_the_W2_pack() throws Exception {
        List<UUID> pageIds = classifyFixture("tax_return");
        for (int pageIndex : new int[] {0, 1}) {
            JsonNode score = scoreEntry(currentResult(pageIds.get(pageIndex)), "W2");
            assertThat(score.get("score").asDouble())
                    .as("W2 score on tax_return page " + pageIndex)
                    .isLessThan(score.get("minConfidence").asDouble());
        }
    }

    @Test
    void the_w2_fixture_never_qualifies_the_TAX_RETURN_pack() throws Exception {
        List<UUID> pageIds = classifyFixture("w2_form");
        Map<String, Object> result = currentResult(pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("W2");
        JsonNode score = scoreEntry(result, "TAX_RETURN");
        assertThat(score.get("score").asDouble()).isLessThan(score.get("minConfidence").asDouble());
    }

    // ── exclusivity invariant (V10, generalized to every new pack) ──────────

    private void assertSharedAnchorsCannotQualify(String type, Set<String> sharedAnchorIds) {
        RulePack pack = seededPack(type);
        // Guards against the vacuous pass: a renamed anchor would silently drop out of the sum.
        assertThat(
                        pack.anchors().stream()
                                .map(Anchor::id)
                                .filter(sharedAnchorIds::contains)
                                .toList())
                .containsExactlyInAnyOrderElementsOf(sharedAnchorIds);
        double shared =
                pack.anchors().stream()
                        .filter(anchor -> sharedAnchorIds.contains(anchor.id()))
                        .mapToDouble(Anchor::weight)
                        .sum();
        assertThat(Math.min(1.0, shared / pack.targetScore())).isLessThan(pack.minConfidence());
    }

    @Test
    void no_T9_pack_can_qualify_on_shared_vocabulary_alone() {
        assertSharedAnchorsCannotQualify(
                "HOI_DECLARATION", Set.of("policy-period", "homeowners-ins", "annual-premium"));
        assertSharedAnchorsCannotQualify(
                "PURCHASE_CONTRACT", Set.of("closing-date", "purchase-price"));
        // schedule-form-1040 joins the shared bucket at weight 0 (Spec 5a): the regex needs a
        // DIGIT, so it never matched "SCHEDULE E (Form 1040)" — it fired on the cross-reference
        // "Schedule 1 (Form 1040), line 5" printed inside a LETTERED schedule's instructions,
        // and a citation of a form is not evidence of being that form. It stays in the pack, at
        // zero, so a reviewer still sees the citation in the evidence document while it counts
        // for nothing. 2 + 2 + 1 + 0 = 5 = 0.50 < 0.60.
        assertSharedAnchorsCannotQualify(
                "TAX_RETURN",
                Set.of("form-1040", "treasury-irs", "filing-status", "schedule-form-1040"));
    }
}
