package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.PageClassifier.PackEvaluation;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V50 against the migrated pack: a county real-estate tax statement classifies as its own type
 * instead of UNKNOWN, and the neighbours it shares a package with — a mortgage statement, an HOI
 * declaration, a 1040 — cannot qualify on its vocabulary, nor it on theirs.
 *
 * <p>Trip is asserted by anchor ID, so a fixture that drifts from the wording says WHICH phrase it
 * stopped printing. No-trip is pinned to the number on the neighbours' own fixtures, the shared
 * vocabulary (an appraisal's or a title commitment's) is pinned under the bar, and the title's
 * case-sensitivity — what keeps escrow prose from cutting a document on an UNKNOWN page — is
 * pinned directly. {@link CrossConfusionIT} holds the whole matrix.
 */
class PropertyTaxStatementIT extends AbstractClassificationIT {

    private static final String TYPE = "PROPERTY_TAX_STATEMENT";

    @Autowired ParserPort parserPort;

    private RulePack pack() {
        rulePackLoader.invalidateAll();
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals(TYPE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active " + TYPE + " pack"));
    }

    private static AnchorMatcher matcherForTruthPage(JsonNode truthPage) {
        List<AnchorSpan> spans = new ArrayList<>();
        long id = 1;
        for (JsonNode word : truthPage.get("words")) {
            spans.add(
                    new AnchorSpan(
                            id++,
                            word.get("text").asText(),
                            word.get("x").decimalValue(),
                            word.get("y").decimalValue(),
                            word.get("width").decimalValue(),
                            word.get("height").decimalValue()));
        }
        return AnchorMatcher.forSpans(spans);
    }

    /** Words become spans on one visual line, in reading order. */
    private static AnchorMatcher matcherFor(String... words) {
        List<AnchorSpan> spans = new ArrayList<>();
        long id = 1;
        double x = 72.0;
        for (String word : words) {
            spans.add(
                    new AnchorSpan(
                            id++,
                            word,
                            BigDecimal.valueOf(x),
                            new BigDecimal("42.0"),
                            new BigDecimal("30.0"),
                            new BigDecimal("12.0")));
            x += 36.0;
        }
        return AnchorMatcher.forSpans(spans);
    }

    private static List<String> anchorIds(PackEvaluation evaluation) {
        return evaluation.matches().stream().map(match -> match.anchorId()).toList();
    }

    // ── trip ────────────────────────────────────────────────────────────────

    @Test
    void the_fixture_classifies_PROPERTY_TAX_STATEMENT_through_every_anchor_it_prints()
            throws Exception {
        UUID packageId = insertPackage("property-tax-statement-v50-it");
        List<UUID> pageIds = insertFixturePages(packageId, "property_tax_statement");
        ParserPort.StageOutcome outcome =
                parserPort.run(
                        new ParserPort.StageRequest(
                                UUID.randomUUID(),
                                packageId,
                                ProcessingStatus.CLASSIFYING,
                                1,
                                "v50-classify-" + packageId));
        assertThat(outcome.success()).isTrue();

        Map<String, Object> result =
                jdbc.queryForMap(
                        "SELECT * FROM classification_result WHERE subject_id = ? AND is_current",
                        pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo(TYPE);
        assertThat((BigDecimal) result.get("confidence")).isEqualByComparingTo("1.0");
        assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
        List<String> matched = new ArrayList<>();
        for (JsonNode anchor : JSON.readTree(result.get("evidence").toString()).get("anchors")) {
            matched.add(anchor.get("anchorId").asText());
        }
        assertThat(matched)
                .containsExactlyInAnyOrder(
                        "pt-title", "pt-levy", "pt-parcel", "pt-value", "pt-special",
                        "pt-collector");
    }

    @Test
    void a_statement_whose_title_was_lost_to_OCR_still_qualifies_on_the_rest() {
        // 2 (levy) + 2 (parcel) + 1 + 1 + 1 = 7 = 0.70.
        PackEvaluation evaluation =
                PageClassifier.evaluate(
                        pack(),
                        matcherFor(
                                "Parcel", "Number", "Taxable", "Value", "Total", "mill", "levy",
                                "Plus:", "Special", "Assessments", "Office:", "Fixture",
                                "County", "Treasurer"));
        assertThat(evaluation.score()).isCloseTo(0.70, within(1e-9));
    }

    @Test
    void the_pack_declares_exactly_one_form_boundary_on_its_title() {
        List<Anchor> boundaries = pack().anchors().stream().filter(Anchor::startsDocument).toList();
        assertThat(boundaries).extracting(Anchor::id).containsExactly("pt-title");
        assertThat(boundaries.get(0).weight()).isEqualTo(5.0);
    }

    @Test
    void the_title_anchor_reads_the_generic_county_wordings() {
        RulePack pack = pack();
        for (String[] title :
                List.of(
                        new String[] {"2025", "Real", "Estate", "Tax", "Statement"},
                        new String[] {"2025-2026", "Secured", "Property", "Tax", "Bill"},
                        new String[] {"REAL", "PROPERTY", "TAX", "BILL"},
                        new String[] {"Property", "Tax", "Statement"},
                        new String[] {"AD", "VALOREM", "TAX", "STATEMENT"})) {
            assertThat(anchorIds(PageClassifier.evaluate(pack, matcherFor(title))))
                    .as(String.join(" ", title))
                    .contains("pt-title");
        }
    }

    // ── no-trip ─────────────────────────────────────────────────────────────

    @Test
    void the_neighbours_fixtures_score_nothing_on_the_property_tax_pack() {
        // A mortgage statement's escrow block, an HOI declaration and a 1040 print none of the
        // pack's phrases. Pinned to the number so a creeping anchor cannot pass silently.
        RulePack pack = pack();
        for (String fixture :
                List.of("mortgage_statement", "hoi_declaration", "tax_return", "w2_form")) {
            JsonNode pages = truth(fixture).get("pages");
            for (int index = 0; index < pages.size(); index++) {
                PackEvaluation evaluation =
                        PageClassifier.evaluate(pack, matcherForTruthPage(pages.get(index)));
                assertThat(evaluation.score())
                        .as("%s on %s page %d via %s", TYPE, fixture, index, anchorIds(evaluation))
                        .isCloseTo(0.0, within(1e-9));
            }
        }
    }

    @Test
    void the_statement_fixture_qualifies_no_neighbouring_pack() {
        JsonNode page = truth("property_tax_statement").get("pages").get(0);
        AnchorMatcher matcher = matcherForTruthPage(page);
        rulePackLoader.invalidateAll();
        for (RulePack other : rulePackLoader.activePacksForCurrentOrg()) {
            if (other.documentTypeCode().equals(TYPE)) {
                continue;
            }
            PackEvaluation evaluation = PageClassifier.evaluate(other, matcher);
            assertThat(evaluation.score())
                    .as("%s on the tax statement via %s", other.documentTypeCode(),
                            anchorIds(evaluation))
                    .isLessThan(other.minConfidence());
        }
    }

    @Test
    void the_shared_property_vocabulary_alone_stays_under_the_bar() {
        // Every SHARED anchor at once — what an appraisal's tax section or a title commitment's
        // requirements could print together: 2 + 1 + 1 + 1 = 5 = 0.50.
        PackEvaluation evaluation =
                PageClassifier.evaluate(
                        pack(),
                        matcherFor(
                                "Assessor's", "Parcel", "#", "Special", "Assessments", "$",
                                "assessed", "value", "County", "Treasurer"));
        assertThat(evaluation.score()).isCloseTo(0.50, within(1e-9));
        assertThat(evaluation.score()).isLessThan(pack().minConfidence());
    }

    @Test
    void escrow_prose_naming_the_tax_bill_is_not_a_title() {
        // On an UNKNOWN page the splitter cuts at ANY startsDocument anchor it finds, so the
        // title must not fire on the sentence a mortgage servicer's escrow letter prints.
        PackEvaluation evaluation =
                PageClassifier.evaluate(
                        pack(),
                        matcherFor(
                                "We", "paid", "your", "property", "tax", "bill", "and", "your",
                                "real", "estate", "tax", "statement", "from", "escrow."));
        assertThat(anchorIds(evaluation)).doesNotContain("pt-title");
        assertThat(evaluation.score()).isCloseTo(0.0, within(1e-9));
    }
}
