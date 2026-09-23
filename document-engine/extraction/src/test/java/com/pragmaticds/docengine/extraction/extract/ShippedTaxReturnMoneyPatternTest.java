package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.extraction.schema.ShippedTaxReturnSeed;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The MONEY capture pattern that SHIPS in the newest tax_return seed, run over the strings a real
 * Form 1040 actually offers it — read out of the migration by {@link ShippedTaxReturnSeed}, never
 * transcribed, because a regression test written against a copied regex proves only that the copy
 * behaves.
 *
 * <p>This is the fast half of the proof: it pins the pattern's own contract in milliseconds, with
 * no container and no pipeline, so a change to the regex names itself immediately. {@code
 * TaxReturnExtractionIT} is the slow half and pins the same facts through the real engine on real
 * page geometry — the two must agree, and the strings below are lifted verbatim from that IT's
 * page so they cannot drift apart.
 *
 * <p>The patterns are compiled through {@link SpanText#valuePattern} — the same {@code TextFold}
 * seam the engine compiles them with — rather than {@code Pattern.compile}, so this test cannot
 * pass on a pattern the engine would compile differently.
 */
class ShippedTaxReturnMoneyPatternTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The five MONEY fields on the 1040, in schema order. */
    private static final List<String> MONEY_FIELDS =
            List.of(
                    "totalIncome",
                    "adjustedGrossIncome",
                    "taxableIncome",
                    "totalTax",
                    "refundAmount");

    /**
     * Every money rung on every money field must capture with the SAME pattern. Fifteen copies of
     * one regex is fifteen chances to fix four of them: if a sixteenth appears, or one drifts,
     * this fails before any behavioural test has to notice.
     */
    @Test
    void every_money_rung_on_every_money_field_shares_one_capture_pattern() {
        Set<String> distinct = new LinkedHashSet<>();
        int rungs = 0;
        for (String field : MONEY_FIELDS) {
            for (String pattern : valuePatternsOf(field)) {
                distinct.add(pattern);
                rungs++;
            }
        }
        assertThat(rungs).as("three rungs on each of the five money fields").isEqualTo(15);
        assertThat(distinct).as("the money capture patterns that ship").hasSize(1);
    }

    /**
     * THE defect this schema version exists for. Every string below is a LINE_RIGHT scope the
     * measured 1040 hands the engine, joined exactly as {@code SpanJoin} joins it.
     *
     * <p>The whole-dollar rows are the miss that started this: the form prints {@code 104,982.}
     * with an empty cents box, and every pre-1.2.0 rung required {@code \.\d{2}}, so three
     * required fields found their labels and then matched nothing at all.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // whole dollars with a trailing period — the shape that was missing entirely
                "9 104{,}982.                                                        | 104{,}982",
                "11a 96{,}410.                                                       | 96{,}410",
                "24 21{,}455.                                                        | 21{,}455",
                "35a 1{,}809.                                                        | 1{,}809",
                // cents-printing filers must not regress
                "35a 1{,}809.44                                                      | 1{,}809.44",
                "9 104{,}982.00                                                      | 104{,}982.00",
                "$1{,}234.56                                                         | $1{,}234.56",
                // the cross reference and the repeated line number, on one real line
                "Subtract line 14 from line 13. If zero or less{,} enter -0- 15"
                        + " 72{,}430.                                                | 72{,}430",
                // an amount with no thousands separator at all
                "24 615.                                                             | 615",
                // ── 1.4.0 (V49): the shapes preparer software prints ─────────────────
                // BARE whole dollars — comma-grouped, no cents, no trailing period — after
                // the dot leaders and the repeated line number: what three real
                // preparer-printed returns hand LINE_RIGHT on the taxable-income line
                ". . . . . . . . . . . 15 72{,}430                                    | 72{,}430",
                // the same shape on page 1's income lines, lettered line number and all
                ". . . . . 11a 124{,}310                                              | 124{,}310",
                "9 128{,}540                                                          | 128{,}540",
                // an OCR'd scan: the leaders read as one tilde run, the thousands
                // separator lost, the cents period kept
                "~~~~~~~~~~~~~ 11 124310.                                             | 124310",
                // a sub-thousand amount with printed cents still reads strictly
                "35a 47.50                                                           | 47.50",
                // the official form's own sub-hundred shapes (digits, the cents period, an
                // empty cents box), which 1.2.0 read and 1.4.0 must not lose: a `0.` total
                // tax on a refund return is the common case; a small tax or refund prints
                // as `85.` beside its REPEATED LINE NUMBER, which is what admits it
                "24 0.                                                               | 0",
                ". . . . . . 24 $0.                                                  | $0",
                "24 85.                                                              | 85",
                "35a 47.                                                             | 47",
                "16 5.                                                               | 5"
            })
    void the_pattern_captures_the_amount_the_form_printed(String scope, String expected) {
        assertThat(firstMatch(unescape(scope)))
                .as("capture from %s", unescape(scope))
                .contains(unescape(expected));
    }

    /**
     * The refusals, which are the whole reason the pattern is not simply {@code \d+}. A rung that
     * admitted a bare integer would read the 1040's own LINE NUMBER — printed immediately left of
     * the amount column — as the amount: a confident wrong value with a real evidence box, which
     * is strictly worse than the missing field it replaced.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // the repeated line number, alone in the scope — nothing else to fall back to
                "9",
                "11a",
                "35a",
                // a whole line of bare integers: line numbers and a form reference
                "9 11a 8888",
                // the form's own cross reference — digits ending a sentence with a period
                "Amount of line 34.",
                "from line 34.",
                "See line 9.",
                // the dot leaders that fill the line between caption and amount
                ". . . . . . . . . . .",
                // ── 1.4.0 (V49) ──────────────────────────────────────────────────────
                // a preparer's worksheet prints the LINE NUMBER with its own period — `24.`
                // at the amount column's left — and 1.2.0's `\d+(?=\.)` read it as $24
                "24.",
                "line 24. 24.",
                // form references on the refund line and the tax line: four bare digits
                "If Form 8888 is attached{,} check here 35a",
                "Check if any from Form(s): 1 8814 2 4972 3",
                // the cross reference a worksheet prints, commas and all
                "from Form 1040{,} 1040-SR{,} or 1040-NR{,} line 24.",
                // a bare three-figure amount with neither cents nor its period stays
                // unreadable: nothing distinguishes it from a form reference
                "850",
                // the year in the footer
                "Form 1040 (2025)",
                // a sub-hundred `N.` NOT beside a repeated line number is a worksheet's own
                // line number (`1.` at the column after `...adjusted gross income.`), never
                // an amount — the shape that keeps `24.` / `line 24. 24.` refused above
                "1.",
                "85.",
                "gross income. 9.",
                // the comma branch must not read a signed, bracketed, truncated or suffixed
                // token as a positive whole-dollar amount
                "(1{,}234)",
                "-1{,}234",
                "12{,}345.6",
                "1{,}234-A"
            })
    void the_pattern_refuses_everything_that_is_not_an_amount(String scope) {
        assertThat(firstMatch(unescape(scope))).as("capture from %s", unescape(scope)).isEmpty();
    }

    /**
     * The trailing period is the cents SEPARATOR, not part of the amount, so it is deliberately
     * left OUT of the capture — {@code Normalizers.money} refuses {@code 12,345.} outright
     * (MONEY_LENIENT must match the whole cleaned string) and the rung would fail on the value it
     * correctly found. What the captured digits normalize to is a lenient parse at certainty 0.9,
     * which is honest: the form printed no cents.
     */
    @Test
    void a_whole_dollar_capture_normalizes_leniently_and_a_cents_capture_strictly() {
        String wholeDollars = firstMatch("9 104,982.").orElseThrow();
        assertThat(wholeDollars).isEqualTo("104,982");
        NormalizedValue lenient = Normalizers.normalize("money", wholeDollars).orElseThrow();
        assertThat(lenient.number()).isEqualByComparingTo("104982");
        assertThat(lenient.certainty())
                .as("no cents were printed — 0.9 says so")
                .isEqualByComparingTo(new BigDecimal("0.9"));

        String withCents = firstMatch("35a 1,809.44").orElseThrow();
        assertThat(withCents).isEqualTo("1,809.44");
        NormalizedValue strict = Normalizers.normalize("money", withCents).orElseThrow();
        assertThat(strict.number()).isEqualByComparingTo("1809.44");
        assertThat(strict.certainty()).isEqualByComparingTo(BigDecimal.ONE);

        // The proof that leaving the period out is REQUIRED, not stylistic.
        assertThat(Normalizers.normalize("money", "104,982.")).isEmpty();

        // 1.4.0: a BARE whole-dollar capture — no period printed at all — is the same lenient
        // parse at the same 0.9: the preparer printed no cents either.
        String bare = firstMatch(". . . . . 15 72,430").orElseThrow();
        assertThat(bare).isEqualTo("72,430");
        NormalizedValue bareLenient = Normalizers.normalize("money", bare).orElseThrow();
        assertThat(bareLenient.number()).isEqualByComparingTo("72430");
        assertThat(bareLenient.certainty()).isEqualByComparingTo(new BigDecimal("0.9"));
    }

    /**
     * The identity-block name pattern accepts the ALL CAPS a preparer prints and still refuses a
     * first-name-only cell, which is what keeps {@code
     * TaxReturnExtractionIT#the_taxpayer_name_stays_missing_on_a_box_grid_rather_than_becoming_a_confident_partial}
     * true without that rung having to be weakened.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "MORGAN T FIXTURE  | MORGAN T FIXTURE",
                "MORGAN FIXTURE    | MORGAN FIXTURE",
                "Jordan Q. Fixture | Jordan Q. Fixture",
                "Jordan Q.         | ",
                "MORGAN            | ",
                "Home address (number and street). If you have a P.O. box| "
            })
    void the_name_pattern_reads_a_whole_name_and_refuses_a_partial(String cell, String expected) {
        Optional<String> captured = first(nameValuePattern(), cell);
        if (expected == null || expected.isBlank()) {
            assertThat(captured).as("no name in %s", cell).isEmpty();
        } else {
            assertThat(captured).as("name in %s", cell).contains(expected.trim());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * {@code @CsvSource} treats a comma as its own delimiter even under {@code delimiter = '|'}
     * when it appears unquoted, and quoting every cell would bury the strings under punctuation.
     * {@code {,}} stands in for a thousands separator and is restored here.
     */
    private static String unescape(String cell) {
        return cell == null ? null : cell.replace("{,}", ",").trim();
    }

    private static Optional<String> firstMatch(String scope) {
        return first(moneyPattern(), scope);
    }

    private static Optional<String> first(String authored, String scope) {
        Matcher matcher = SpanText.valuePattern(authored).matcher(scope);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    /** The one money pattern that ships — asserted single by the drift test above. */
    private static String moneyPattern() {
        return valuePatternsOf("totalIncome").get(0);
    }

    private static String nameValuePattern() {
        return valuePatternsOf("primaryTaxpayerName").get(0);
    }

    private static List<String> valuePatternsOf(String fieldName) {
        JsonNode definition;
        try {
            definition = JSON.readTree(ShippedTaxReturnSeed.DEFINITION);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the shipped tax_return seed is not parseable", e);
        }
        for (JsonNode field : definition.path("fields")) {
            if (fieldName.equals(field.path("name").asText())) {
                List<String> patterns = new ArrayList<>();
                for (JsonNode rung : field.path("extractors")) {
                    patterns.add(rung.path("value").path("pattern").asText());
                }
                return List.copyOf(patterns);
            }
        }
        throw new IllegalArgumentException(
                "no field " + fieldName + " in tax_return@" + ShippedTaxReturnSeed.VERSION);
    }

    /** The seed this whole test is read from must be the version the migration ships. */
    @Test
    void the_seed_under_test_is_the_newest_shipped_tax_return_schema() {
        assertThat(ShippedTaxReturnSeed.VERSION).isEqualTo("1.4.0");
        assertThat(Pattern.compile("\"name\"\\s*:\\s*\"(\\w+)\"")
                        .matcher(ShippedTaxReturnSeed.DEFINITION)
                        .results()
                        .count())
                .as("the ten TAX_RETURN fields")
                .isEqualTo(10);
    }
}
