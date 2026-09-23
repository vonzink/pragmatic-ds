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
import com.pragmaticds.docengine.extraction.schema.TableSpec;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The engine contract: one outcome per schema field in schema order; pages in document order,
 * first page whose ladder succeeds wins; extractors in spec order, first success wins; a value
 * that fails every rung on every page is the MISSING outcome, not an absence.
 */
class DefaultFieldExtractionEngineTest {

    private static final String MONEY = "\\$?\\d{1,3}(?:,\\d{3})*\\.\\d{2}";
    private static final String DATE = "\\d{2}/\\d{2}/\\d{4}";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return span(id, text, x, y, w, h, "1");
    }

    private static SpanRef span(
            long id, String text, String x, String y, String w, String h, String confidence) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                new BigDecimal(confidence));
    }

    private static PageContent page(SpanRef... spans) {
        return new PageContent(UUID.randomUUID(), 0, List.of(spans), List.of());
    }

    private static FieldSpec field(String name, DataType type, String normalizer, ExtractorSpec... rungs) {
        return new FieldSpec(name, type, true, normalizer, false, List.of(rungs));
    }

    private static ExtractorSpec anchor(
            double strength, String label, String pattern, int occurrence, ValueScope scope) {
        return new ExtractorSpec(
                ExtractionMethod.ANCHOR_LABEL,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, occurrence, scope));
    }

    private static ExtractorSpec regex(double strength, String pattern, int occurrence) {
        return new ExtractorSpec(
                ExtractionMethod.REGEX,
                strength,
                null,
                null,
                new ValueSpec(pattern, occurrence, ValueScope.PAGE));
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("PAYSTUB", "1.0.0", List.of(fields));
    }

    private static FieldOutcome only(List<FieldOutcome> outcomes) {
        assertThat(outcomes).hasSize(1);
        return outcomes.get(0);
    }

    @Test
    void the_engine_version_is_the_extractor_version_persistence_stores() {
        assertThat(DefaultFieldExtractionEngine.VERSION).isEqualTo("engine/1.0.0");
    }

    // ── Phase 5 review: the shipped seed's capture patterns ──────────────────
    // These run the REAL patterns from V7__extraction.sql (read, not transcribed)
    // over hostile inputs. Each one previously produced a WRONG value at full
    // confidence, which is worse than producing nothing.

    private static String seedPattern(String fieldName, int rung) {
        return com.pragmaticds.docengine.extraction.schema.SeedPatterns.valuePattern(fieldName, rung);
    }

    @Test
    void an_unsupported_pay_frequency_is_missing_rather_than_a_contained_one() {
        // "Bi-Monthly" contains "Monthly". The old pattern sliced the inner word out and the
        // normalizer — which correctly rejects bi-monthly — never saw it. Twice-a-month payroll
        // reported as monthly is a 2x error in every downstream income calculation.
        PageContent page =
                page(
                        span(1, "Pay", "72.0", "636.0", "19.0", "10.2"),
                        span(2, "Frequency:", "97.0", "636.0", "50.0", "10.2"),
                        span(3, "Bi-Monthly", "152.0", "636.0", "52.0", "10.2"));
        FieldSpec frequency =
                field(
                        "payFrequency",
                        DataType.ENUM,
                        "payFrequency",
                        anchor(0.9, "Pay Frequency", seedPattern("payFrequency", 0), 0,
                                ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(frequency), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void a_supported_pay_frequency_still_extracts() {
        PageContent page =
                page(
                        span(1, "Pay", "72.0", "636.0", "19.0", "10.2"),
                        span(2, "Frequency:", "97.0", "636.0", "50.0", "10.2"),
                        span(3, "Bi-Weekly", "152.0", "636.0", "48.0", "10.2"));
        FieldSpec frequency =
                field(
                        "payFrequency",
                        DataType.ENUM,
                        "payFrequency",
                        anchor(0.9, "Pay Frequency", seedPattern("payFrequency", 0), 0,
                                ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(frequency), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.normalized().text()).isEqualTo("BIWEEKLY");
    }

    @Test
    void an_over_precise_number_is_not_sliced_into_a_plausible_money_amount() {
        // "1234.5678" is not a money amount. The old pattern matched "234.56" inside it and
        // reported it at certainty 1.0 with an evidence box over the right span — a wrong
        // number that looks perfectly sourced. This is the worst failure this system can have.
        PageContent page =
                page(
                        span(1, "Gross", "72.0", "244.1", "31.8", "10.2"),
                        span(2, "Pay", "109.8", "244.1", "19.6", "10.2"),
                        span(3, "1234.5678", "135.4", "244.1", "48.0", "10.2"));
        FieldSpec gross =
                field(
                        "currentGrossPay",
                        DataType.MONEY,
                        "money",
                        anchor(0.8, "Gross Pay", seedPattern("currentGrossPay", 1), 0,
                                ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(gross), List.of(page)));

        assertThat(outcome.found()).isFalse();
    }

    @Test
    void an_amount_written_without_thousands_separators_still_extracts() {
        // The other half of the same fix: a real paystub may print 4670.69 ungrouped, and the
        // pattern must not demand commas — that would turn a present value into MISSING.
        PageContent page =
                page(
                        span(1, "Gross", "72.0", "244.1", "31.8", "10.2"),
                        span(2, "Pay", "109.8", "244.1", "19.6", "10.2"),
                        span(3, "4670.69", "135.4", "244.1", "42.8", "10.2"));
        FieldSpec gross =
                field(
                        "currentGrossPay",
                        DataType.MONEY,
                        "money",
                        anchor(0.8, "Gross Pay", seedPattern("currentGrossPay", 1), 0,
                                ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(gross), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.normalized().number()).isEqualByComparingTo(new BigDecimal("4670.69"));
    }

    @Test
    void the_employer_regex_does_not_swallow_the_employee_name_before_it() {
        // Page-wide unlabelled match: the old pattern started at "Jordan" and returned
        // "Jordan Q. Fixture ACME WIDGETS LLC" as the employer.
        PageContent page =
                page(
                        span(1, "Employee:", "72.0", "94.1", "52.0", "10.2"),
                        span(2, "Jordan", "130.0", "94.1", "33.6", "10.2"),
                        span(3, "Q.", "169.6", "94.1", "11.6", "10.2"),
                        span(4, "Fixture", "187.2", "94.1", "33.6", "10.2"),
                        span(5, "ACME", "72.0", "61.9", "41.2", "13.0"),
                        span(6, "WIDGETS", "119.2", "61.9", "65.3", "13.0"),
                        span(7, "LLC", "190.6", "61.9", "27.2", "13.0"));
        FieldSpec employer =
                field(
                        "employerName",
                        DataType.STRING,
                        null,
                        regex(0.6, seedPattern("employerName", 1), 0));

        FieldOutcome outcome = only(engine.extract(schema(employer), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("ACME WIDGETS LLC");
    }

    @Test
    void the_borrower_name_stops_at_the_name_instead_of_running_down_the_line() {
        PageContent page =
                page(
                        span(1, "Employee:", "72.0", "94.1", "52.0", "10.2"),
                        span(2, "Jordan", "130.0", "94.1", "33.6", "10.2"),
                        span(3, "Q.", "169.6", "94.1", "11.6", "10.2"),
                        span(4, "Fixture", "187.2", "94.1", "33.6", "10.2"),
                        span(5, "Employee", "240.0", "94.1", "45.0", "10.2"),
                        span(6, "ID", "290.0", "94.1", "12.0", "10.2"),
                        span(7, "4821", "308.0", "94.1", "22.0", "10.2"));
        FieldSpec borrower =
                field(
                        "borrowerName",
                        DataType.STRING,
                        "personName",
                        anchor(0.9, "Employee:", seedPattern("borrowerName", 0), 0,
                                ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(borrower), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("Jordan Q. Fixture");
    }

    // ── Phase 5 review: a rung must never succeed without evidence ───────────
    // Acceptance criterion 2 — every non-null field has at least one VALUE
    // evidence row with a valid box — is only true if a value that maps to NO
    // span fails its rung. A zero-width regex match maps to no span (the
    // overlap test is strict), so it must not be reported as found.

    @Test
    void a_value_pattern_that_can_match_empty_fails_the_rung_instead_of_finding_nothing() {
        PageContent page = page(span(1, "Fixture", "72.0", "94.1", "33.6", "10.2"));
        // A schema author's optional-quantifier pattern: legal, and matches empty at offset 0.
        FieldSpec optional = field("middleName", DataType.STRING, null, regex(0.9, "[0-9]*", 0));

        FieldOutcome outcome = only(engine.extract(schema(optional), List.of(page)));

        assertThat(outcome.found())
                .as("an empty match traces to no box — it cannot be a found value")
                .isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_found_field_always_carries_at_least_one_value_evidence_box() {
        PageContent page =
                page(
                        span(1, "Net", "72.0", "343.4", "19.3", "11.1"),
                        span(2, "Pay", "97.3", "343.4", "21.3", "11.1"),
                        span(3, "$3,565.87", "124.7", "343.4", "53.4", "11.1"));
        // The joining space between spans belongs to neither — a pattern matching
        // only whitespace overlaps no span at all.
        FieldSpec spacey = field("netPay", DataType.STRING, null, regex(0.9, " ", 0));

        FieldOutcome outcome = only(engine.extract(schema(spacey), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_label_match_that_overlaps_no_span_fails_the_rung_rather_than_throwing() {
        PageContent page =
                page(
                        span(1, "Pay", "72.0", "112.1", "19.0", "10.2"),
                        span(2, "Date:", "97.0", "112.1", "26.3", "10.2"),
                        span(3, "01/17/2026", "129.2", "112.1", "55.0", "10.2"));
        // A regex label anchored on the joiner alone: it matches the page text but
        // maps to zero spans. Before the fix this threw IndexOutOfBoundsException
        // out of the engine and failed the whole EXTRACTING stage.
        ExtractorSpec joinerLabel =
                new ExtractorSpec(
                        ExtractionMethod.ANCHOR_LABEL,
                        0.9,
                        new LabelSpec(AnchorKind.REGEX, " "),
                        null,
                        new ValueSpec(DATE, 0, ValueScope.LINE_RIGHT));
        FieldSpec payDate = field("payDate", DataType.DATE, "date", joinerLabel);

        FieldOutcome outcome = only(engine.extract(schema(payDate), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void a_rung_that_cannot_produce_evidence_lets_a_later_rung_win() {
        PageContent page =
                page(
                        span(1, "Gross", "72.0", "244.1", "31.8", "10.2"),
                        span(2, "Pay", "109.8", "244.1", "19.6", "10.2"),
                        span(3, "4,170.69", "135.4", "244.1", "42.8", "10.2"));
        FieldSpec gross =
                field(
                        "currentGrossPay",
                        DataType.MONEY,
                        "money",
                        regex(1.0, "[0-9]*", 0), // matches empty first — must NOT win
                        anchor(0.8, "Gross Pay", MONEY, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(gross), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcome.displayedText()).isEqualTo("4,170.69");
        assertThat(outcome.valueEvidence()).isNotEmpty();
    }

    @Test
    void anchor_label_line_right_captures_the_value_with_full_evidence() {
        PageContent page =
                page(
                        span(1, "Employee:", "72.0", "94.1", "52.0", "10.2"),
                        span(2, "Jordan", "130.0", "94.1", "33.6", "10.2"),
                        span(3, "Q.", "169.6", "94.1", "11.6", "10.2"),
                        span(4, "Fixture", "187.2", "94.1", "33.6", "10.2"));
        FieldSpec borrower =
                field(
                        "borrowerName",
                        DataType.STRING,
                        "personName",
                        anchor(
                                0.9,
                                "Employee:",
                                "[A-Z][A-Za-z.'-]*(?: [A-Z][A-Za-z.'-]*)*",
                                0,
                                ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(borrower), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcome.anchorStrength()).isEqualTo(0.9);
        assertThat(outcome.pageId()).isEqualTo(page.pageId());
        assertThat(outcome.displayedText()).isEqualTo("Jordan Q. Fixture");
        assertThat(outcome.rawValue()).isEqualTo("Jordan Q. Fixture");
        assertThat(outcome.normalized().text()).isEqualTo("Jordan Q. Fixture");
        assertThat(outcome.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(2L, 3L, 4L);
        assertThat(outcome.valueEvidence())
                .allSatisfy(evidence -> assertThat(evidence.layoutElementId()).isNull());
        assertThat(outcome.valueEvidence().get(0).box().x()).isEqualByComparingTo("130.0");
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(1L);
        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("1");
        assertThat(outcome.confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(outcome.confidence().normalizerCertainty()).isEqualByComparingTo("1");
        assertThat(outcome.confidence().overall()).isEqualTo(new BigDecimal("0.9000"));
    }

    @Test
    void occurrence_selects_between_duplicate_value_strings_on_the_same_line() {
        // The fixture reality: "612.44 612.44" — current and YTD, same text, different boxes.
        PageContent page =
                page(
                        span(1, "Federal", "72.0", "314.1", "37.3", "10.2"),
                        span(2, "Withholding", "115.3", "314.1", "57.5", "10.2"),
                        span(3, "612.44", "178.8", "314.1", "33.6", "10.2"),
                        span(4, "612.44", "218.4", "314.1", "33.6", "10.2"));
        FieldSpec current =
                field(
                        "federalWithholding",
                        DataType.MONEY,
                        "money",
                        anchor(0.9, "Federal Withholding", MONEY, 0, ValueScope.LINE_RIGHT));
        FieldSpec ytd =
                field(
                        "ytdFederalWithholding",
                        DataType.MONEY,
                        "money",
                        anchor(0.9, "Federal Withholding", MONEY, 1, ValueScope.LINE_RIGHT));

        List<FieldOutcome> outcomes = engine.extract(schema(current, ytd), List.of(page));

        FieldOutcome first = outcomes.get(0);
        FieldOutcome second = outcomes.get(1);
        assertThat(first.displayedText()).isEqualTo("612.44");
        assertThat(second.displayedText()).isEqualTo("612.44");
        assertThat(first.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(3L);
        assertThat(second.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(4L);
        assertThat(first.valueEvidence().get(0).box().x()).isEqualByComparingTo("178.8");
        assertThat(second.valueEvidence().get(0).box().x()).isEqualByComparingTo("218.4");
    }

    @Test
    void a_label_split_across_two_spans_is_located_with_both_as_label_evidence() {
        PageContent page =
                page(
                        span(1, "Pay", "72.0", "112.1", "19.0", "10.2"),
                        span(2, "Period:", "97.0", "112.1", "34.8", "10.2"),
                        span(3, "01/01/2026", "137.8", "112.1", "55.0", "10.2"));
        FieldSpec start =
                field(
                        "payPeriodStart",
                        DataType.DATE,
                        "date",
                        anchor(0.9, "Pay Period", DATE, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(start), List.of(page)));

        assertThat(outcome.displayedText()).isEqualTo("01/01/2026");
        assertThat(outcome.normalized().date()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(1L, 2L);
    }

    @Test
    void line_right_excludes_the_label_and_anything_left_of_it() {
        PageContent page =
                page(
                        span(1, "$99.99", "10.0", "343.4", "30.0", "11.1"),
                        span(2, "Net", "72.0", "343.4", "19.3", "11.1"),
                        span(3, "Pay", "97.3", "343.4", "21.3", "11.1"),
                        span(4, "$3,565.87", "124.7", "343.4", "53.4", "11.1"));
        FieldSpec netPay =
                field(
                        "netPay",
                        DataType.MONEY,
                        "money",
                        anchor(0.9, "Net Pay", MONEY, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(netPay), List.of(page)));

        assertThat(outcome.displayedText()).isEqualTo("$3,565.87");
        assertThat(outcome.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(4L);
    }

    @Test
    void line_scope_takes_the_whole_visual_line_including_left_of_the_label() {
        PageContent page =
                page(
                        span(1, "$99.99", "10.0", "343.4", "30.0", "11.1"),
                        span(2, "Net", "72.0", "343.4", "19.3", "11.1"),
                        span(3, "Pay", "97.3", "343.4", "21.3", "11.1"),
                        span(4, "$3,565.87", "124.7", "343.4", "53.4", "11.1"));
        FieldSpec netPay =
                field(
                        "netPay",
                        DataType.MONEY,
                        "money",
                        anchor(0.9, "Net Pay", MONEY, 0, ValueScope.LINE));

        FieldOutcome outcome = only(engine.extract(schema(netPay), List.of(page)));

        assertThat(outcome.displayedText()).isEqualTo("$99.99");
        assertThat(outcome.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(1L);
    }

    @Test
    void a_value_ending_at_a_span_boundary_does_not_drag_in_the_neighbour_span() {
        PageContent page =
                page(
                        span(1, "Amount:", "72.0", "100.0", "40.0", "10.2"),
                        span(2, "612.44", "120.0", "100.0", "33.6", "10.2"),
                        span(3, "USD", "160.0", "100.0", "20.0", "10.2"));
        FieldSpec amount =
                field(
                        "amount",
                        DataType.MONEY,
                        "money",
                        anchor(0.9, "Amount:", MONEY, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(amount), List.of(page)));

        assertThat(outcome.displayedText()).isEqualTo("612.44");
        assertThat(outcome.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(2L);
    }

    @Test
    void the_first_page_in_document_order_wins_the_field() {
        PageContent first =
                page(
                        span(1, "Pay", "72.0", "130.1", "19.0", "10.2"),
                        span(2, "Date:", "97.0", "130.1", "26.3", "10.2"),
                        span(3, "01/17/2026", "129.2", "130.1", "55.0", "10.2"));
        PageContent second =
                page(
                        span(4, "Pay", "72.0", "130.1", "19.0", "10.2"),
                        span(5, "Date:", "97.0", "130.1", "26.3", "10.2"),
                        span(6, "02/20/2026", "129.2", "130.1", "55.0", "10.2"));
        FieldSpec payDate =
                field(
                        "payDate",
                        DataType.DATE,
                        "date",
                        anchor(0.9, "Pay Date", DATE, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(payDate), List.of(first, second)));

        assertThat(outcome.pageId()).isEqualTo(first.pageId());
        assertThat(outcome.displayedText()).isEqualTo("01/17/2026");
    }

    @Test
    void the_ladder_falls_back_in_spec_order_and_reports_the_fallback_rungs_strength() {
        // No tables on this page: the TABLE_CLUSTER rung fails, the ANCHOR_LABEL rung wins.
        PageContent page =
                page(
                        span(1, "Gross", "72.0", "262.1", "31.8", "10.2"),
                        span(2, "Pay", "108.0", "262.1", "19.0", "10.2"),
                        span(3, "4,670.69", "140.0", "262.1", "42.8", "10.2"));
        ExtractorSpec tableRung =
                new ExtractorSpec(
                        ExtractionMethod.TABLE_CLUSTER,
                        1.0,
                        null,
                        new TableSpec(
                                new LabelSpec(AnchorKind.LITERAL, "Gross"),
                                new LabelSpec(AnchorKind.LITERAL, "Current")),
                        new ValueSpec(MONEY, 0, ValueScope.LINE));
        FieldSpec gross =
                field(
                        "currentGrossPay",
                        DataType.MONEY,
                        "money",
                        tableRung,
                        anchor(0.8, "Gross Pay", MONEY, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(gross), List.of(page)));

        assertThat(outcome.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcome.anchorStrength()).isEqualTo(0.8);
        assertThat(outcome.confidence().anchorStrength()).isEqualByComparingTo("0.8");
        assertThat(outcome.displayedText()).isEqualTo("4,670.69");
    }

    @Test
    void a_field_found_nowhere_is_the_missing_outcome() {
        PageContent page = page(span(1, "nothing", "72.0", "100.0", "40.0", "10.2"));
        FieldSpec netPay =
                field(
                        "netPay",
                        DataType.MONEY,
                        "money",
                        anchor(0.9, "Net Pay", MONEY, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(netPay), List.of(page)));

        assertThat(outcome).isEqualTo(FieldOutcome.missing(netPay));
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.found()).isFalse();
        assertThat(outcome.pageId()).isNull();
        assertThat(outcome.valueEvidence()).isEmpty();
        assertThat(outcome.labelEvidence()).isEmpty();
        assertThat(outcome.confidence()).isEqualTo(ConfidenceBreakdown.ZERO);
    }

    @Test
    void a_captured_value_that_fails_normalization_fails_the_rung() {
        // The regex happily matches "13/45/2026"; the strict date normalizer refuses it —
        // and that refusal must fail the RUNG, yielding the missing outcome here.
        PageContent page =
                page(
                        span(1, "Pay", "72.0", "130.1", "19.0", "10.2"),
                        span(2, "Date:", "97.0", "130.1", "26.3", "10.2"),
                        span(3, "13/45/2026", "129.2", "130.1", "55.0", "10.2"));
        FieldSpec payDate =
                field(
                        "payDate",
                        DataType.DATE,
                        "date",
                        anchor(0.9, "Pay Date", DATE, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(payDate), List.of(page)));

        assertThat(outcome).isEqualTo(FieldOutcome.missing(payDate));
    }

    @Test
    void after_a_normalization_failure_the_ladder_moves_on_to_the_next_rung() {
        PageContent page =
                page(
                        span(1, "Pay", "72.0", "130.1", "19.0", "10.2"),
                        span(2, "Date:", "97.0", "130.1", "26.3", "10.2"),
                        span(3, "13/45/2026", "129.2", "130.1", "55.0", "10.2"),
                        span(4, "Issued:", "72.0", "160.1", "36.0", "10.2"),
                        span(5, "01/17/2026", "120.0", "160.1", "55.0", "10.2"));
        FieldSpec payDate =
                field(
                        "payDate",
                        DataType.DATE,
                        "date",
                        anchor(0.9, "Pay Date", DATE, 0, ValueScope.LINE_RIGHT),
                        anchor(0.7, "Issued:", DATE, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(payDate), List.of(page)));

        assertThat(outcome.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcome.anchorStrength()).isEqualTo(0.7);
        assertThat(outcome.displayedText()).isEqualTo("01/17/2026");
        assertThat(outcome.normalized().date()).isEqualTo(LocalDate.of(2026, 1, 17));
    }

    @Test
    void case_insensitive_label_location_survives_case_folding_length_changes() {
        // Turkish İ before the label: a lowered-copy implementation shifts every later offset
        // and mis-attributes both label and value spans. Offsets must come from the ORIGINAL.
        PageContent page =
                page(
                        span(1, "İİİ", "10.0", "112.1", "20.0", "10.2"),
                        span(2, "Pay", "72.0", "112.1", "19.0", "10.2"),
                        span(3, "Period:", "97.0", "112.1", "34.8", "10.2"),
                        span(4, "01/01/2026", "137.8", "112.1", "55.0", "10.2"));
        FieldSpec start =
                field(
                        "payPeriodStart",
                        DataType.DATE,
                        "date",
                        // lowercase in the schema, mixed case on the page: CI literal matching
                        anchor(0.9, "pay period", DATE, 0, ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(start), List.of(page)));

        assertThat(outcome.displayedText()).isEqualTo("01/01/2026");
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(2L, 3L);
        assertThat(outcome.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(4L);
    }

    @Test
    void regex_scans_the_whole_page_and_carries_no_label_evidence() {
        PageContent page =
                page(
                        span(1, "ACME", "72.0", "61.9", "41.2", "13.0"),
                        span(2, "WIDGETS", "119.2", "61.9", "65.3", "13.0"),
                        span(3, "LLC", "190.6", "61.9", "27.2", "13.0"),
                        span(4, "Employee:", "72.0", "94.1", "52.0", "10.2"));
        FieldSpec employer =
                field(
                        "employerName",
                        DataType.STRING,
                        null,
                        regex(0.6, "[A-Z][A-Za-z&.,' ]*?(?:LLC|Inc\\.?|Corp\\.?)", 0));

        FieldOutcome outcome = only(engine.extract(schema(employer), List.of(page)));

        assertThat(outcome.method()).isEqualTo(ExtractionMethod.REGEX);
        assertThat(outcome.displayedText()).isEqualTo("ACME WIDGETS LLC");
        assertThat(outcome.normalized().text()).isEqualTo("ACME WIDGETS LLC");
        assertThat(outcome.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(1L, 2L, 3L);
        assertThat(outcome.labelEvidence()).isEmpty();
    }

    @Test
    void span_confidence_is_the_minimum_of_the_value_spans() {
        PageContent page =
                page(
                        span(1, "Employee:", "72.0", "94.1", "52.0", "10.2"),
                        span(2, "Jordan", "130.0", "94.1", "33.6", "10.2", "1"),
                        span(3, "Q.", "169.6", "94.1", "11.6", "10.2", "0.6"),
                        span(4, "Fixture", "187.2", "94.1", "33.6", "10.2", "1"));
        FieldSpec borrower =
                field(
                        "borrowerName",
                        DataType.STRING,
                        "personName",
                        anchor(
                                0.9,
                                "Employee:",
                                "[A-Z][A-Za-z.'-]*(?: [A-Z][A-Za-z.'-]*)*",
                                0,
                                ValueScope.LINE_RIGHT));

        FieldOutcome outcome = only(engine.extract(schema(borrower), List.of(page)));

        // One shaky word taints the whole value; overall = 0.6 × 0.9 × 1, scale 4 HALF_UP.
        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("0.6");
        assertThat(outcome.confidence().overall()).isEqualTo(new BigDecimal("0.5400"));
    }

    @Test
    void outcomes_come_back_one_per_field_in_schema_order() {
        PageContent page =
                page(
                        span(1, "Pay", "72.0", "130.1", "19.0", "10.2"),
                        span(2, "Date:", "97.0", "130.1", "26.3", "10.2"),
                        span(3, "01/17/2026", "129.2", "130.1", "55.0", "10.2"));
        FieldSpec missing =
                field(
                        "netPay",
                        DataType.MONEY,
                        "money",
                        anchor(0.9, "Net Pay", MONEY, 0, ValueScope.LINE_RIGHT));
        FieldSpec found =
                field(
                        "payDate",
                        DataType.DATE,
                        "date",
                        anchor(0.9, "Pay Date", DATE, 0, ValueScope.LINE_RIGHT));

        List<FieldOutcome> outcomes = engine.extract(schema(missing, found), List.of(page));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).field().name()).isEqualTo("netPay");
        assertThat(outcomes.get(0).found()).isFalse();
        assertThat(outcomes.get(1).field().name()).isEqualTo("payDate");
        assertThat(outcomes.get(1).found()).isTrue();
    }
}
