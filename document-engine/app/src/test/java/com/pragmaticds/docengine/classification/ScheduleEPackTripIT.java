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
 * Trip, no-trip, and SPLIT proof for the Spec 5a {@code SCHEDULE_E} pack.
 *
 * <p>The split tests are the ones to read first. A real Schedule E prints
 * {@code Schedule E (Form 1040) 2025} in page 1's FOOTER and again in page 2's HEADER, so a
 * form-boundary rule keyed on that phrase would break every two-page form into two documents.
 * {@code Supplemental Income and Loss} is the masthead subtitle and appears on page 1 only —
 * that is the anchor carrying {@code "startsDocument": true}, and these two tests are what
 * prove the rule keys on the HEADER rather than on page count.
 */
class ScheduleEPackTripIT extends AbstractClassificationIT {

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

    /** The anchor ids the winning pack actually matched on this page. */
    private List<String> matchedAnchors(Map<String, Object> result) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode anchor : JSON.readTree(result.get("evidence").toString()).get("anchors")) {
            ids.add(anchor.get("anchorId").asText());
        }
        return ids;
    }

    private RulePack seededPack(String type) {
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active " + type + " pack for the dev org"));
    }

    private List<UUID> classifyFixture(String fixture) {
        UUID packageId = insertPackage(fixture + "-se-it");
        List<UUID> pageIds = insertFixturePages(packageId, fixture);
        runStage(packageId, ProcessingStatus.CLASSIFYING, "se-classify-" + packageId);
        return pageIds;
    }

    // ── trip ────────────────────────────────────────────────────────────────

    @Test
    void both_schedule_e_pages_classify_SCHEDULE_E() {
        for (UUID pageId : classifyFixture("schedule_e")) {
            Map<String, Object> result = currentResult(pageId);
            assertThat(result.get("document_type_code")).isEqualTo("SCHEDULE_E");
            assertThat((BigDecimal) result.get("confidence"))
                    .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
            assertThat(result.get("rule_pack_version")).isEqualTo("1.0.0");
        }
    }

    @Test
    void page_two_qualifies_on_its_OWN_part_headings_not_on_the_continuation_header()
            throws Exception {
        // Page 2 carries no masthead: it qualifies on Part II's and Part III's headings.
        // If it did not, a two-page form would split at the page break and both split
        // tests below would pass for the wrong reason.
        List<UUID> pageIds = classifyFixture("schedule_e");
        Map<String, Object> pageTwo = currentResult(pageIds.get(1));
        JsonNode score = scoreEntry(pageTwo, "SCHEDULE_E");
        assertThat(score.get("score").asDouble())
                .isGreaterThanOrEqualTo(score.get("minConfidence").asDouble());
        assertThat(matchedAnchors(pageTwo))
                .as("page 2's matched anchors")
                .contains("partnerships-scorps", "estates-trusts")
                .doesNotContain("supplemental-income");
    }

    // ── the split rule (design D6/D8) ───────────────────────────────────────

    @Test
    void a_two_page_schedule_e_stays_ONE_logical_document() {
        // The regression that proves the rule keys on the HEADER, not on page count.
        UUID packageId = insertPackage("se-one-form-it");
        insertFixturePages(packageId, "schedule_e");
        runStage(packageId, ProcessingStatus.CLASSIFYING, "se-one-classify-" + packageId);
        runStage(packageId, ProcessingStatus.SPLITTING, "se-one-split-" + packageId);

        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT * FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        packageId);
        assertThat(documents).as("one form, one document").hasSize(1);
        assertThat(documents.get(0).get("document_type_code")).isEqualTo("SCHEDULE_E");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM logical_document_page"
                                        + " WHERE logical_document_id = ?",
                                Integer.class,
                                documents.get(0).get("id")))
                .isEqualTo(2);
    }

    @Test
    void two_schedule_e_forms_in_one_package_split_into_TWO_logical_documents() {
        // Without this, the splitter's maximal-consecutive-run rule would merge both forms
        // into one four-page document and property A of form 2 would collide with property
        // A of form 1 on the rebuilt unique index.
        UUID packageId = insertPackage("se-two-forms-it");
        insertFixturePages(packageId, "schedule_e_two_forms");
        runStage(packageId, ProcessingStatus.CLASSIFYING, "se-two-classify-" + packageId);
        runStage(packageId, ProcessingStatus.SPLITTING, "se-two-split-" + packageId);

        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT * FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        packageId);
        assertThat(documents).as("two forms, two documents").hasSize(2);
        for (Map<String, Object> document : documents) {
            assertThat(document.get("document_type_code")).isEqualTo("SCHEDULE_E");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM logical_document_page"
                                            + " WHERE logical_document_id = ?",
                                    Integer.class,
                                    document.get("id")))
                    .as("each document keeps BOTH of its own pages")
                    .isEqualTo(2);
        }
    }

    // ── no-trip ─────────────────────────────────────────────────────────────

    @Test
    void the_schedule_e_fixture_never_qualifies_the_TAX_RETURN_pack() throws Exception {
        // The whole reason tax_return@1.1.0 exists. This fixture prints a real Schedule E's
        // own cross-reference — "Schedule 1 (Form 1040), line 5" — plus "Attach to Form
        // 1040". Under tax_return@1.0.0 that pair scored 0.70 and qualified. A reference to
        // a form is not evidence of being that form.
        for (UUID pageId : classifyFixture("schedule_e")) {
            JsonNode score = scoreEntry(currentResult(pageId), "TAX_RETURN");
            assertThat(score.get("score").asDouble())
                    .as("TAX_RETURN score on a Schedule E page")
                    .isLessThan(score.get("minConfidence").asDouble());
        }
    }

    @Test
    void the_tax_return_fixture_never_qualifies_the_SCHEDULE_E_pack() throws Exception {
        // The other direction, and the one a generic pack would fail: both 1040 pages
        // print "Form 1040" and page 1 the Treasury line — generic 1040 furniture.
        // SCHEDULE_E anchors on none of it. (The schedule furniture — "Attachment
        // Sequence", "(Form 1040)" — is gated by the schedule_1/schedule_2 fixtures in
        // CrossConfusionIT since issue #60 moved the Schedule 2 page out of this fixture.)
        for (UUID pageId : classifyFixture("tax_return")) {
            JsonNode score = scoreEntry(currentResult(pageId), "SCHEDULE_E");
            assertThat(score.get("score").asDouble())
                    .as("SCHEDULE_E score on a tax_return page")
                    .isLessThan(score.get("minConfidence").asDouble());
        }
    }

    @Test
    void the_SCHEDULE_E_pack_cannot_qualify_on_shared_vocabulary_alone() {
        // V10's exclusivity invariant, applied from birth. Only the Schedule-E-only phrases
        // carry weight enough to qualify; the continuation header and the rental vocabulary
        // sum to 2/10 = 0.20 < 0.60. (The sum is COMPUTED from the seed, so a re-weighting
        // moves it here automatically — what the test pins is that it stays under the bar.)
        Set<String> shared = Set.of("schedule-e-page", "rents-received", "fair-rental-days");
        RulePack pack = seededPack("SCHEDULE_E");
        // Vacuous-pass guard: a renamed anchor would silently drop out of the sum.
        assertThat(pack.anchors().stream().map(Anchor::id).filter(shared::contains).toList())
                .containsExactlyInAnyOrderElementsOf(shared);
        double sum =
                pack.anchors().stream()
                        .filter(anchor -> shared.contains(anchor.id()))
                        .mapToDouble(Anchor::weight)
                        .sum();
        assertThat(Math.min(1.0, sum / pack.targetScore())).isLessThan(pack.minConfidence());
    }

    @Test
    void a_page_ONE_cannot_qualify_without_the_anchor_that_carries_the_form_boundary() {
        // The defect this test was written for: PAGE 1 could reach the bar with the masthead
        // subtitle missing. supplemental-income is both the heaviest identity anchor AND the
        // ONLY startsDocument declaration, so a page-1 rescan with a cropped or unreadable
        // masthead classified SCHEDULE_E while startsDocument stayed false — and
        // PackageSplitter.group then merged the borrower's SECOND Schedule E into the first,
        // collapsing two forms' worth of A/B/C property columns into one key set. Silently:
        // the merged document looks exactly like a well-formed four-page one.
        //
        // The rule this pins: on PAGE 1 vocabulary, qualification is unreachable without a
        // boundary anchor. Page 2 is untouched — its own headings (Part II and Part III) still
        // qualify it comfortably, which is what keeps a two-page form ONE run.
        RulePack pack = seededPack("SCHEDULE_E");
        Set<String> pageOne =
                Set.of("supplemental-income", "rental-real-estate", "rents-received",
                        "fair-rental-days", "schedule-e-page");
        // Vacuous-pass guard: a renamed anchor would silently drop out of the sum.
        assertThat(pack.anchors().stream().map(Anchor::id).filter(pageOne::contains).toList())
                .containsExactlyInAnyOrderElementsOf(pageOne);

        double withoutTheBoundary =
                pack.anchors().stream()
                        .filter(anchor -> pageOne.contains(anchor.id()))
                        .filter(anchor -> !anchor.startsDocument())
                        .mapToDouble(Anchor::weight)
                        .sum();
        assertThat(Math.min(1.0, withoutTheBoundary / pack.targetScore()))
                .as("every page-1 anchor EXCEPT the boundary one, summed")
                .isLessThan(pack.minConfidence());

        // …and the page-1 anchors WITH it still qualify, so the fix is not "page 1 stopped
        // classifying". A test that only proved the first half would pass on a dead pack.
        double withTheBoundary =
                pack.anchors().stream()
                        .filter(anchor -> pageOne.contains(anchor.id()))
                        .mapToDouble(Anchor::weight)
                        .sum();
        assertThat(Math.min(1.0, withTheBoundary / pack.targetScore()))
                .isGreaterThanOrEqualTo(pack.minConfidence());
    }

    @Test
    void exactly_one_SCHEDULE_E_anchor_declares_that_it_starts_a_document() {
        // D8's limit made explicit: the form boundary is the page-1-only masthead subtitle.
        // If a second anchor ever gains the flag — especially the continuation header — a
        // two-page form starts splitting at its own page break, so this count is the guard.
        RulePack pack = seededPack("SCHEDULE_E");
        assertThat(pack.anchors().stream().filter(Anchor::startsDocument).map(Anchor::id).toList())
                .containsExactly("supplemental-income");
    }

    @Test
    void every_form_boundary_anchor_is_a_birth_packs_own_title() {
        // PackageSplitter's AnchorRef keys on (packType, anchorId) and deliberately DROPS
        // packVersion, while splitting reads PERSISTED evidence written by whatever pack
        // version ran at the time. SCHEDULE_E is a birth, so no earlier version of this
        // pack can have written "supplemental-income" evidence under a different meaning;
        // this assertion is what keeps the boundary set that small. V46 (issue #60) adds
        // four more, each on a BIRTH pack's own title for the same reason — no earlier
        // version exists to have written the anchor id under another meaning — and
        // TaxSchedulesAndUntypedPagesIT pins that each declares exactly one.
        List<String> declaring =
                rulePackLoader.activePacksForCurrentOrg().stream()
                        .flatMap(
                                pack ->
                                        pack.anchors().stream()
                                                .filter(Anchor::startsDocument)
                                                .map(anchor -> pack.documentTypeCode() + "/"
                                                        + anchor.id()))
                        .toList();
        assertThat(declaring)
                .containsExactlyInAnyOrder(
                        "SCHEDULE_E/supplemental-income",
                        "SCHEDULE_1/s1-title",
                        "SCHEDULE_2/s2-title",
                        "FORM_8962/ptc-title",
                        "STATE_TAX_RETURN/st-title",
                        // V50: a birth pack's own printed title, like the four above.
                        "PROPERTY_TAX_STATEMENT/pt-title",
                        // V51: the triage-only births, each on its own printed title(s).
                        "FAX_COVER_SHEET/fax-title",
                        "EFILE_AUTHORIZATION/ef-title",
                        "TAX_PREPARER_LETTER/tpl-title",
                        "LOAN_DISCLOSURE_PACKAGE/ldp-title",
                        "CLOSING_PACKAGE/cp-title");
    }
}
