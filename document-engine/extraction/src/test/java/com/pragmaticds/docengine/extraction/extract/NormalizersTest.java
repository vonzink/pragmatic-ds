package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The normalizer registry. Certainty is the normalizer-certainty confidence component: 1.0 for a
 * strict-format parse, lower for a lenient one; an empty Optional is a normalization FAILURE and
 * fails the extractor rung.
 */
class NormalizersTest {

    // ── money ───────────────────────────────────────────────────────────────

    @Test
    void money_strict_format_with_dollar_sign_and_commas_parses_at_full_certainty() {
        NormalizedValue value = Normalizers.normalize("money", "$48,231.30").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("48231.30");
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(value.text()).isNull();
        assertThat(value.date()).isNull();
    }

    @Test
    void money_strict_format_without_dollar_sign_parses_at_full_certainty() {
        NormalizedValue value = Normalizers.normalize("money", "4,670.69").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("4670.69");
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void money_plain_digits_parse_leniently_at_reduced_certainty() {
        NormalizedValue value = Normalizers.normalize("money", "4670.69").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("4670.69");
        assertThat(value.certainty()).isEqualByComparingTo("0.9");
    }

    @Test
    void money_digits_without_cents_parse_leniently() {
        NormalizedValue value = Normalizers.normalize("money", "612").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("612");
        assertThat(value.certainty()).isEqualByComparingTo("0.9");
    }

    @Test
    void money_garbage_is_a_normalization_failure() {
        assertThat(Normalizers.normalize("money", "N/A")).isEmpty();
    }

    /**
     * Accounting notation: a parenthesised amount is a NEGATIVE amount. Schedule E line 21 is
     * income OR (loss) per property, and the sign is the entire meaning of the number — an
     * $18,470 loss silently booked as $18,470 of income is a confident wrong value with a
     * correct-looking evidence box, which is strictly worse than not extracting it at all.
     */
    @Test
    void money_in_accounting_parentheses_is_negative() {
        NormalizedValue value = Normalizers.normalize("money", "(18,470)").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("-18470");
        // Parens carry the SIGN; they do not change how strictly the amount itself parsed.
        // "18,470" has no cents, so it is the lenient branch, exactly as it would be unsigned.
        assertThat(value.certainty()).isEqualByComparingTo("0.9");
    }

    @Test
    void money_in_accounting_parentheses_with_cents_stays_strict() {
        NormalizedValue value = Normalizers.normalize("money", "($1,809.44)").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("-1809.44");
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void money_with_a_leading_plus_is_positive_and_still_strict() {
        // The online print-out's month-to-date tile: "+$2,180.40". The plus is a printed sign,
        // captured as rendered; it must neither negate nor demote the amount to lenient.
        NormalizedValue value = Normalizers.normalize("money", "+$2,180.40").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo(new BigDecimal("2180.40"));
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void money_with_a_leading_minus_is_negative() {
        NormalizedValue value = Normalizers.normalize("money", "-310.25").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("-310.25");
    }

    /** A sign with nothing attached is still garbage — the rung fails, it does not guess zero. */
    @Test
    void a_bare_sign_is_still_a_normalization_failure() {
        assertThat(Normalizers.normalize("money", "-")).isEmpty();
        assertThat(Normalizers.normalize("money", "()")).isEmpty();
        assertThat(Normalizers.normalize("money", "(N/A)")).isEmpty();
    }

    /** The unsigned path is untouched — the existing money tests above are the other half. */
    @Test
    void an_unsigned_amount_keeps_its_exact_previous_result() {
        NormalizedValue value = Normalizers.normalize("money", "$48,231.30").orElseThrow();

        assertThat(value.number()).isEqualByComparingTo("48231.30");
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    // ── date ────────────────────────────────────────────────────────────────

    @Test
    void date_slash_format_is_month_first_us_policy() {
        NormalizedValue value = Normalizers.normalize("date", "01/17/2026").orElseThrow();

        assertThat(value.date()).isEqualTo(LocalDate.of(2026, 1, 17));
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void date_single_digit_month_and_day_parse() {
        assertThat(Normalizers.normalize("date", "1/5/2026").orElseThrow().date())
                .isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void date_iso_format_parses_at_full_certainty() {
        NormalizedValue value = Normalizers.normalize("date", "2026-01-17").orElseThrow();

        assertThat(value.date()).isEqualTo(LocalDate.of(2026, 1, 17));
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void date_short_month_name_parses_at_full_certainty() {
        NormalizedValue value = Normalizers.normalize("date", "Jan 5, 2026").orElseThrow();

        assertThat(value.date()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void date_full_month_name_parses_at_full_certainty() {
        assertThat(Normalizers.normalize("date", "January 5, 2026").orElseThrow().date())
                .isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void date_two_digit_year_pivots_to_the_2000s_at_reduced_certainty() {
        NormalizedValue value = Normalizers.normalize("date", "01/17/26").orElseThrow();

        assertThat(value.date()).isEqualTo(LocalDate.of(2026, 1, 17));
        assertThat(value.certainty()).isEqualByComparingTo("0.8");
    }

    @Test
    void date_with_impossible_month_and_day_is_a_normalization_failure() {
        assertThat(Normalizers.normalize("date", "13/45/2026")).isEmpty();
    }

    @Test
    void date_garbage_is_a_normalization_failure() {
        assertThat(Normalizers.normalize("date", "soon")).isEmpty();
    }

    // ── payFrequency ────────────────────────────────────────────────────────

    @Test
    void pay_frequency_is_case_and_punctuation_insensitive() {
        assertThat(Normalizers.normalize("payFrequency", "Bi-Weekly").orElseThrow().text())
                .isEqualTo("BIWEEKLY");
        assertThat(Normalizers.normalize("payFrequency", "weekly").orElseThrow().text())
                .isEqualTo("WEEKLY");
        assertThat(Normalizers.normalize("payFrequency", "Semi-Monthly").orElseThrow().text())
                .isEqualTo("SEMIMONTHLY");
        assertThat(Normalizers.normalize("payFrequency", "MONTHLY").orElseThrow().text())
                .isEqualTo("MONTHLY");
    }

    @Test
    void pay_frequency_carries_full_certainty() {
        assertThat(Normalizers.normalize("payFrequency", "Bi-Weekly").orElseThrow().certainty())
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void an_unknown_pay_frequency_is_a_normalization_failure() {
        assertThat(Normalizers.normalize("payFrequency", "fortnightly")).isEmpty();
    }

    // ── personName ──────────────────────────────────────────────────────────

    @Test
    void person_name_collapses_internal_whitespace_and_trims() {
        NormalizedValue value =
                Normalizers.normalize("personName", "  Jordan   Q.  Fixture ").orElseThrow();

        assertThat(value.text()).isEqualTo("Jordan Q. Fixture");
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void person_name_with_suspicious_characters_drops_to_reduced_certainty() {
        NormalizedValue value = Normalizers.normalize("personName", "J0rd@n Fixture").orElseThrow();

        assertThat(value.certainty()).isEqualByComparingTo("0.7");
    }

    @Test
    void person_name_keeps_full_certainty_for_a_JOINT_name_in_either_conjunction() {
        // "Name(s) shown on return" is a joint field: two people, one printed value. The
        // "and" spelling is letters and was always clean; the "&" spelling — one SPACED
        // ampersand joining two names — is how return software prints the same fact and
        // may not cost confidence either.
        NormalizedValue and_ =
                Normalizers.normalize("personName", "Jordan Q. Fixture and Casey R. Fixture")
                        .orElseThrow();
        assertThat(and_.text()).isEqualTo("Jordan Q. Fixture and Casey R. Fixture");
        assertThat(and_.certainty()).isEqualByComparingTo(BigDecimal.ONE);

        NormalizedValue ampersand =
                Normalizers.normalize("personName", "Jordan Q. Fixture & Casey R. Fixture")
                        .orElseThrow();
        assertThat(ampersand.text()).isEqualTo("Jordan Q. Fixture & Casey R. Fixture");
        assertThat(ampersand.certainty()).isEqualByComparingTo(BigDecimal.ONE);

        // Names that merely CONTAIN the conjunction's letters are ordinary names.
        assertThat(Normalizers.normalize("personName", "Armand Roland").orElseThrow().certainty())
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(Normalizers.normalize("personName", "Andy Anderson").orElseThrow().certainty())
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void person_name_ending_in_a_DANGLING_conjunction_is_a_truncated_joint_and_scores_down() {
        // The residue an incomplete joint capture leaves: the first spouse plus the
        // conjunction, second name lost. No person is named that; the value is reported
        // and scored SUSPICIOUS rather than trusted at 1.0.
        assertThat(
                        Normalizers.normalize("personName", "Jordan Q. Fixture and")
                                .orElseThrow()
                                .certainty())
                .isEqualByComparingTo("0.7");
        assertThat(
                        Normalizers.normalize("personName", "Jordan Q. Fixture &")
                                .orElseThrow()
                                .certainty())
                .isEqualByComparingTo("0.7");
    }

    @Test
    void person_name_scores_a_SPELLED_middle_name_at_full_certainty() {
        // The real return behind the Spec-5a name fix prints the second filer's middle
        // name spelled out. Letters and spaces were always inside the clean set — the
        // capture must ride through at 1.0, single or joint.
        assertThat(
                        Normalizers.normalize("personName", "Jordan Quinn Fixture")
                                .orElseThrow()
                                .certainty())
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(
                        Normalizers.normalize(
                                        "personName", "Jordan Q. Fixture and Casey Reese Fixture")
                                .orElseThrow()
                                .certainty())
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void person_name_cannot_SEE_a_missing_surname_and_is_not_asked_to() {
        // "Jordan Q. Fixture and Casey Reese" is the residue the pre-fix pattern left when
        // a spelled middle consumed the surname slot: three trailing name words, no dangling
        // conjunction, textually indistinguishable from a complete joint name. It scores
        // 1.0 HERE BY DESIGN — no text-level rule can tell it from "Jordan and Casey
        // Fixture" without also condemning real names. The @PERSON@ pattern's boundary is
        // the defense; this test pins that the normalizer is NOT asked to be.
        assertThat(
                        Normalizers.normalize("personName", "Jordan Q. Fixture and Casey Reese")
                                .orElseThrow()
                                .certainty())
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void person_name_does_not_extend_full_certainty_to_an_unspaced_ampersand() {
        // "J&B" is an entity spelling. Only the spaced pair — two names, one "&" between
        // them — reads as a joint personal name.
        assertThat(Normalizers.normalize("personName", "J&B Fixture").orElseThrow().certainty())
                .isEqualByComparingTo("0.7");
    }

    // ── entityName ──────────────────────────────────────────────────────────

    @Test
    void entity_name_keeps_full_certainty_for_every_shape_a_legal_entity_prints() {
        // The five the Schedule E fixture draws, plus the two punctuation shapes a real
        // filing adds. None of these is unusual, and none of them may cost confidence: an
        // entity-name normalizer that cried wolf on an ordinary name would make the 0.7
        // signal worthless.
        for (String name :
                List.of(
                        "SUMMIT RIDGE PARTNERS LP",
                        "1ST CHOICE PROPERTIES LLC",
                        "O'BRIEN FAMILY TRUST",
                        "Meridian Fixture Estate",
                        "McALLISTER REMIC TRUST",
                        "SMITH & JONES HOLDINGS, L.P.",
                        "Cañon City Rentals LLC")) {
            NormalizedValue value = Normalizers.normalize("entityName", name).orElseThrow();
            assertThat(value.text()).as("%s is reported verbatim", name).isEqualTo(name);
            assertThat(value.certainty()).as("%s certainty", name).isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Test
    void entity_name_collapses_internal_whitespace_and_trims() {
        NormalizedValue value =
                Normalizers.normalize("entityName", "  SUMMIT   RIDGE  PARTNERS LP ")
                        .orElseThrow();

        assertThat(value.text()).isEqualTo("SUMMIT RIDGE PARTNERS LP");
        assertThat(value.certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void a_degenerate_entity_name_is_reported_but_costs_certainty() {
        // What a truncated capture leaves behind: the head or the tail of a name, alone.
        // The value is still REPORTED — a reviewer must see what was read — but it no longer
        // arrives at full confidence, which is what a null normalizer gave it.
        for (String fragment : List.of("O", "LP", "INC")) {
            NormalizedValue value =
                    Normalizers.normalize("entityName", fragment).orElseThrow();
            assertThat(value.text()).isEqualTo(fragment);
            assertThat(value.certainty()).as("%s certainty", fragment).isEqualByComparingTo("0.7");
        }
    }

    @Test
    void an_entity_name_carrying_characters_outside_the_set_costs_certainty() {
        NormalizedValue value =
                Normalizers.normalize("entityName", "ACME <?> HOLDINGS LLC").orElseThrow();

        assertThat(value.text()).isEqualTo("ACME <?> HOLDINGS LLC");
        assertThat(value.certainty()).isEqualByComparingTo("0.7");
    }

    @Test
    void an_entity_name_that_collapses_to_nothing_is_a_normalization_failure() {
        // A value that traces to no printed text is not a value: the rung fails and the
        // ladder moves on, rather than persisting a blank name at certainty 1.
        assertThat(Normalizers.normalize("entityName", "   ")).isEmpty();
    }

    // ── default (null) ──────────────────────────────────────────────────────

    @Test
    void a_null_normalizer_yields_trimmed_whitespace_collapsed_raw_text() {
        Optional<NormalizedValue> value = Normalizers.normalize(null, "  ACME   WIDGETS LLC ");

        assertThat(value).isPresent();
        assertThat(value.orElseThrow().text()).isEqualTo("ACME WIDGETS LLC");
        assertThat(value.orElseThrow().certainty()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void an_unknown_normalizer_name_is_a_schema_bug_not_a_silent_miss() {
        assertThatThrownBy(() -> Normalizers.normalize("sanitizeHarder", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
