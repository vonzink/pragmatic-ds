package com.pragmaticds.docengine.extraction.overlay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The wiring the planner cannot pin: that BOTH halves of a carried decision are written, and that
 * the flag reaches the row.
 *
 * <p>Both halves matter independently. The {@code review_status} alone would badge a value as
 * corrected that the read overlay has no decision to serve, and the decision alone would serve a
 * human's value under a NOT_REVIEWED badge — either way the two layers contradict each other in the
 * same response, which is exactly the class of drift {@code EffectiveStatus} exists to prevent.
 */
class ReviewCarryForwardTest {

    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID SCHEMA = UUID.randomUUID();

    private final ExtractedFieldRepository fields = mock(ExtractedFieldRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<FieldDecisionCarryForwardPort> provider =
            mock(ObjectProvider.class);

    private final FieldDecisionCarryForwardPort port = mock(FieldDecisionCarryForwardPort.class);

    private final ReviewCarryForward carryForward = new ReviewCarryForward(fields, provider);

    private ExtractedField replacement(String displayedText) {
        ExtractedField field =
                new ExtractedField(
                        DOCUMENT,
                        SCHEMA,
                        "netPay",
                        "STRING",
                        displayedText,
                        displayedText,
                        displayedText,
                        null,
                        null,
                        null,
                        "ANCHOR",
                        "engine/1.0.0",
                        BigDecimal.ONE,
                        null,
                        ExtractedField.VALIDATION_VALID,
                        ExtractedField.REVIEW_NOT_REVIEWED,
                        false);
        // The id a real save() would have assigned — the carry is keyed on it.
        ReflectionTestUtils.setField(field, "id", UUID.randomUUID());
        return field;
    }

    private FieldSnapshot corrected(String machineValue) {
        return new FieldSnapshot(
                UUID.randomUUID(),
                DOCUMENT,
                "netPay",
                null,
                SCHEMA,
                machineValue,
                ExtractedField.REVIEW_CORRECTED);
    }

    @Test
    void stamps_the_status_and_hands_the_correction_to_review() {
        when(provider.getIfAvailable()).thenReturn(port);
        FieldSnapshot before = corrected("1,000.00");
        ExtractedField after = replacement("1,000.00");

        int carried = carryForward.apply(List.of(before), List.of(after));

        assertThat(carried).isEqualTo(1);
        assertThat(after.getReviewStatus()).isEqualTo(ExtractedField.REVIEW_CORRECTED);
        // Unchanged reading: nothing to re-examine, so validation is left exactly as extracted.
        assertThat(after.getValidationStatus()).isEqualTo(ExtractedField.VALIDATION_VALID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FieldDecisionCarryForwardPort.Carry>> carries =
                ArgumentCaptor.forClass(List.class);
        verify(port).carryForward(carries.capture());
        assertThat(carries.getValue())
                .containsExactly(
                        new FieldDecisionCarryForwardPort.Carry(
                                before.fieldId(), after.getId(), "1,000.00"));
    }

    @Test
    void a_changed_reading_keeps_the_human_value_and_asks_for_a_fresh_look() {
        when(provider.getIfAvailable()).thenReturn(port);
        ExtractedField after = replacement("1,050.00");

        carryForward.apply(List.of(corrected("1,000.00")), List.of(after));

        assertThat(after.getReviewStatus()).isEqualTo(ExtractedField.REVIEW_CORRECTED);
        assertThat(after.getValidationStatus())
                .isEqualTo(ExtractedField.VALIDATION_MANUAL_REVIEW_REQUIRED);
    }

    /**
     * The carried decision records what the human's value now stands in front of — the NEW machine
     * reading, not the stale one, or the trail would describe a comparison that never happened.
     */
    @Test
    void the_carry_reports_the_new_machine_reading() {
        when(provider.getIfAvailable()).thenReturn(port);
        ExtractedField after = replacement("1,050.00");

        carryForward.apply(List.of(corrected("1,000.00")), List.of(after));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FieldDecisionCarryForwardPort.Carry>> carries =
                ArgumentCaptor.forClass(List.class);
        verify(port).carryForward(carries.capture());
        assertThat(carries.getValue().get(0).machineValue()).isEqualTo("1,050.00");
    }

    /** With {@code :review} off the classpath there is nothing to carry and nothing may explode. */
    @Test
    void survives_review_being_absent() {
        when(provider.getIfAvailable()).thenReturn(null);
        ExtractedField after = replacement("1,000.00");

        assertThat(carryForward.apply(List.of(corrected("1,000.00")), List.of(after))).isEqualTo(1);
        assertThat(after.getReviewStatus()).isEqualTo(ExtractedField.REVIEW_CORRECTED);
    }

    @Test
    void an_unreviewed_generation_touches_nothing() {
        assertThat(carryForward.apply(List.of(), List.of(replacement("1,000.00")))).isZero();
        verify(fields, never()).save(any());
        verify(port, never()).carryForward(any());
    }
}
