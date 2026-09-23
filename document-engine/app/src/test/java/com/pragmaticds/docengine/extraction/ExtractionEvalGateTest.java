package com.pragmaticds.docengine.extraction;

import static com.pragmaticds.docengine.extraction.ExtractionEvalScorerTest.UNKNOWN_TYPE;
import static com.pragmaticds.docengine.extraction.ExtractionEvalScorerTest.captured;
import static com.pragmaticds.docengine.extraction.ExtractionEvalScorerTest.corpus;
import static com.pragmaticds.docengine.extraction.ExtractionEvalScorerTest.empty;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.Baseline;
import com.pragmaticds.docengine.extraction.ExtractionEvalGate.Kind;
import com.pragmaticds.docengine.extraction.ExtractionEvalGate.Result;
import com.pragmaticds.docengine.extraction.ExtractionEvalScorer.ActualDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gate that fails the build, exercised on a report whose numbers are known by construction:
 * the type SYNTHETIC_TYPE_X fills 2 of 3 printed fields (66.67% complete) and gets 1 of those 2
 * right (50% accurate). The tests here are the issue's definition of done made executable — raise
 * a floor above what was measured and the failure must name the type, the metric and the field.
 */
class ExtractionEvalGateTest {

    private static final ExtractionEvalReport REPORT = measuredReport();

    @Test
    void floors_at_the_measured_value_pass() {
        Baseline baseline = baseline(Map.of("completeness", 0.66, "accuracy", 0.50));

        Result result = ExtractionEvalGate.check(REPORT, baseline);

        assertThat(result.passed()).as(result.describe()).isTrue();
        assertThat(result.gated()).isTrue();
        assertThat(result.byType().get(UNKNOWN_TYPE).label()).isEqualTo("pass");
    }

    @Test
    void a_floor_raised_above_the_measured_value_fails_naming_the_type_the_metric_and_the_field() {
        Baseline baseline = baseline(Map.of("completeness", 0.99, "accuracy", 0.50));

        Result result = ExtractionEvalGate.check(REPORT, baseline);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        ExtractionEvalGate.Violation violation = result.violations().get(0);
        assertThat(violation.kind()).isEqualTo(Kind.REGRESSION);
        assertThat(violation.scope()).isEqualTo(UNKNOWN_TYPE);
        assertThat(violation.metric()).isEqualTo("completeness");
        String message = result.describe();
        assertThat(message)
                .contains("FAIL")
                .contains(UNKNOWN_TYPE + ".completeness regressed below its committed floor")
                .contains("measured 0.6667, floor 0.99")
                .contains("missing  sample/document[0].missing#")
                .contains("wrong    sample/document[0].wrong#");
        assertThat(result.byType().get(UNKNOWN_TYPE).label()).isEqualTo("FAIL");
    }

    @Test
    void an_accuracy_floor_above_the_measured_value_names_the_wrong_field() {
        Baseline baseline = baseline(Map.of("accuracy", 0.75));

        Result result = ExtractionEvalGate.check(REPORT, baseline);

        assertThat(result.violations()).extracting(ExtractionEvalGate.Violation::metric).containsExactly("accuracy");
        assertThat(result.describe())
                .contains(UNKNOWN_TYPE + ".accuracy regressed")
                .contains("measured 0.5000, floor 0.75")
                .contains("wrong    sample/document[0].wrong#");
    }

