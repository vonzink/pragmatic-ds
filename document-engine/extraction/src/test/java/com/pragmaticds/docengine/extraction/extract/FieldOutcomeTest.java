package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The occurrence dimension on the engine's verdict. {@code groupKey} is the value PRINTED on the
 * form — {@code A}/{@code B}/{@code C} for Schedule E's property columns, the row ordinal for
 * entity tables — and it is null for every ungrouped field, which is every field that exists
 * before Spec 5a (design D2).
 *
 * <p>The missing-occurrence pair is the load-bearing part: a column that is empty on the form must
 * persist as a MISSING occurrence carrying its own key — never absent, and never a defaulted zero,
 * because a zero in a rental expense silently changes a qualifying-income calculation (design D5).
 */
class FieldOutcomeTest {

    private static FieldSpec field() {
        return new FieldSpec(
                "rentsReceived",
                DataType.MONEY,
                true,
                "money",
                false,
                List.of(
                        new ExtractorSpec(
                                ExtractionMethod.ANCHOR_LABEL,
                                0.9,
                                new LabelSpec(AnchorKind.LITERAL, "Rents received"),
                                null,
                                new ValueSpec("x", 0, ValueScope.LINE))));
    }

    @Test
    void an_ungrouped_missing_outcome_has_a_null_group_key() {
        FieldOutcome outcome = FieldOutcome.missing(field());

        assertThat(outcome.groupKey()).isNull();
        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.confidence()).isEqualTo(ConfidenceBreakdown.ZERO);
    }

    @Test
    void a_missing_occurrence_carries_its_key_and_is_otherwise_the_missing_shape() {
        FieldOutcome occurrence = FieldOutcome.missing(field(), "C");

        assertThat(occurrence.groupKey()).isEqualTo("C");
        assertThat(occurrence.found()).isFalse();
        assertThat(occurrence.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(occurrence.pageId()).isNull();
        assertThat(occurrence.displayedText()).isNull();
        assertThat(occurrence.rawValue()).isNull();
        assertThat(occurrence.normalized())
                .as("a missing occurrence is never a defaulted 0.00")
                .isNull();
        assertThat(occurrence.valueEvidence()).isEmpty();
        assertThat(occurrence.labelEvidence()).isEmpty();
        assertThat(occurrence.confidence()).isEqualTo(ConfidenceBreakdown.ZERO);
    }

    @Test
    void two_missing_occurrences_differ_only_by_their_key() {
        FieldSpec spec = field();

        assertThat(FieldOutcome.missing(spec, "B")).isNotEqualTo(FieldOutcome.missing(spec, "C"));
        assertThat(FieldOutcome.missing(spec, null)).isEqualTo(FieldOutcome.missing(spec));
    }

    @Test
    void the_pre_spec5a_ten_argument_shape_still_builds_an_ungrouped_outcome() {
        // Every rung that predates grouping constructs the outcome positionally. The convenience
        // constructor is what keeps CHECKBOX_STATE and SIGNATURE_PRESENCE — and every existing
        // test that compares against them — untouched by this spec.
        FieldOutcome outcome =
                new FieldOutcome(
                        field(),
                        ExtractionMethod.NONE,
                        0.0,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        ConfidenceBreakdown.ZERO);

        assertThat(outcome.groupKey()).isNull();
        assertThat(outcome).isEqualTo(FieldOutcome.missing(field()));
    }
}
