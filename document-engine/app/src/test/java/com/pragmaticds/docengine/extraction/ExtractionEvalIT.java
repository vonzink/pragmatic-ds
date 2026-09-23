package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.Baseline;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.EvalCase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The deterministic extraction measuring stick: every corpus case through CLASSIFYING → SPLITTING →
 * EXTRACTING, scored for completeness, accuracy, classification, normalization, rung and evidence,
 * reported per document type as a build artifact, and gated against a committed baseline.
 *
 * <p><b>Why this exists alongside the per-type ITs.</b> {@code W2ExtractionIT} and its siblings
 * assert MECHANISM — which rung fired, which evidence box, why the value is trustworthy — and they
 * remain the right place for that. What a pass/fail test cannot do is answer "did adding the ninth
 * document type make the third one worse", because it publishes no number to compare. This class
 * publishes the numbers ({@code build/reports/extraction-eval/summary.json} and {@code summary.md}),
 * and a new document type costs one small JSON file here rather than a new 300-line test class.
 *
 * <p><b>The baseline is a floor, not a target.</b> A type may land with a modest score and rise
 * later; what it may never do is silently fall. A regression fails the build with the type, the
 * metric, and the fields that went missing or wrong. A large IMPROVEMENT fails it too — loudly,
 * telling you to raise the floor, since a ratchet that never tightens stops being a ratchet.
 *
 * <p><b>Calibrating.</b> The floors are measured, never guessed. Run with
 * {@code -Ddocengine.eval.calibrate=true} and the run writes a ready-to-commit {@code baseline.json}
 * into the report directory instead of asserting against the old one. Read the diff before
 * committing it: a floor that dropped is a regression you are about to bless.
 */
class ExtractionEvalIT extends AbstractExtractionEvalIT {

    @Test
    void every_document_type_extracts_at_or_above_its_committed_baseline() {
        ExtractionEvalCorpus corpus = ExtractionEvalCorpus.load();
        ExtractionEvalReport report = evaluate(corpus);
        Baseline baseline = ExtractionEvalCorpus.baseline();

        ExtractionEvalGate.Result result = report(report, baseline, "");

        if (CALIBRATE) {
            return;
        }
        if (baseline.isEmpty()) {
            // Dormant rather than green-by-default: the run still measures and reports every
            // metric, it just has nothing to compare them against yet. Floors must be MEASURED,
            // and this build is the thing that measures them.
            System.out.println(
                    "WARNING: extraction-eval baseline has no floors, so nothing is gated."
                            + " Re-run with -Ddocengine.eval.calibrate=true and commit"
                            + " app/build/reports/extraction-eval/baseline.json.");
            return;
        }
        if (!result.passed()) {
            fail(result.describe());
        }
    }

    /**
     * The corpus must cover every type that has a seeded extraction schema. Without this, adding a
     * type to the database and forgetting its eval case would leave it silently unmeasured — which
     * is precisely the hole this harness closes, so it is checked rather than trusted.
     */
    @Test
    void corpus_covers_every_type_with_a_seeded_extraction_schema() {
        List<String> seeded =
                jdbc.queryForList(
                        "SELECT DISTINCT document_type_code FROM extraction_schema"
                                + " WHERE org_id IS NULL ORDER BY 1",
                        String.class);

        assertThat(ExtractionEvalCorpus.load().coveredTypes())
                .as("every type with a seeded extraction schema needs an eval case")
                .containsAll(seeded);
    }

    @Test
    void corpus_is_synthetic_and_names_only_generated_fixtures() {
        ExtractionEvalCorpus corpus = ExtractionEvalCorpus.load();

        assertThat(corpus.cases()).isNotEmpty();
        assertThat(corpus.cases()).allMatch(EvalCase::synthetic);
        // The fixture must resolve in fixtures/truth, which generate.py owns and CI sha256-pins.
        // A case naming anything else cannot load, so a real document has no route in.
        corpus.cases().forEach(testCase -> assertThat(truth(testCase.fixture())).isNotNull());
        // Ground truth is READ, never re-authored: a case that resolved no fields at all is a
        // fixture missing its expectedFields block, which would score a silent, meaningless 100%.
        assertThat(corpus.cases())
                .allSatisfy(
                        testCase ->
                                assertThat(
                                                testCase.documents().stream()
                                                        .mapToInt(
                                                                document ->
                                                                        document.fields().size())
                                                        .sum())
                                        .as("%s resolved no expected fields", testCase.id())
                                        .isPositive());
    }
}
