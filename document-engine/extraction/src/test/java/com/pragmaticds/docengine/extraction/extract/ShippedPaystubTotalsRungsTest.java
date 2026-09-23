package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ShippedPaystubSeed;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The paystub totals rungs THAT SHIP (V48: the strip's YTD block and the {@code FIT} caption),
 * run over the span geometry that would fool a looser regex — read out of the migration by
 * {@link ShippedPaystubSeed} and parsed by the real {@link ExtractionSchemaLoader}, never
 * transcribed, so the regex under test is the one production compiles.
 *
 * <p>Page text is SpanJoin-folded in row order with a single space between rows, and every
 * label rung binds its FIRST page match. Two consequences are pinned here: a lookbehind that
 * only asks what precedes the caption row can cross a row boundary into a section header, and a
 * word-bounded {@code FIT} is also the first word of {@code FIT TAXABLE WAGES}.
 */
class ShippedPaystubTotalsRungsTest {

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static final SchemaDefinition SHIPPED =
            new ExtractionSchemaLoader(null)
                    .parseAuthored(
                            "PAYSTUB", ShippedPaystubSeed.VERSION, ShippedPaystubSeed.DEFINITION);

    private static SchemaDefinition only(String fieldName) {
        FieldSpec field =
                SHIPPED.fields().stream()
                        .filter(spec -> spec.name().equals(fieldName))
                        .findFirst()
                        .orElseThrow();
        return new SchemaDefinition("PAYSTUB", SHIPPED.version(), List.of(field));
    }

    private static SpanRef span(long id, String text, String x, String y, String w) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal("8.0")),
                BigDecimal.ONE);
    }

    private static PageContent page(SpanRef... spans) {
        return new PageContent(UUID.randomUUID(), 0, List.of(spans), List.of(), List.of());
    }

    private FieldOutcome extract(String fieldName, PageContent page) {
        return engine.extract(only(fieldName), List.of(page)).get(0);
    }

    /** The bureau's totals strip: two blocks of {@code Earnings Deductions Net Pay}, six amounts. */
    private static PageContent strip() {
        return page(
                span(1, "Earnings", "40.0", "100.0", "31.0"),
                span(2, "Deductions", "120.0", "100.0", "39.0"),
                span(3, "Net", "210.0", "100.0", "12.0"),
                span(4, "Pay", "224.0", "100.0", "13.0"),
                span(5, "Earnings", "330.0", "100.0", "31.0"),
                span(6, "Deductions", "410.0", "100.0", "39.0"),
                span(7, "Net", "500.0", "100.0", "12.0"),
                span(8, "Pay", "514.0", "100.0", "13.0"),
                span(9, "2,096.77", "40.0", "112.0", "31.0"),
                span(10, "760.49", "120.0", "112.0", "25.0"),
                span(11, "1,336.28", "210.0", "112.0", "31.0"),
                span(12, "62,082.43", "330.0", "112.0", "36.0"),
                span(13, "18,412.55", "410.0", "112.0", "36.0"),
                span(14, "43,669.88", "500.0", "112.0", "36.0"));
    }

    @Test
    void the_strips_second_block_yields_the_ytd_earnings_and_the_first_the_current() {
        assertThat(extract("ytdGrossPay", strip()).displayedText()).isEqualTo("62,082.43");
        assertThat(extract("currentGrossPay", strip()).displayedText()).isEqualTo("2,096.77");
    }

    @Test
    void a_section_header_directly_below_a_row_ending_net_pay_is_not_the_ytd_block() {
        // A heading row that ENDS with "Net Pay", then the detail block's own "Earnings
        // Deductions" section header on the next row, then the first detail row. Folded in row
        // order the page reads "... Net Pay Earnings Deductions Regular 3,846.17 ...": a
        // lookbehind that only asks what precedes "Earnings Deductions" binds the section header,
        // the cell's first line is the first detail row, and occurrence 0 of the money pattern
        // is a line item — confident, evidence-backed and wrong. The YTD block must carry its
        // OWN "Net Pay"; there is no YTD strip on this page, so the field stays MISSING.
        PageContent page =
                page(
                        span(1, "Current", "40.0", "80.0", "30.0"),
                        span(2, "Period", "74.0", "80.0", "26.0"),
                        span(3, "Net", "200.0", "80.0", "12.0"),
                        span(4, "Pay", "214.0", "80.0", "13.0"),
                        span(5, "Earnings", "40.0", "98.0", "31.0"),
                        span(6, "Deductions", "300.0", "98.0", "39.0"),
                        span(7, "Regular", "40.0", "112.0", "28.0"),
                        span(8, "3,846.17", "90.0", "112.0", "31.0"),
                        span(9, "FICA", "300.0", "112.0", "18.0"),
                        span(10, "147.84", "360.0", "112.0", "25.0"));

        FieldOutcome outcome = extract("ytdGrossPay", page);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void fit_taxable_wages_above_the_withholding_line_does_not_steal_the_first_match() {
        // A provider that prints its taxable-wages block ABOVE the deductions: "FIT TAXABLE
        // WAGES 2,500.00" is the first page occurrence of a word-bounded FIT, and the rung must
        // walk past it to the "FIT WH" row that actually carries the withholding.
        PageContent page =
                page(
                        span(1, "FIT", "40.0", "100.0", "14.0"),
                        span(2, "TAXABLE", "58.0", "100.0", "34.0"),
                        span(3, "WAGES", "96.0", "100.0", "28.0"),
                        span(4, "2,500.00", "200.0", "100.0", "31.0"),
                        span(5, "FIT", "40.0", "120.0", "14.0"),
                        span(6, "WH", "58.0", "120.0", "12.0"),
                        span(7, "123.45", "200.0", "120.0", "25.0"));

        FieldOutcome outcome = extract("federalWithholding", page);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("123.45");
    }

    @Test
    void a_stub_that_prints_only_fit_taxable_wages_leaves_the_withholding_missing() {
        PageContent page =
                page(
                        span(1, "FIT", "40.0", "100.0", "14.0"),
                        span(2, "TAXABLE", "58.0", "100.0", "34.0"),
                        span(3, "WAGES", "96.0", "100.0", "28.0"),
                        span(4, "2,500.00", "200.0", "100.0", "31.0"));

        FieldOutcome outcome = extract("federalWithholding", page);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void a_fit_filing_status_code_is_not_the_withholding_caption() {
        // "FIT-S 2" — the federal filing status and allowances a provider prints in its header —
        // is word-bounded FIT followed by a hyphen. Not a deduction row.
        PageContent page =
                page(
                        span(1, "FIT-S", "40.0", "100.0", "24.0"),
                        span(2, "2", "70.0", "100.0", "6.0"),
                        span(3, "1,234.00", "200.0", "100.0", "31.0"));

        FieldOutcome outcome = extract("federalWithholding", page);

        assertThat(outcome.found()).isFalse();
    }
}
