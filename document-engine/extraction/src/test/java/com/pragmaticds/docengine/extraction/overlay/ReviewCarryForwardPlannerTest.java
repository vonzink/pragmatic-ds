package com.pragmaticds.docengine.extraction.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules that decide whether a human's review survives a re-extraction, pinned without a
 * database so each one is stated once and legibly.
 *
 * <p>{@code CorrectionSurvivesReExtractionIT} proves the whole machine end to end on the ordinary
 * case. These pin the cases that IT cannot reach cheaply and that a later simplification would
 * otherwise quietly break — repeating groups, a reading that changed underneath the decision, and
 * the coordinates that must carry NOTHING.
 */
class ReviewCarryForwardPlannerTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final UUID OTHER_DOC = UUID.randomUUID();
    private static final UUID SCHEMA = UUID.randomUUID();
    private static final UUID NEXT_SCHEMA = UUID.randomUUID();

    private static FieldSnapshot field(
            String name, String groupKey, String displayed, String reviewStatus) {
        return field(DOC, SCHEMA, name, groupKey, displayed, reviewStatus);
    }

    private static FieldSnapshot field(
            UUID documentId,
            UUID schemaId,
            String name,
            String groupKey,
            String displayed,
            String reviewStatus) {
        return new FieldSnapshot(
                UUID.randomUUID(), documentId, name, groupKey, schemaId, displayed, reviewStatus);
    }

    @Test
    void a_correction_moves_to_the_row_that_replaced_it() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot after = field("netPay", null, "1,000.00", ExtractedField.REVIEW_NOT_REVIEWED);

        List<ReviewCarryForwardPlanner.Rebinding> plan =
                ReviewCarryForwardPlanner.plan(List.of(before), List.of(after));

        assertThat(plan)
                .containsExactly(
                        new ReviewCarryForwardPlanner.Rebinding(
                                before.fieldId(),
                                after.fieldId(),
                                ExtractedField.REVIEW_CORRECTED,
                                true));
    }

    @Test
    void an_untouched_field_carries_nothing() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_NOT_REVIEWED);
        FieldSnapshot after = field("netPay", null, "1,000.00", ExtractedField.REVIEW_NOT_REVIEWED);

        assertThat(ReviewCarryForwardPlanner.plan(List.of(before), List.of(after))).isEmpty();
    }

    /**
     * The occurrence dimension. A correction on property B must land on property B and on nothing
     * else — the failure mode a name-only key would produce is a value silently attributed to the
     * wrong property, which is worse than losing the correction outright.
     */
    @Test
    void a_repeating_group_correction_stays_on_its_own_occurrence() {
        FieldSnapshot rentsA = field("rents", "A", "1,100.00", ExtractedField.REVIEW_NOT_REVIEWED);
        FieldSnapshot rentsB = field("rents", "B", "2,200.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot newRentsA = field("rents", "A", "1,100.00", ExtractedField.REVIEW_NOT_REVIEWED);
        FieldSnapshot newRentsB = field("rents", "B", "2,200.00", ExtractedField.REVIEW_NOT_REVIEWED);

        List<ReviewCarryForwardPlanner.Rebinding> plan =
                ReviewCarryForwardPlanner.plan(
                        List.of(rentsA, rentsB), List.of(newRentsA, newRentsB));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).previousFieldId()).isEqualTo(rentsB.fieldId());
        assertThat(plan.get(0).newFieldId()).isEqualTo(newRentsB.fieldId());
    }

    /**
     * The ungrouped row's key is {@code null}, the pre-Spec-5a shape. It must not collide with a
     * keyed occurrence of the same name, or a single-valued correction would land on an occurrence.
     */
    @Test
    void an_ungrouped_occurrence_does_not_match_a_keyed_one() {
        FieldSnapshot before = field("rents", null, "1,100.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot after = field("rents", "A", "1,100.00", ExtractedField.REVIEW_NOT_REVIEWED);

        assertThat(ReviewCarryForwardPlanner.plan(List.of(before), List.of(after))).isEmpty();
    }

    /** A regroup that moves a page into a NEW document does not drag corrections across. */
    @Test
    void a_coordinate_in_another_document_carries_nothing() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot after =
                field(
                        OTHER_DOC,
                        SCHEMA,
                        "netPay",
                        null,
                        "1,000.00",
                        ExtractedField.REVIEW_NOT_REVIEWED);

        assertThat(ReviewCarryForwardPlanner.plan(List.of(before), List.of(after))).isEmpty();
    }

    /** A field the new schema no longer emits: the decision stays on record, but stops applying. */
    @Test
    void a_vanished_coordinate_carries_nothing() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot after = field("grossPay", null, "2,000.00", ExtractedField.REVIEW_NOT_REVIEWED);

        assertThat(ReviewCarryForwardPlanner.plan(List.of(before), List.of(after))).isEmpty();
    }

    /**
     * The correction outlives a changed reading — dropping it would restore a machine value a human
     * already refused — but {@code sameReading} is false, which is what escalates the occurrence
     * back into the review queue instead of letting the human's value go stale unnoticed.
     */
    @Test
    void a_correction_survives_a_changed_reading_but_is_flagged() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot after = field("netPay", null, "1,050.00", ExtractedField.REVIEW_NOT_REVIEWED);

        List<ReviewCarryForwardPlanner.Rebinding> plan =
                ReviewCarryForwardPlanner.plan(List.of(before), List.of(after));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).reviewStatus()).isEqualTo(ExtractedField.REVIEW_CORRECTED);
        assertThat(plan.get(0).sameReading()).isFalse();
    }

    /** A rejection is the fail-safe direction: keep withholding a value nobody has approved. */
    @Test
    void a_rejection_survives_a_changed_reading_and_is_flagged() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_REJECTED);
        FieldSnapshot after = field("netPay", null, "1,050.00", ExtractedField.REVIEW_NOT_REVIEWED);

        List<ReviewCarryForwardPlanner.Rebinding> plan =
                ReviewCarryForwardPlanner.plan(List.of(before), List.of(after));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).reviewStatus()).isEqualTo(ExtractedField.REVIEW_REJECTED);
        assertThat(plan.get(0).sameReading()).isFalse();
    }

    /**
     * A CONFIRM is agreement with ONE value. Carrying it onto a different one would put a human's
     * name on a number no human ever saw, so it reverts to unreviewed instead.
     */
    @Test
    void a_confirmation_does_not_survive_a_changed_reading() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CONFIRMED);
        FieldSnapshot after = field("netPay", null, "1,050.00", ExtractedField.REVIEW_NOT_REVIEWED);

        assertThat(ReviewCarryForwardPlanner.plan(List.of(before), List.of(after))).isEmpty();
    }

    @Test
    void a_confirmation_survives_an_unchanged_reading() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CONFIRMED);
        FieldSnapshot after = field("netPay", null, "1,000.00", ExtractedField.REVIEW_NOT_REVIEWED);

        List<ReviewCarryForwardPlanner.Rebinding> plan =
                ReviewCarryForwardPlanner.plan(List.of(before), List.of(after));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).sameReading()).isTrue();
    }

    /**
     * A schema version bump can redefine what a field name means, so the decision was made about a
     * different question. Same treatment as a changed value: carried, but flagged.
     */
    @Test
    void a_changed_schema_version_counts_as_a_changed_reading() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot after =
                field(
                        DOC,
                        NEXT_SCHEMA,
                        "netPay",
                        null,
                        "1,000.00",
                        ExtractedField.REVIEW_NOT_REVIEWED);

        List<ReviewCarryForwardPlanner.Rebinding> plan =
                ReviewCarryForwardPlanner.plan(List.of(before), List.of(after));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).sameReading()).isFalse();
    }

    /**
     * The unique index makes this impossible for a well-formed generation, so reaching it means an
     * assumption broke. The safe answer to a broken assumption is to carry nothing — never to pick
     * one of the candidates and hope.
     */
    @Test
    void an_ambiguous_coordinate_carries_nothing() {
        FieldSnapshot beforeOne = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot beforeTwo = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CONFIRMED);
        FieldSnapshot after = field("netPay", null, "1,000.00", ExtractedField.REVIEW_NOT_REVIEWED);

        assertThat(ReviewCarryForwardPlanner.plan(List.of(beforeOne, beforeTwo), List.of(after)))
                .isEmpty();
    }

    @Test
    void a_duplicated_replacement_coordinate_carries_nothing() {
        FieldSnapshot before = field("netPay", null, "1,000.00", ExtractedField.REVIEW_CORRECTED);
        FieldSnapshot afterOne = field("netPay", null, "1,000.00", ExtractedField.REVIEW_NOT_REVIEWED);
        FieldSnapshot afterTwo = field("netPay", null, "1,000.00", ExtractedField.REVIEW_NOT_REVIEWED);

        assertThat(ReviewCarryForwardPlanner.plan(List.of(before), List.of(afterOne, afterTwo)))
                .isEmpty();
    }
}
