package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase E3 — the boundary regression gate: golden multi-document packages in every shape the
 * design enumerates (§5, §11), with ground-truth boundaries, run through the DETERMINISTIC
 * pipeline (no model — the stub port proposes nothing, exactly as CI always runs).
 *
 * <p>What is being pinned is the deterministic baseline the AI is allowed to IMPROVE and must
 * never be able to damage:
 *
 * <ul>
 *   <li><b>Precision 1.0 is the invariant.</b> The deterministic split must never cut where no
 *       boundary exists — every one of its cuts is a proof (type change, form anchor, instance
 *       key). A false positive here is a bug, full stop.
 *   <li><b>Recall is the known, measured loss.</b> The glue case and the same-type-no-key run
 *       are the misses the boundary-extraction stage exists to recover; their counts are pinned
 *       so a change to {@code group()} that silently loses (or luckily gains) a boundary moves a
 *       number a human must look at.
 * </ul>
 *
 * <p>Two-forms-on-one-sheet is pinned as a KNOWN LIMIT, not a regression (roadmap R11): page
 * granularity cannot express a mid-page boundary, with or without AI — the multi-document-sheet
 * flag (B4) is asserted instead, because visible-and-unsolved is the designed behaviour and
 * silent-and-unsolved would be the regression.
 *
 * <p>When a live adapter's quality is evaluated (corpus run, not CI), the same truth sets and the
 * same {@link #boundariesOf} read the model-on result — these fixtures are the eval's ground
 * truth, not just CI's.
 */
class BoundarySplitRegressionIT extends AbstractExtractionIT {

    // ── golden package builders (truth-bridge, no PDF fixtures to regenerate) ──

    private UUID packageOf(String name, List<JsonNode> pages) {
        UUID packageId = insertPackage(name + "-" + UUID.randomUUID());
        ArrayNode combined = JSON.createArrayNode();
        pages.forEach(combined::add);
        insertFixturePages(packageId, ORG_DEV, combined);
        return packageId;
    }

    private List<JsonNode> truthPages(String fixtureName) {
        List<JsonNode> pages = new ArrayList<>();
        truth(fixtureName).get("pages").forEach(pages::add);
        return pages;
    }

    /** One paystub page made distinct from its siblings: a unique marker word in the body. */
    private JsonNode distinctPaystub(int copy) {
        ObjectNode page = truthPages("paystub_complete").get(0).deepCopy();
        ObjectNode marker = JSON.createObjectNode();
        marker.put("text", "STUB-COPY-" + copy);
        marker.put("x", 72.0);
        marker.put("y", 700.0);
        marker.put("width", 120.0);
        marker.put("height", 10.0);
        ((ArrayNode) page.get("words")).add(marker);
        return page;
    }

    /** An untypable continuation-style page with a real header band — the glue material. */
    private JsonNode riderPage(int rider) {
        ObjectNode page = JSON.createObjectNode();
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        ArrayNode words = page.putArray("words");
        double x = 72;
        for (String text : ("Terms and Conditions Addendum Section " + (char) ('A' + rider)).split(" ")) {
            ObjectNode word = words.addObject();
            word.put("text", text);
            word.put("x", x);
            word.put("y", 40.0);
            word.put("width", 80.0);
            word.put("height", 12.0);
            x += 90;
        }
        ObjectNode body = words.addObject();
        body.put("text", "untypable-" + rider);
        body.put("x", 72.0);
        body.put("y", 400.0);
        body.put("width", 100.0);
        body.put("height", 12.0);
        return page;
    }

    /** Two forms on ONE sheet: every word of a paystub AND every word of a W-2, same page. */
    private JsonNode twoFormsOneSheet() {
        ObjectNode page = truthPages("paystub_complete").get(0).deepCopy();
        ArrayNode words = (ArrayNode) page.get("words");
        truthPages("w2_form").get(0).get("words").forEach(words::add);
        return page;
    }

    private void classifyAndSplit(UUID packageId) {
        for (ProcessingStatus stage :
                List.of(ProcessingStatus.CLASSIFYING, ProcessingStatus.SPLITTING)) {
            assertThat(runStage(packageId, stage).success())
                    .as("stage %s succeeds", stage)
                    .isTrue();
        }
    }

    /**
     * The predicted boundaries: each document's first page's {@code package_page_index}, in
     * ordinal order, EXCLUDING the first document — a package always begins somewhere, and
     * counting the trivial start would flatter every score.
     */
    private Set<Integer> boundariesOf(UUID packageId) {
        List<Integer> starts =
                jdbc.queryForList(
                        """
                        SELECT p.package_page_index
                          FROM logical_document d
                          JOIN logical_document_page lp
                            ON lp.logical_document_id = d.id AND lp.ordinal = 0
                          JOIN page p ON p.id = lp.page_id
                         WHERE d.package_id = ? ORDER BY d.ordinal
                        """,
                        Integer.class,
                        packageId);
        return starts.isEmpty()
                ? Set.of()
                : new TreeSet<>(starts.subList(1, starts.size()));
    }

    /** One golden package: its predicted vs. truth boundaries after the deterministic split. */
    private record Scored(Set<Integer> predicted, Set<Integer> truth) {}

    private Scored score(UUID packageId, Set<Integer> truth) {
        classifyAndSplit(packageId);
        return new Scored(boundariesOf(packageId), truth);
    }

    // ── the shapes, individually pinned ─────────────────────────────────────

    @Test
    void glue_across_unknown_is_the_known_miss_the_ai_stage_exists_for() {
        UUID packageId =
                packageOf(
                        "golden-glue",
                        List.of(
                                truthPages("paystub_complete").get(0),
                                riderPage(0),
                                riderPage(1),
                                riderPage(2)));
        Scored scored = score(packageId, Set.of(1));

        // The documented trade-off (group() javadoc): UNKNOWN continues, so the second document
        // glues to the paystub. Deterministically ZERO predicted boundaries — and none invented.
        assertThat(scored.predicted()).isEmpty();
    }

    @Test
    void consecutive_same_type_statements_are_recovered_by_the_instance_key() {
        UUID packageId = packageOf("golden-statements", truthPages("bank_statement_three"));
        Scored scored = score(packageId, Set.of(2, 4));

        assertThat(scored.predicted()).isEqualTo(scored.truth());
    }

    @Test
    void a_run_of_separate_one_page_paystubs_is_the_known_same_type_miss() {
        // The "14-page paystub" shape at test size: five distinct stubs, no type change, no
        // startsDocument anchor, no instance key on PAYSTUB — deterministically one document.
        UUID packageId =
                packageOf(
                        "golden-stub-run",
                        List.of(
                                distinctPaystub(0),
                                distinctPaystub(1),
                                distinctPaystub(2),
                                distinctPaystub(3),
                                distinctPaystub(4)));
        Scored scored = score(packageId, Set.of(1, 2, 3, 4));

        assertThat(scored.predicted()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM logical_document WHERE package_id = ?",
                                Integer.class,
                                packageId))
                .isEqualTo(1);
    }

    @Test
    void clean_type_changes_are_found_exactly() {
        List<JsonNode> pages = new ArrayList<>();
        pages.add(truthPages("paystub_complete").get(0));
        pages.add(truthPages("w2_form").get(0));
        pages.addAll(truthPages("bank_statement"));
        UUID packageId = packageOf("golden-clean", pages);
        Scored scored = score(packageId, Set.of(1, 2));

        assertThat(scored.predicted()).isEqualTo(scored.truth());
    }

    @Test
    void two_forms_on_one_sheet_stays_unsolved_but_never_silent() {
        // R11, failing BY DESIGN: page granularity cannot cut inside a sheet. What IS required is
        // the B4 flag — the reviewer must be told this sheet matches two types, because a limit a
        // reviewer can see costs one look, where a silent one costs a missed document.
        UUID packageId = packageOf("golden-sheet", List.of(twoFormsOneSheet()));
        classifyAndSplit(packageId);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM logical_document WHERE package_id = ?",
                                Integer.class,
                                packageId))
                .as("one sheet, one document — the split cannot help here")
                .isEqualTo(1);
        String evidence =
                jdbc.queryForObject(
                        """
                        SELECT evidence::text FROM classification_result
                         WHERE subject_type = 'PAGE' AND is_current
                           AND subject_id IN (SELECT id FROM page WHERE package_id = ?)
                        """,
                        String.class,
                        packageId);
        assertThat(evidence)
                .as("the multi-document-sheet flag names the co-qualifying type")
                .contains("coQualifyingTypes");
    }

    // ── the aggregate gate ──────────────────────────────────────────────────

    @Test
    void the_deterministic_boundary_baseline_is_pinned() {
        List<Scored> scored = new ArrayList<>();
        scored.add(
                score(
                        packageOf(
                                "f1-glue",
                                List.of(
                                        truthPages("paystub_complete").get(0),
                                        riderPage(0),
                                        riderPage(1),
                                        riderPage(2))),
                        Set.of(1)));
        scored.add(score(packageOf("f1-statements", truthPages("bank_statement_three")), Set.of(2, 4)));
        scored.add(
                score(
                        packageOf(
                                "f1-stub-run",
                                List.of(
                                        distinctPaystub(0),
                                        distinctPaystub(1),
                                        distinctPaystub(2),
                                        distinctPaystub(3),
                                        distinctPaystub(4))),
                        Set.of(1, 2, 3, 4)));
        List<JsonNode> clean = new ArrayList<>();
        clean.add(truthPages("paystub_complete").get(0));
        clean.add(truthPages("w2_form").get(0));
        clean.addAll(truthPages("bank_statement"));
        scored.add(score(packageOf("f1-clean", clean), Set.of(1, 2)));

        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        for (Scored s : scored) {
            Set<Integer> hits = new HashSet<>(s.predicted());
            hits.retainAll(s.truth());
            truePositives += hits.size();
            falsePositives += s.predicted().size() - hits.size();
            falseNegatives += s.truth().size() - hits.size();
        }

        // PRECISION 1.0 IS THE INVARIANT: a deterministic cut is a proof, and a false positive
        // is a bug wherever it comes from. RECALL is the measured, named loss (glue: 1 miss;
        // same-type stub run: 4 misses) that Phase D/E exist to recover. If either number moves,
        // a human decides whether the change is an improvement — that decision must never be
        // made silently by a diff nobody read.
        assertThat(falsePositives).as("deterministic split invented a boundary").isZero();
        assertThat(truePositives).isEqualTo(4);
        assertThat(falseNegatives).isEqualTo(5);

        double precision = truePositives / (double) (truePositives + falsePositives);
        double recall = truePositives / (double) (truePositives + falseNegatives);
        double f1 = 2 * precision * recall / (precision + recall);
        assertThat(precision).isEqualTo(1.0);
        assertThat(f1).as("deterministic boundary F1 baseline").isCloseTo(8.0 / 13.0, within(1e-9));
    }
}