    @Test
    void a_measurement_far_above_its_floor_is_a_stale_baseline_and_also_fails() {
        Baseline baseline = baseline(Map.of("completeness", 0.50));

        Result result = ExtractionEvalGate.check(REPORT, baseline);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations().get(0).kind()).isEqualTo(Kind.STALE);
        assertThat(result.describe()).contains("re-run with -Ddocengine.eval.calibrate=true");
    }

    @Test
    void overall_floors_gate_the_roll_up_the_same_way() {
        Baseline baseline = new Baseline(Map.of("completeness", 0.99), Map.of());

        Result result = ExtractionEvalGate.check(REPORT, baseline);

        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).scope()).isEqualTo("overall");
        assertThat(result.describe()).contains("overall.completeness regressed");
    }

    @Test
    void a_baseline_naming_a_type_the_corpus_no_longer_covers_fails() {
        Baseline baseline =
                new Baseline(Map.of(), Map.of("VANISHED_TYPE", Map.of("completeness", 0.5)));

        Result result = ExtractionEvalGate.check(REPORT, baseline);

        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).kind()).isEqualTo(Kind.UNCOVERED_TYPE);
        assertThat(result.describe()).contains("baseline names type VANISHED_TYPE but the corpus covers none");
    }

    @Test
    void a_baseline_naming_an_unknown_metric_fails_instead_of_gating_nothing() {
        Baseline baseline = baseline(Map.of("completness", 0.5));

        Result result = ExtractionEvalGate.check(REPORT, baseline);

        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).kind()).isEqualTo(Kind.UNKNOWN_METRIC);
    }

    @Test
    void a_floorless_baseline_passes_but_reports_itself_as_ungated() {
        Result result = ExtractionEvalGate.check(REPORT, new Baseline(Map.of(), Map.of()));

        assertThat(result.passed()).isTrue();
        assertThat(result.gated()).isFalse();
        assertThat(result.toMarkdown()).contains("not gated");
    }

    @Test
    void markdown_lists_every_type_with_counts_floors_and_verdict() {
        Baseline baseline = baseline(Map.of("completeness", 0.99, "accuracy", 0.50));

        String markdown = ExtractionEvalGate.check(REPORT, baseline).toMarkdown();

        assertThat(markdown)
                .contains("# Extraction completeness by document type")
                .contains("**Gate: FAIL (1 violation)**")
                .contains("| type | cases | fields | filled | correct | completeness | accuracy |")
                .contains("| " + UNKNOWN_TYPE + " | 1 | 3 | 2 | 1 | 66.7% | 50.0% | 99.0% | 50.0% | FAIL |")
                .contains("| **overall** | 1 | 3 | 2 | 1 | 66.7% | 50.0% | — | — | not gated |")
                .contains("- missing `sample/document[0].missing#`")
                .contains("- wrong `sample/document[0].wrong#`")
                .contains("## Violations");
    }

    @Test
    void calibration_writes_every_gated_metric_one_notch_under_its_measurement() throws Exception {
        JsonNode proposed = new ObjectMapper().readTree(ExtractionEvalGate.calibrate(REPORT));

        JsonNode type = proposed.get("byType").get(UNKNOWN_TYPE);
        assertThat(type.get("completeness").decimalValue()).isEqualByComparingTo("0.65");
        assertThat(type.get("accuracy").decimalValue()).isEqualByComparingTo("0.49");
        assertThat(proposed.get("overall").get("completeness").decimalValue()).isEqualByComparingTo("0.65");
        // A calibrated baseline gates: the same report passes against what it just proposed.
        Baseline roundTrip = new ObjectMapper().treeToValue(proposed, Baseline.class);
        assertThat(ExtractionEvalGate.check(REPORT, roundTrip).passed()).isTrue();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ExtractionEvalReport measuredReport() {
        ActualDocument observed =
                new ActualDocument(
                        UNKNOWN_TYPE,
                        Map.of(
                                "right#", captured("10.00"),
                                "wrong#", captured("99.99"),
                                "missing#", empty(),
                                "absent#", empty()));
        return new ExtractionEvalScorer()
                .score(corpus(UNKNOWN_TYPE, "sample"), Map.of("sample", List.of(observed)));
    }

    private static Baseline baseline(Map<String, Double> typeFloors) {
        return new Baseline(Map.of(), Map.of(UNKNOWN_TYPE, typeFloors));
    }
}
