package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bank_statement@1.1.0 (V18) dialect rungs, mirrored at the engine level with the geometry
 * the real statement and its corpus fixtures print. Two vocabularies are under test, and every
 * loosened reach carries its decoy in the same class (design D5):
 *
 * <ul>
 *   <li><b>statementPeriod:</b> real statements print NO period caption — the phrase
 *       "statement period" appears only inside footnote PROSE, where the retired naked label
 *       anchored and captured whatever dates followed (measured live: a confident 0.90 wrong
 *       value). The 1.1.0 rungs anchor on full RANGE CONSTRUCTIONS — "Statement Period:
 *       MM/DD/YYYY - MM/DD/YYYY" or "Month D, YYYY through Month D, YYYY" — and the month rung
 *       tolerates the measured word FUSION ("2026throughJune" arrives as one span).
 *   <li><b>totals:</b> the summary block says "Deposits and Additions" where the generator says
 *       "Total Deposits", the same wordings repeat as detail-section HEADERS with transaction
 *       rows beneath, and a withdrawals total prints a leading minus that is part of the value.
 * </ul>
 *
 * <p>Which summary captions may answer for a TOTAL at all is settled by the printed block's own
 * arithmetic, in {@link ChaseSummaryTotalsTest} against the shipped seed. "Electronic
 * Withdrawals" is not one of them — it is a category among several — so nothing here binds it.
 */
class StatementDialectExtractionTest {

    private static final String MONTHS =
            "(?:January|February|March|April|May|June|July|August|September|October|November"
                    + "|December)";

    /** V18's construction labels and value patterns, verbatim. */
    private static final String SLASH_RANGE_LABEL =
            "Statement Period:? ?\\d{2}/\\d{2}/\\d{4} ?- ?\\d{2}/\\d{2}/\\d{4}";

