package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.classification.PageClassifier.PackEvaluation;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.match.AnchorMatch;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.classification.web.PackageDocumentsAssembler;
import com.pragmaticds.docengine.classification.web.PackageDocumentsView;
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
 * Issue #60, end to end against the migrated packs: the pages a tax package prints that nothing
 * could type. Schedules 1 and 2 used to classify TAX_RETURN through their own titles and fold
 * into the 1040; Form 8962 and a state return typed NOTHING, and {@link PackageSplitter}'s
 * continuation rule glued them onto whatever preceded them. V46 gives each its own type with a
 * {@code startsDocument} title anchor, narrows TAX_RETURN to the 1040 (and Schedule 3), and
 * counts the absorption that still happens.
 *
 * <p>Trip: each fixture classifies its own type through the anchors V46 names — asserted by ID,
 * not score, so a fixture that drifts from the form's wording says WHICH phrase it stopped
 * printing. No-trip: none of the four pages reaches TAX_RETURN's bar, and a 1040 page reaches
 * none of theirs. Then the package the issue measured, in shape: seven pages through CLASSIFYING
 * and SPLITTING become FIVE documents, and the one absorption left — the state return's untyped
 * second page — is written to the row and read back through the documents projection.
 * {@link CrossConfusionIT} holds the whole matrix; this names the cells that drove V46.
 */
class TaxSchedulesAndUntypedPagesIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;
    @Autowired PackageDocumentsAssembler documentsView;

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
        UUID packageId = insertPackage(fixture + "-i60-it");
        List<UUID> pageIds = insertFixturePages(packageId, fixture);
        runStage(packageId, ProcessingStatus.CLASSIFYING, "i60-classify-" + packageId);
        return pageIds;
    }

    private void assertClassifies(UUID pageId, String type) {
        Map<String, Object> result = currentResult(pageId);
        assertThat(result.get("document_type_code")).isEqualTo(type);
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
        assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
    }

    private void assertBelowBar(Map<String, Object> result, String packType) throws Exception {
        assertThat(scoreEntry(result, packType).get("score").asDouble())
                .as("%s's score on a %s page", packType, result.get("document_type_code"))
                .isLessThan(seededPack(packType).minConfidence());
    }

    /** Words become spans in reading order, exactly as AnchorMatcher joins them. */
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

    /** A synthetic truth page — one visual line of {@code words} — for the fixture bridge. */
    private static ObjectNode syntheticPage(String... words) {
        ObjectNode page = JSON.createObjectNode();
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        ArrayNode list = page.putArray("words");
        double x = 72.0;
        for (String word : words) {
            ObjectNode node = list.addObject();
            node.put("text", word);
            node.put("x", x);
            node.put("y", 400.0);
            node.put("width", 30.0);
            node.put("height", 12.0);
            x += 36.0;
        }
        return page;
    }

    // ── trip ────────────────────────────────────────────────────────────────

    @Test
    void the_schedule_1_fixture_classifies_SCHEDULE_1_through_every_anchor_it_prints()
            throws Exception {
        UUID pageId = classifyFixture("schedule_1").get(0);
        assertClassifies(pageId, "SCHEDULE_1");
        assertThat(matchedAnchors(currentResult(pageId)))
                .containsExactlyInAnyOrder(
                        "s1-title", "s1-footer", "s1-add-income", "s1-adjust", "s1-se-deduct",
                        "s1-educator");
    }

    @Test
    void the_schedule_2_fixture_classifies_SCHEDULE_2_through_every_anchor_it_prints()
            throws Exception {
        UUID pageId = classifyFixture("schedule_2").get(0);
        assertClassifies(pageId, "SCHEDULE_2");
        assertThat(matchedAnchors(currentResult(pageId)))
                .containsExactlyInAnyOrder(
                        "s2-title", "s2-footer", "s2-other", "s2-ira", "s2-homebuyer",
                        "s2-ss-medicare");
    }

    @Test
    void the_form_8962_fixture_classifies_FORM_8962_through_every_anchor_it_prints()
            throws Exception {
        UUID pageId = classifyFixture("form_8962").get(0);
        assertClassifies(pageId, "FORM_8962");
        assertThat(matchedAnchors(currentResult(pageId)))
                .containsExactlyInAnyOrder(
                        "ptc-title", "ptc-reconcile", "ptc-poverty", "ptc-form", "ptc-annual",
                        "ptc-excess");
    }

    @Test
    void the_state_return_first_page_classifies_STATE_TAX_RETURN_and_its_second_page_UNKNOWN()
            throws Exception {
        List<UUID> pageIds = classifyFixture("state_tax_return");
        assertClassifies(pageIds.get(0), "STATE_TAX_RETURN");
        assertThat(matchedAnchors(currentResult(pageIds.get(0))))
                .containsExactlyInAnyOrder("st-title", "st-return", "st-form", "st-dept");

        // Page 2 prints the form number (a reference, 0.30) and nothing else the pack keys on:
        // untyped by construction — the page the continuation rule exists for.
        Map<String, Object> second = currentResult(pageIds.get(1));
        assertThat(second.get("document_type_code")).isEqualTo(PageClassifier.UNKNOWN);
        assertBelowBar(second, "STATE_TAX_RETURN");
    }

    @Test
    void every_new_pack_declares_exactly_one_form_boundary_on_its_title() {
        for (String type : List.of("SCHEDULE_1", "SCHEDULE_2", "FORM_8962", "STATE_TAX_RETURN")) {
            List<Anchor> boundaries =
                    seededPack(type).anchors().stream().filter(Anchor::startsDocument).toList();
            assertThat(boundaries).as("%s startsDocument anchors", type).hasSize(1);
            assertThat(boundaries.get(0).weight())
                    .as("%s's boundary is its heaviest anchor", type)
                    .isEqualTo(5.0);
        }
    }

    // ── no-trip ─────────────────────────────────────────────────────────────

    @Test
    void every_new_page_scores_exactly_what_its_1040_references_are_worth_on_TAX_RETURN()
            throws Exception {
        // Pinned to the number, not merely "< 0.60", so an anchor creeping to 0.50 cannot pass
        // silently. The schedules print "(Form 1040)" / "Attach to Form 1040" — the literal,
        // worth 2 — and the Treasury line STACKED, so the em-dash literal misses: 0.20. Form
        // 8962 adds its filing-status caution ("...if your filing status is married filing
        // separately"), which the case-insensitive "Filing Status" literal reads: 0.30. The
        // state return cites "1040 line 15" without the word "Form" and prints no 1040
        // furniture at all: 0.00 on both pages.
        Map<String, List<Double>> expected =
                Map.of(
                        "schedule_1", List.of(0.20),
                        "schedule_2", List.of(0.20),
                        "form_8962", List.of(0.30),
                        "state_tax_return", List.of(0.0, 0.0));
        for (Map.Entry<String, List<Double>> entry : expected.entrySet()) {
            List<UUID> pageIds = classifyFixture(entry.getKey());
            for (int index = 0; index < pageIds.size(); index++) {
                Map<String, Object> result = currentResult(pageIds.get(index));
                assertThat(scoreEntry(result, "TAX_RETURN").get("score").asDouble())
                        .as("TAX_RETURN score on %s page %d", entry.getKey(), index)
                        .isCloseTo(entry.getValue().get(index), within(1e-9));
                assertBelowBar(result, "TAX_RETURN");
            }
        }
    }

    // ── state returns whose title carries the form number (review finding) ─

    /**
     * Page-1 header shapes of the big states, as their published forms print them: the form
     * number sits INSIDE the title, so {@code st-title}'s contiguous shape misses and each must
     * qualify through {@code st-form} + {@code st-dept} + the non-boundary {@code st-return}.
     * Fixture-free on purpose — one Colorado fixture cannot stand in for fifty layouts.
     */
    private static final Map<String, String[]> FORM_NUMBER_IN_TITLE_HEADERS =
            Map.of(
                    "Georgia", new String[] {
                        "Georgia", "Form", "500", "Individual", "Income", "Tax", "Return",
                        "Georgia", "Department", "of", "Revenue", "2025"
                    },
                    "Virginia", new String[] {
                        "Virginia", "Resident", "Form", "760", "Individual", "Income", "Tax",
                        "Return", "Virginia", "Department", "of", "Taxation"
                    },
                    "Arizona", new String[] {
                        "Arizona", "Form", "140", "Resident", "Personal", "Income", "Tax",
                        "Return", "Arizona", "Department", "of", "Revenue"
                    },
                    "Ohio", new String[] {
                        "Ohio", "IT", "1040", "Individual", "Income", "Tax", "Return",
                        "Ohio", "Department", "of", "Taxation", "2025"
                    },
                    "Illinois", new String[] {
                        "Form", "IL-1040", "Individual", "Income", "Tax", "Return",
                        "Illinois", "Department", "of", "Revenue", "2025"
                    });

    @Test
    void a_state_return_whose_title_carries_the_form_number_still_qualifies() {
        RulePack pack = seededPack("STATE_TAX_RETURN");
        for (Map.Entry<String, String[]> header : FORM_NUMBER_IN_TITLE_HEADERS.entrySet()) {
            PackEvaluation evaluation =
                    PageClassifier.evaluate(pack, matcherFor(header.getValue()));
            // st-form 3 + st-return 2 + st-dept 2 = 0.70 — without the boundary title.
            assertThat(evaluation.score())
                    .as("%s page-1 header on STATE_TAX_RETURN", header.getKey())
                    .isCloseTo(0.70, within(1e-9))
                    .isGreaterThanOrEqualTo(pack.minConfidence());
            assertThat(evaluation.matches())
                    .extracting(AnchorMatch::anchorId)
                    .as("%s anchors", header.getKey())
                    .containsExactlyInAnyOrder("st-form", "st-return", "st-dept");
        }
    }

    @Test
    void a_1040_first_page_scores_only_the_shared_title_words_on_STATE_TAX_RETURN() {
        // "U.S. Individual Income Tax Return" is what st-return reads — 2, and nothing else on
        // a 1040 page is a state form number or a state revenue department.
        RulePack pack = seededPack("STATE_TAX_RETURN");
        PackEvaluation evaluation =
                PageClassifier.evaluate(
                        pack,
                        matcherFor(
                                "Form", "1040", "U.S.", "Individual", "Income", "Tax", "Return",
                                "2025", "Department", "of", "the", "Treasury—Internal",
                                "Revenue", "Service", "Filing", "Status", "Single"));

        assertThat(evaluation.score()).isCloseTo(0.20, within(1e-9));
        assertThat(evaluation.score()).isLessThan(pack.minConfidence());
        assertThat(evaluation.matches())
                .extracting(AnchorMatch::anchorId)
                .containsExactly("st-return");
    }

    // ── a boundary anchor must not fire on prose (review finding) ───────────

    @Test
    void an_untyped_page_saying_no_additional_taxes_are_due_does_not_start_a_document()
            throws Exception {
        // On an UNKNOWN page the classifier records EVERY pack's matched anchors, and the
        // splitter cuts at any startsDocument anchor it finds there — the pack need not have
        // won. So s2-title is case-sensitive: the form prints "Additional Taxes", a preparer's
        // cover letter prints "no additional taxes are due", and only the first may cut.
        ArrayNode pages = JSON.createArrayNode();
        pages.add(truth("w2_form").get("pages").get(0));
        pages.add(
                syntheticPage(
                        "Based", "on", "the", "enclosed", "return", "no", "additional", "taxes",
                        "are", "due", "for", "this", "individual", "income", "tax", "return."));

        UUID packageId = insertPackage("issue-60-prose-boundary");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, pages);
        runStage(packageId, ProcessingStatus.CLASSIFYING, "i60-prose-classify-" + packageId);
        runStage(packageId, ProcessingStatus.SPLITTING, "i60-prose-split-" + packageId);

        Map<String, Object> prose = currentResult(pageIds.get(1));
        assertThat(prose.get("document_type_code")).isEqualTo(PageClassifier.UNKNOWN);
        assertThat(matchedAnchors(prose))
                .as("no boundary anchor may read prose")
                .doesNotContain("s2-title", "s1-title", "ptc-title", "st-title");

        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT * FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        packageId);
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).get("document_type_code")).isEqualTo("W2");
        assertThat(documents.get(0).get("absorbed_untyped_pages")).isEqualTo(1);
    }

    @Test
    void a_1040_page_reaches_none_of_the_new_bars() throws Exception {
        // The 1040 says "Additional income from Schedule 1, line 10" and "Amount from Schedule
        // 2, line 3" — it cites the schedules and prints none of their titles.
        for (UUID pageId : classifyFixture("tax_return")) {
            Map<String, Object> result = currentResult(pageId);
            assertThat(result.get("document_type_code")).isEqualTo("TAX_RETURN");
            for (String type : List.of("SCHEDULE_1", "SCHEDULE_2", "FORM_8962", "STATE_TAX_RETURN")) {
                assertBelowBar(result, type);
            }
        }
    }

    @Test
    void the_active_TAX_RETURN_pack_no_longer_anchors_the_schedule_titles() {
        RulePack pack = seededPack("TAX_RETURN");
        assertThat(pack.version()).isEqualTo("1.2.0");
        assertThat(pack.anchors())
                .extracting(Anchor::pattern)
                .noneMatch(pattern -> pattern.contains("Additional Taxes"))
                .noneMatch(pattern -> pattern.contains("Adjustments to Income"));
        // Schedule 3 stays where V13 put it: out of this issue's scope, and dropping its title
        // would turn a typed page into an absorbed one.
        assertThat(pack.anchors())
                .extracting(Anchor::pattern)
                .anyMatch(pattern -> pattern.contains("Additional Credits and Payments"));
    }

    // ── the package the issue measured, in shape ────────────────────────────

    @Test
    void the_seven_page_package_splits_into_five_documents_and_the_absorbed_page_is_counted()
            throws Exception {
        // 1040 p1, 1040 p2, Schedule 1, Schedule 2, Form 8962, state p1, state p2 (untyped).
        // Before V46: one seven-page TAX_RETURN. After: five documents, one absorbed page.
        ArrayNode pages = JSON.createArrayNode();
        for (String fixture :
                List.of("tax_return", "schedule_1", "schedule_2", "form_8962", "state_tax_return")) {
            pages.addAll((ArrayNode) truth(fixture).get("pages"));
        }
        assertThat(pages).hasSize(7);

        UUID packageId = insertPackage("issue-60-package");
        insertFixturePages(packageId, ORG_DEV, pages);
        runStage(packageId, ProcessingStatus.CLASSIFYING, "i60-classify-" + packageId);
        runStage(packageId, ProcessingStatus.SPLITTING, "i60-split-" + packageId);

        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT * FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        packageId);
        assertThat(documents)
                .extracting(document -> document.get("document_type_code"))
                .containsExactly(
                        "TAX_RETURN", "SCHEDULE_1", "SCHEDULE_2", "FORM_8962", "STATE_TAX_RETURN");
        assertThat(documents)
                .extracting(document -> document.get("boundary_provenance"))
                .containsExactly(
                        LogicalDocument.BOUNDARY_PACKAGE_START,
                        LogicalDocument.BOUNDARY_RULE,
                        LogicalDocument.BOUNDARY_RULE,
                        LogicalDocument.BOUNDARY_RULE,
                        LogicalDocument.BOUNDARY_RULE);
        assertThat(documents)
                .extracting(document -> document.get("absorbed_untyped_pages"))
                .containsExactly(0, 0, 0, 0, 1);

        List<Integer> pageCounts = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            pageCounts.add(
                    jdbc.queryForObject(
                            "SELECT count(*) FROM logical_document_page WHERE logical_document_id = ?",
                            Integer.class,
                            document.get("id")));
        }
        assertThat(pageCounts).containsExactly(2, 1, 1, 1, 2);

        // The projection review reads carries the count, so "STATE_TAX_RETURN, 2 pages, 1
        // untyped page absorbed" is visible without a query.
        PackageDocumentsView view = documentsView.build(packageId, ORG_DEV);
        assertThat(view.documents())
                .extracting(PackageDocumentsView.DocumentView::absorbedUntypedPages)
                .containsExactly(0, 0, 0, 0, 1);
        assertThat(view.unassignedPages()).isEmpty();

        // And the regroup snapshot — what a reviewer's decision records as previous_value —
        // names it too, so "what did I overrule" includes what the machine merely absorbed.
        assertThat(documentsView.groupingJson(packageId, ORG_DEV))
                .contains("\"absorbedUntypedPages\":1");
    }

    @Test
    void a_document_split_before_v46_reads_null_not_zero() {
        // Rows written before the column existed carry NO count; the engine refuses to
        // fabricate one, the same honesty V24 kept for boundary_provenance.
        UUID packageId = insertPackage("issue-60-legacy");
        UUID documentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code,
                    classification_confidence, boundary_provenance)
                VALUES (?, ?, ?, 0, 'TAX_RETURN', 0.9, 'PACKAGE_START')
                """,
                documentId,
                ORG_DEV,
                packageId);

        Integer stored =
                jdbc.queryForObject(
                        "SELECT absorbed_untyped_pages FROM logical_document WHERE id = ?",
                        Integer.class,
                        documentId);
        assertThat(stored).isNull();
    }
}
