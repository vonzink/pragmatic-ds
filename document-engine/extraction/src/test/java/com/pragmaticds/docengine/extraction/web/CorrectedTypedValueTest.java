package com.pragmaticds.docengine.extraction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.extract.NormalizedValue;
import com.pragmaticds.docengine.extraction.extract.Normalizers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A human's correction and the machine's capture must type IDENTICALLY.
 *
 * <p>They are read side by side on every field surface, and the export cannot say the reviewer's own
 * value is unparseable while the machine's equivalent is not. This class asserts that equivalence
 * DIRECTLY — every case runs both paths and compares — rather than restating expected numbers,
 * because a restated expectation is exactly what let the two implementations drift apart before:
 * the local money parser rejected the accounting negative {@code (18,470)} that {@code Normalizers}
 * accepts, so a reviewer correcting a Schedule E loss by typing what the form prints got a null
 * typed value in the export.
 *
 * <p>Written so it would FAIL against the pre-fix class: the regression cases below are the ones
 * that produced an empty typed value while the machine path produced a number or a date.
 */
class CorrectedTypedValueTest {

    // ── the drift cases: what the old local parsers got wrong ───────────────

    @ParameterizedTest
    @ValueSource(strings = {"(18,470)", "(1,234.56)", "(500)", "-18470", "-1,234.56"})
    void a_negative_amount_types_the_way_the_machine_types_it(String printed) {
        assertThat(CorrectedTypedValue.of("MONEY", printed).number())
                .as("accounting/negative money must not become a null typed value")
                .isNotNull()
                .isNegative()
                .isEqualByComparingTo(machineNumber("money", printed));
    }

    @Test
    void a_two_digit_year_types_the_way_the_machine_types_it() {
        // The old local parser knew no two-digit format at all; the machine path pivots to 2000.
        assertThat(CorrectedTypedValue.of("DATE", "01/15/25").date())
                .isNotNull()
                .isEqualTo(machineDate("date", "01/15/25"));
    }

    @Test
    void an_impossible_day_is_rejected_rather_than_silently_clamped() {
        // The old local parser resolved yyyy SMART, which clamps 02/30 to the month's last day and
        // hands a reviewer a date they never typed. STRICT (uuuu) refuses it, and refusing is the
        // engine's rule everywhere: missing beats wrong.
        assertThat(CorrectedTypedValue.of("DATE", "02/30/2024").date()).isNull();
        assertThat(Normalizers.normalize("date", "02/30/2024")).isEmpty();
    }

    // ── the equivalence itself, across every shape a reviewer types ─────────

    @ParameterizedTest
    @ValueSource(
            strings = {
                "8,888.88", "$3,565.87", "1,234", "0", "0.00", "48231.30", "$0.01",
                "(18,470)", "-5.00", "N/A", "", "   ", "not a number", "12.345", "1e5"
            })
    void money_corrections_agree_with_the_machine_on_every_input(String typed) {
        assertThat(CorrectedTypedValue.of("MONEY", typed).number())
                .as("correction and machine disagree on %s", typed)
                .isEqualTo(machineNumber("money", typed));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "01/15/2025", "1/5/2025", "2025-01-15", "January 15, 2025", "Jan 15, 2025",
                "01/15/25", "02/30/2024", "not a date", "", "2025-13-01"
            })
    void date_corrections_agree_with_the_machine_on_every_input(String typed) {
        assertThat(CorrectedTypedValue.of("DATE", typed).date())
                .as("correction and machine disagree on %s", typed)
                .isEqualTo(machineDate("date", typed));
    }

    // ── the parts that are deliberately NOT the normalizer's ────────────────

    @Test
    void a_string_correction_is_its_own_typed_value_untouched_by_any_normalizer() {
        // STRING/ENUM never route through a normalizer: a reviewer's text IS the value, and
        // personName/entityName cleaning would silently edit what they typed.
        for (String dataType : List.of("STRING", "ENUM", "SOMETHING_NEW")) {
            CorrectedTypedValue typed = CorrectedTypedValue.of(dataType, "  Smith & Sons, LLC  ");
            assertThat(typed.text()).isEqualTo("Smith & Sons, LLC");
            assertThat(typed.number()).isNull();
            assertThat(typed.date()).isNull();
        }
    }

    @Test
    void a_blank_or_absent_correction_types_to_nothing() {
        assertThat(CorrectedTypedValue.of("MONEY", null).exportArm()).isNull();
        assertThat(CorrectedTypedValue.of("MONEY", "   ").exportArm()).isNull();
        assertThat(CorrectedTypedValue.of(null, "8,888.88").exportArm()).isNull();
    }

    @Test
    void the_export_arm_is_the_one_populated_arm() {
        assertThat(CorrectedTypedValue.of("MONEY", "8,888.88").exportArm())
                .isEqualTo(new BigDecimal("8888.88"));
        assertThat(CorrectedTypedValue.of("DATE", "2025-01-15").exportArm()).isEqualTo("2025-01-15");
        assertThat(CorrectedTypedValue.of("STRING", "ACME").exportArm()).isEqualTo("ACME");
        // Unparseable for its type: null, never a wrong number. displayedText still carries truth.
        assertThat(CorrectedTypedValue.of("MONEY", "N/A").exportArm()).isNull();
    }

    // ── the machine path, called directly ───────────────────────────────────

    private static BigDecimal machineNumber(String normalizer, String raw) {
        return Normalizers.normalize(normalizer, raw).map(NormalizedValue::number).orElse(null);
    }

    private static LocalDate machineDate(String normalizer, String raw) {
        return Normalizers.normalize(normalizer, raw).map(NormalizedValue::date).orElse(null);
    }

    @Test
    void the_machine_path_used_as_the_oracle_really_parses_something() {
        // Guards the equivalence tests above from passing vacuously by both sides being null.
        Optional<NormalizedValue> parsed = Normalizers.normalize("money", "(18,470)");
        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().number()).isEqualByComparingTo(new BigDecimal("-18470"));
    }
}