    private static final String MONTH_RANGE_LABEL =
            MONTHS + " \\d{1,2}, \\d{4}\\s?through\\s?" + MONTHS + " \\d{1,2}, \\d{4}";
    private static final String MONTH_DATE = "(?<!\\d)" + MONTHS + " \\d{1,2}, \\d{4}(?!\\d)";
    private static final String SLASH_DATE = "\\d{2}/\\d{2}/\\d{4}";
    private static final String SIGNED_MONEY =
            "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                BigDecimal.ONE);
    }

    private static PageContent page(SpanRef... spans) {
        return new PageContent(UUID.randomUUID(), 0, List.of(spans), List.of());
    }

    private static ExtractorSpec anchor(
            AnchorKind kind, String label, String pattern, int occurrence, ValueScope scope) {
        return new ExtractorSpec(
                ExtractionMethod.ANCHOR_LABEL,
                0.9,
                new LabelSpec(kind, label),
                null,
                new ValueSpec(pattern, occurrence, scope),
                null,
                null,
                null,
                null,
                null);
    }

    private static FieldSpec periodField(String name, int occurrence) {
        return new FieldSpec(
                name,
                DataType.DATE,
                true,
                "date",
                false,
                List.of(
                        anchor(AnchorKind.REGEX, SLASH_RANGE_LABEL, SLASH_DATE, occurrence,
                                ValueScope.LINE),
                        anchor(AnchorKind.REGEX, MONTH_RANGE_LABEL, MONTH_DATE, occurrence,
                                ValueScope.LINE)),
                null);
    }

    private static SchemaDefinition periodSchema() {
        return new SchemaDefinition(
                "BANK_STATEMENT",
                "1.1.0",
                List.of(periodField("statementPeriodStart", 0), periodField("statementPeriodEnd", 1)));
    }

    // ── the real masthead: month-name dates with the measured word fusion ────

    /** 'May 16, 2026throughJune 15, 2026' as pdfplumber hands it over: the year, 'through'
     * and the next month arrive as ONE span. */
    private static PageContent fusedMastheadPage() {
        return page(
                span(1, "May", "380.0", "53.4", "17.0", "9.0"),
                span(2, "16,", "399.5", "53.4", "12.5", "9.0"),
                span(3, "2026throughJune", "414.5", "53.4", "70.0", "9.0"),
                span(4, "15,", "487.0", "53.4", "12.5", "9.0"),
                span(5, "2026", "502.0", "53.4", "20.0", "9.0"));
    }

    @Test
    void the_month_range_masthead_yields_both_period_dates_through_the_fusion() {
        List<FieldOutcome> outcomes = engine.extract(periodSchema(), List.of(fusedMastheadPage()));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).displayedText()).isEqualTo("May 16, 2026");
        assertThat(outcomes.get(0).normalized().date()).hasToString("2026-05-16");
        assertThat(outcomes.get(1).displayedText()).isEqualTo("June 15, 2026");
        assertThat(outcomes.get(1).normalized().date()).hasToString("2026-06-15");
    }

    @Test
    void the_generator_dialect_slash_range_still_yields_both_dates() {
        PageContent page =
                page(
                        span(1, "Statement", "72.0", "95.3", "50.1", "10.2"),
                        span(2, "Period:", "128.1", "95.3", "34.8", "10.2"),
                        span(3, "01/01/2026", "169.0", "95.3", "55.0", "10.2"),
                        span(4, "-", "229.5", "95.3", "5.0", "10.2"),
                        span(5, "01/31/2026", "239.7", "95.3", "55.0", "10.2"));

        List<FieldOutcome> outcomes = engine.extract(periodSchema(), List.of(page));

        assertThat(outcomes.get(0).displayedText()).isEqualTo("01/01/2026");
        assertThat(outcomes.get(1).displayedText()).isEqualTo("01/31/2026");
    }

    // ── the prose trap: a phrase inside prose is not a caption ───────────────

    /** '...the monthly statement period: 04/10/2026 05/09/2026 were waived...' — dates RIGHT
     * of the phrase on one printed row, exactly the layout that handed the retired naked label
     * a confident wrong capture. */
    private static PageContent proseTrapPage() {
        return page(
                span(1, "fees", "39.6", "664.7", "18.0", "8.0"),
                span(2, "for", "61.2", "664.7", "12.0", "8.0"),
                span(3, "the", "76.8", "664.7", "13.0", "8.0"),
                span(4, "monthly", "93.4", "664.7", "30.0", "8.0"),
                span(5, "statement", "127.0", "664.7", "37.0", "8.0"),
                span(6, "period:", "167.6", "664.7", "26.0", "8.0"),
                span(7, "04/10/2026", "197.2", "664.7", "44.0", "8.0"),
                span(8, "05/09/2026", "244.8", "664.7", "44.0", "8.0"),
                span(9, "were", "292.4", "664.7", "18.0", "8.0"),
                span(10, "waived", "314.0", "664.7", "26.0", "8.0"));
    }

    @Test
    void prose_mentioning_the_statement_period_yields_nothing() {
        List<FieldOutcome> outcomes = engine.extract(periodSchema(), List.of(proseTrapPage()));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).displayedText())
                .as("a phrase inside prose is not a caption: missing over wrong")
                .isNull();
        assertThat(outcomes.get(1).displayedText()).isNull();
    }

    @Test
    void the_retired_naked_label_is_what_captured_the_prose_dates() {
        // The defect the retirement fixes, pinned so the naked label can never quietly return:
        // against the SAME prose page, a bare 'Statement Period' LINE_RIGHT rung captures the
        // prose's dates at full confidence — 04/10/2026, a wrong value with a plausible box.
        FieldSpec retired =
                new FieldSpec(
                        "statementPeriodStart",
                        DataType.DATE,
                        true,
                        "date",
                        false,
                        List.of(
                                anchor(AnchorKind.LITERAL, "Statement Period", SLASH_DATE, 0,
                                        ValueScope.LINE_RIGHT)),
                        null);
        SchemaDefinition schema = new SchemaDefinition("BANK_STATEMENT", "1.0.0", List.of(retired));

        List<FieldOutcome> outcomes = engine.extract(schema, List.of(proseTrapPage()));

        assertThat(outcomes.get(0).displayedText()).isEqualTo("04/10/2026");
    }

    // ── the summary totals: real wordings, detail-section decoys, the sign ───

    private static FieldSpec totalsField(String name, String summaryLabel) {
        return new FieldSpec(
                name,
                DataType.MONEY,
                true,
                "money",
                false,
                List.of(
                        anchor(AnchorKind.LITERAL, "Total Deposits", SIGNED_MONEY, 0,
                                ValueScope.LINE_RIGHT),
                        anchor(AnchorKind.LITERAL, summaryLabel, SIGNED_MONEY, 0,
                                ValueScope.LINE_RIGHT)),
                null);
    }

    /** The fixture-C shape: a summary block whose deposit caption repeats LOWER DOWN as a
     * detail-section header with transaction rows beneath, whose amounts nothing may capture.
     * The withdrawal row here is a genuine "Total Withdrawals" total — the category captions a
     * real statement prints instead are {@link ChaseSummaryTotalsTest}'s subject. */
    private static PageContent summaryAndDetailPage() {
        return page(
                span(1, "Deposits", "39.6", "332.3", "34.0", "9.0"),
                span(2, "and", "77.6", "332.3", "14.0", "9.0"),
                span(3, "Additions", "95.6", "332.3", "36.0", "9.0"),
                span(4, "2,412.19", "326.1", "332.3", "36.0", "9.0"),
                span(5, "Total", "39.6", "350.1", "22.0", "9.0"),
                span(6, "Withdrawals", "65.6", "350.1", "48.0", "9.0"),
                span(7, "-1,834.02", "326.1", "350.1", "40.0", "9.0"),
                // the detail sections, lower on the page:
                span(8, "DEPOSITS", "39.6", "412.8", "44.0", "9.0"),
                span(9, "AND", "87.6", "412.8", "20.0", "9.0"),
                span(10, "ADDITIONS", "111.6", "412.8", "48.0", "9.0"),
                span(11, "05/21", "39.6", "430.8", "22.0", "9.0"),
                span(12, "Zelle", "65.6", "430.8", "20.0", "9.0"),
                span(13, "Payment", "89.6", "430.8", "33.0", "9.0"),
                span(14, "1,180.00", "326.1", "430.8", "36.0", "9.0"));
    }

    @Test
    void the_summary_amount_wins_and_the_detail_row_never_answers() {
        SchemaDefinition schema =
                new SchemaDefinition(
                        "BANK_STATEMENT",
                        "1.1.0",
                        List.of(totalsField("totalDeposits", "Deposits and Additions")));

        List<FieldOutcome> outcomes = engine.extract(schema, List.of(summaryAndDetailPage()));

        assertThat(outcomes.get(0).displayedText())
                .as("first occurrence in reading order is the SUMMARY row")
                .isEqualTo("2,412.19");
    }

    /** A genuine TOTAL row — the caption "Total Withdrawals", which is what the shipped seed
     * still binds — printing the leading minus real banks put on one. */
    @Test
    void a_withdrawals_total_keeps_its_printed_minus() {
        SchemaDefinition schema =
                new SchemaDefinition(
                        "BANK_STATEMENT",
                        "1.2.0",
                        List.of(totalsField("totalWithdrawals", "Total Withdrawals")));

        List<FieldOutcome> outcomes = engine.extract(schema, List.of(summaryAndDetailPage()));

        assertThat(outcomes.get(0).displayedText()).isEqualTo("-1,834.02");
        assertThat(outcomes.get(0).normalized().number())
                .as("the sign is part of the value (the Spec 5a money-sign rule)")
                .isEqualByComparingTo("-1834.02");
    }
}
