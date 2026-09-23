package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.GroupSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * COLUMN groups: the rung runs ONCE PER DECLARED KEY, confined to that key's column band on the
 * label's own line. The bands are measured from the PRINTED header row — midpoints between
 * adjacent key centers, outer edges half a pitch beyond the outermost keys — so nothing here is a
 * fixed offset and a differently scaled page reads the same.
 *
 * <p>The geometry is a Schedule E Part I "Rents received" line: a header row printing {@code
 * Properties: A B C} at x 360 / 430 / 500, and a data row whose amounts sit at x 350 and x 420
 * under A and B, with C left blank — the two-property filing this spec's corpus gate uses.
 *
 * <p><b>The wrong-column decoy test is MANDATORY</b> (design risk table). A value belonging to
 * column B attributed to column A is confident, evidence-backed and WRONG — strictly worse than
 * missing, and indistinguishable to a reviewer without opening the PDF. It is Spec 4's box-grid
 * decoy one layer down. Never delete or loosen it.
 */
class ColumnGroupExtractionTest {

    /** The CONTRACT's money pattern, verbatim. */
    private static final String MONEY =
            "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)";

    private static final String SIGNED_MONEY =
            "(?<![\\d,.])(?:\\((?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?|\\d+\\.\\d{2})\\)"
                    + "|\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?|\\d+\\.\\d{2}))(?![\\d,.])";

    private static final long INTENDED_A_SPAN_ID = 113L;
    private static final long INTENDED_B_SPAN_ID = 114L;

    private static final BigDecimal UNSCALED = BigDecimal.ONE;

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    // ── span and page builders ───────────────────────────────────────────────

    private static SpanRef span(
            long id, String text, String x, String y, String w, String h, BigDecimal scale) {
        return span(id, text, x, y, w, h, scale, "1");
    }

    private static SpanRef span(
            long id,
            String text,
            String x,
            String y,
            String w,
            String h,
            BigDecimal scale,
            String confidence) {
        return new SpanRef(
                id,
                text,
                new Box(
                        new BigDecimal(x).multiply(scale),
                        new BigDecimal(y),
                        new BigDecimal(w).multiply(scale),
                        new BigDecimal(h)),
                new BigDecimal(confidence));
    }

    /** The header row: {@code Properties:} then the three printed column letters. */
    private static List<SpanRef> header(BigDecimal scale) {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(1, "Properties:", "270.0", "300.0", "40.0", "7.0", scale));
        spans.add(span(2, "A", "360.0", "300.0", "6.0", "7.0", scale));
        spans.add(span(3, "B", "430.0", "300.0", "6.0", "7.0", scale));
        spans.add(span(4, "C", "500.0", "300.0", "6.0", "7.0", scale));
        return spans;
    }

    /** The real-form shape: the header caption and its exact keys occupy adjacent visual lines. */
    private static List<SpanRef> adjacentHeader(BigDecimal scale) {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(1, "Properties:", "270.0", "300.0", "40.0", "7.0", scale));
        spans.add(span(2, "A", "360.0", "310.0", "6.0", "7.0", scale));
        spans.add(span(3, "B", "430.0", "310.0", "6.0", "7.0", scale));
        spans.add(span(4, "C", "500.0", "310.0", "6.0", "7.0", scale));
        return spans;
    }

    /** The row caption, at the far left of the data line and outside every column band. */
    private static List<SpanRef> caption(BigDecimal scale) {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(10, "3", "40.0", "330.0", "4.0", "7.4", scale));
        spans.add(span(11, "Rents", "56.0", "330.0", "20.0", "7.4", scale));
        spans.add(span(12, "received", "79.0", "330.0", "30.0", "7.4", scale));
        return spans;
    }

    private static PageContent page(List<SpanRef> spans) {
        return page(0, spans);
    }

    /** A page at its own index in the package — a multi-page document is not all page 0. */
    private static PageContent page(int packagePageIndex, List<SpanRef> spans) {
        return new PageContent(UUID.randomUUID(), packagePageIndex, List.copyOf(spans), List.of());
    }

    /** One span reprinted on a later page: the same box, its own id. */
    private static SpanRef shift(SpanRef span, long idOffset) {
        return new SpanRef(span.id() + idOffset, span.text(), span.box(), span.confidence());
    }

    /**
     * The two-property page: A = 16,800.00 (span 13), B = 14,400.00 (span 14), C blank. Centers
     * land at 369 / 439 against bands A [328, 398), B [398, 468), C [468, 538).
     */
    private static PageContent twoPropertyPage(BigDecimal scale) {
        List<SpanRef> spans = new ArrayList<>(header(scale));
        spans.addAll(caption(scale));
        spans.add(span(13, "16,800.00", "350.0", "330.0", "38.0", "7.4", scale));
        spans.add(span(14, "14,400.00", "420.0", "330.0", "38.0", "7.4", scale));
        return page(spans);
    }

    /**
     * The same Part I geometry as {@link #twoPropertyPage} at unit scale, but as ONE page of a
     * MULTI-page document: its own package index, its own span ids, and its own amounts — a null
     * amount leaves that column blank. Every page differs, because a value read from the wrong
     * page is only visible when the pages are told apart.
     */
    private static PageContent propertyPage(int index, long idOffset, String rentA, String rentB) {
        List<SpanRef> spans = new ArrayList<>();
        for (SpanRef span : header(UNSCALED)) {
            spans.add(shift(span, idOffset));
        }
        for (SpanRef span : caption(UNSCALED)) {
            spans.add(shift(span, idOffset));
        }
        if (rentA != null) {
            spans.add(span(idOffset + 13, rentA, "350.0", "330.0", "38.0", "7.4", UNSCALED));
        }
        if (rentB != null) {
            spans.add(span(idOffset + 14, rentB, "420.0", "330.0", "38.0", "7.4", UNSCALED));
        }
        return page(index, spans);
    }

    /** A page of the same document that prints neither the header row nor the row caption. */
    private static PageContent pageWithoutTheGroup(int index, long idOffset) {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(idOffset + 90, "Supplemental", "40.0", "100.0", "60.0", "7.4", UNSCALED));
        spans.add(span(idOffset + 91, "Income", "104.0", "100.0", "34.0", "7.4", UNSCALED));
        return page(index, spans);
    }

    /** The decoy page: column A is BLANK and only column B carries an amount. */
    private static PageContent columnBOnlyPage() {
        List<SpanRef> spans = new ArrayList<>(header(UNSCALED));
        spans.addAll(caption(UNSCALED));
        spans.add(span(14, "14,400.00", "420.0", "330.0", "38.0", "7.4", UNSCALED));
        return page(spans);
    }

    private static PageContent positiveOffsetGuardPage() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(601, "Properties:", "270.0", "100.0", "40.0", "7.0", UNSCALED));
        spans.add(span(602, "A", "360.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(603, "B", "430.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(604, "C", "500.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(
                span(
                        605,
                        "Subtract line 20 from line 3",
                        "40.0",
                        "120.0",
                        "120.0",
                        "7.0",
                        UNSCALED));
        spans.add(span(606, "Intervening", "40.0", "130.0", "42.0", "7.0", UNSCALED));
        spans.add(span(607, "4,321.09", "345.0", "140.0", "40.0", "7.0", UNSCALED));
        spans.add(span(608, "(432.10)", "415.0", "140.0", "40.0", "7.0", UNSCALED));
        return page(spans);
    }

    // ── schema builders ──────────────────────────────────────────────────────

    private static GroupSpec columns(String... keys) {
        return GroupSpec.column(new LabelSpec(AnchorKind.LITERAL, "Properties:"), List.of(keys));
    }

    private static ExtractorSpec anchorOnLine(double strength, String label, String pattern) {
        return new ExtractorSpec(
                ExtractionMethod.ANCHOR_LABEL,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE));
    }

    private static ExtractorSpec anchorOnLine(
            double strength, String label, String pattern, int lineOffset) {
        return new ExtractorSpec(
                ExtractionMethod.ANCHOR_LABEL,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE, lineOffset));
    }

    private static FieldSpec grouped(GroupSpec group, ExtractorSpec... rungs) {
        return new FieldSpec(
                "rentsReceived", DataType.MONEY, true, "money", false, List.of(rungs), group);
    }

    private static FieldSpec ungrouped(ExtractorSpec... rungs) {
        return new FieldSpec("rentsReceived", DataType.MONEY, true, "money", false, List.of(rungs));
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("SCHEDULE_E", "1.0.0", List.of(fields));
    }

    // ── one occurrence per declared key ──────────────────────────────────────

    @Test
    void adjacent_exact_key_row_reads_each_value_from_its_own_band() {
        List<SpanRef> spans = new ArrayList<>(adjacentHeader(UNSCALED));
        spans.addAll(caption(UNSCALED));
        spans.add(span(13, "16,800.00", "350.0", "330.0", "38.0", "7.4", UNSCALED));
        spans.add(span(14, "14,400.00", "420.0", "330.0", "38.0", "7.4", UNSCALED));
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(page(spans)));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(13L);
        assertThat(outcomes.get(1).displayedText()).isEqualTo("14,400.00");
        assertThat(outcomes.get(1).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(14L);
        assertThat(outcomes.get(2)).isEqualTo(FieldOutcome.missing(rents, "C"));
    }

    @Test
    void an_adjacent_key_row_matches_its_keys_through_the_fold() {
        // The fold-aware adaptation's own pin: the form prints the keys with a typographic
        // EN DASH while the schema declares the ASCII hyphen. Raw equals would miss the key
        // row entirely; the TextFold seam must apply to the fallback exactly as it does to
        // the header line.
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(1, "Properties:", "270.0", "300.0", "40.0", "7.0", UNSCALED));
        spans.add(span(2, "A–1", "352.0", "310.0", "22.0", "7.0", UNSCALED));
        spans.add(span(3, "B–1", "422.0", "310.0", "22.0", "7.0", UNSCALED));
        spans.add(span(4, "C–1", "492.0", "310.0", "22.0", "7.0", UNSCALED));
        spans.addAll(caption(UNSCALED));
        spans.add(span(13, "16,800.00", "350.0", "330.0", "38.0", "7.4", UNSCALED));
        FieldSpec rents =
                grouped(columns("A-1", "B-1", "C-1"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(page(spans)));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(13L);
    }

    @Test
    void a_key_row_separated_from_the_header_by_an_intervening_line_stays_missing() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(1, "Properties:", "270.0", "300.0", "40.0", "7.0", UNSCALED));
        spans.add(span(5, "Intervening", "270.0", "310.0", "40.0", "7.0", UNSCALED));
        spans.add(span(2, "A", "360.0", "320.0", "6.0", "7.0", UNSCALED));
        spans.add(span(3, "B", "430.0", "320.0", "6.0", "7.0", UNSCALED));
        spans.add(span(4, "C", "500.0", "320.0", "6.0", "7.0", UNSCALED));
        spans.add(span(10, "3", "40.0", "340.0", "4.0", "7.4", UNSCALED));
        spans.add(span(11, "Rents", "56.0", "340.0", "20.0", "7.4", UNSCALED));
        spans.add(span(12, "received", "79.0", "340.0", "30.0", "7.4", UNSCALED));
        spans.add(span(13, "16,800.00", "350.0", "340.0", "38.0", "7.4", UNSCALED));
        spans.add(span(14, "14,400.00", "420.0", "340.0", "38.0", "7.4", UNSCALED));
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(page(spans)));

        assertThat(outcomes).containsExactly(
                FieldOutcome.missing(rents, "A"),
                FieldOutcome.missing(rents, "B"),
                FieldOutcome.missing(rents, "C"));
    }

    @Test
    void two_complete_ordered_key_rows_on_the_page_leave_every_occurrence_missing() {
        List<SpanRef> spans = new ArrayList<>(adjacentHeader(UNSCALED));
        spans.add(span(5, "A", "360.0", "320.0", "6.0", "7.0", UNSCALED));
        spans.add(span(6, "B", "430.0", "320.0", "6.0", "7.0", UNSCALED));
        spans.add(span(7, "C", "500.0", "320.0", "6.0", "7.0", UNSCALED));
        spans.add(span(10, "3", "40.0", "340.0", "4.0", "7.4", UNSCALED));
        spans.add(span(11, "Rents", "56.0", "340.0", "20.0", "7.4", UNSCALED));
        spans.add(span(12, "received", "79.0", "340.0", "30.0", "7.4", UNSCALED));
        spans.add(span(13, "16,800.00", "350.0", "340.0", "38.0", "7.4", UNSCALED));
        spans.add(span(14, "14,400.00", "420.0", "340.0", "38.0", "7.4", UNSCALED));
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(page(spans)));

        assertThat(outcomes).containsExactly(
                FieldOutcome.missing(rents, "A"),
                FieldOutcome.missing(rents, "B"),
                FieldOutcome.missing(rents, "C"));
    }

    @Test
    void every_declared_key_gets_its_own_occurrence_in_declared_order() {
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(UNSCALED)));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes)
                .extracting(FieldOutcome::groupKey)
                .as("declared key order is the occurrence order — never the page's")
                .containsExactly("A", "B", "C");
    }

    @Test
    void each_occurrence_reads_the_amount_under_its_own_column() {
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(UNSCALED)));

        assertThat(outcomes.get(0).found()).isTrue();
        assertThat(outcomes.get(0).method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).normalized().number())
                .isEqualByComparingTo(new BigDecimal("16800.00"));

        assertThat(outcomes.get(1).found()).isTrue();
        assertThat(outcomes.get(1).displayedText()).isEqualTo("14,400.00");
        assertThat(outcomes.get(1).normalized().number())
                .isEqualByComparingTo(new BigDecimal("14400.00"));
    }

    @Test
    void every_occurrence_carries_its_own_value_evidence_and_the_shared_label() {
        // Design D4: three rents produce three VALUE boxes, each pointing at its own column.
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(UNSCALED)));

        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(13L);
        assertThat(outcomes.get(0).valueEvidence().get(0).box().x()).isEqualByComparingTo("350.0");
        assertThat(outcomes.get(1).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(14L);
        assertThat(outcomes.get(1).valueEvidence().get(0).box().x()).isEqualByComparingTo("420.0");
        // The row caption is the LABEL for every occurrence — it is the same printed phrase.
        assertThat(outcomes.get(0).labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(11L, 12L);
        assertThat(outcomes.get(1).labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(11L, 12L);
    }

    // ── THE DECOY TEST (mandatory — the design's headline risk) ──────────────

    @Test
    void an_authored_relative_line_ignores_decoys_and_preserves_the_loss_sign() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(101, "Properties:", "270.0", "100.0", "40.0", "7.0", UNSCALED));
        spans.add(span(102, "A", "360.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(103, "B", "430.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(104, "C", "500.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(105, "8,765.43", "345.0", "120.0", "40.0", "7.0", UNSCALED));
        spans.add(span(106, "(765.43)", "415.0", "120.0", "40.0", "7.0", UNSCALED));
        spans.add(span(107, "Subtract", "40.0", "130.0", "30.0", "7.0", UNSCALED));
        spans.add(span(108, "line", "74.0", "130.0", "15.0", "7.0", UNSCALED));
        spans.add(span(109, "20", "93.0", "130.0", "10.0", "7.0", UNSCALED));
        spans.add(span(110, "from", "107.0", "130.0", "18.0", "7.0", UNSCALED));
        spans.add(span(111, "line", "129.0", "130.0", "15.0", "7.0", UNSCALED));
        spans.add(span(112, "3", "148.0", "130.0", "6.0", "7.0", UNSCALED));
        spans.add(span(115, "Intervening", "40.0", "140.0", "42.0", "7.0", UNSCALED));
        spans.add(
                span(
                        INTENDED_A_SPAN_ID,
                        "1,234.56",
                        "345.0",
                        "150.0",
                        "40.0",
                        "7.0",
                        UNSCALED));
        spans.add(
                span(
                        INTENDED_B_SPAN_ID,
                        "(654.32)",
                        "415.0",
                        "150.0",
                        "40.0",
                        "7.0",
                        UNSCALED));
        spans.add(span(116, "9,999.99", "345.0", "160.0", "40.0", "7.0", UNSCALED));
        spans.add(span(117, "(999.99)", "415.0", "160.0", "40.0", "7.0", UNSCALED));
        FieldSpec incomeOrLoss =
                grouped(
                        columns("A", "B", "C"),
                        anchorOnLine(0.9, "Subtract line 20 from line 3", SIGNED_MONEY),
                        anchorOnLine(0.9, "Subtract line 20 from line 3", SIGNED_MONEY, 2));

        List<FieldOutcome> outcomes = engine.extract(schema(incomeOrLoss), List.of(page(spans)));

        assertThat(outcomes.get(0).groupKey()).isEqualTo("A");
        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(INTENDED_A_SPAN_ID);
        assertThat(outcomes.get(0).normalized().number()).isPositive();
        assertThat(outcomes.get(1).groupKey()).isEqualTo("B");
        assertThat(outcomes.get(1).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(INTENDED_B_SPAN_ID);
        assertThat(outcomes.get(1).normalized().number()).isNegative();
        assertThat(outcomes.get(2)).isEqualTo(FieldOutcome.missing(incomeOrLoss, "C"));
    }

    @Test
    void a_missing_authored_relative_line_leaves_every_occurrence_missing() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(201, "Properties:", "270.0", "100.0", "40.0", "7.0", UNSCALED));
        spans.add(span(202, "A", "360.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(203, "B", "430.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(204, "C", "500.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(
                span(
                        205,
                        "Subtract line 20 from line 3",
                        "40.0",
                        "120.0",
                        "120.0",
                        "7.0",
                        UNSCALED));
        spans.add(span(206, "Intervening", "40.0", "130.0", "42.0", "7.0", UNSCALED));
        FieldSpec incomeOrLoss =
                grouped(
                        columns("A", "B", "C"),
                        anchorOnLine(0.9, "Subtract line 20 from line 3", SIGNED_MONEY, 2));

        List<FieldOutcome> outcomes = engine.extract(schema(incomeOrLoss), List.of(page(spans)));

        assertThat(outcomes).containsExactly(
                FieldOutcome.missing(incomeOrLoss, "A"),
                FieldOutcome.missing(incomeOrLoss, "B"),
                FieldOutcome.missing(incomeOrLoss, "C"));
    }

    @Test
    void two_matches_inside_one_selected_band_leave_that_occurrence_missing() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(301, "Properties:", "270.0", "100.0", "40.0", "7.0", UNSCALED));
        spans.add(span(302, "A", "360.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(303, "B", "430.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(304, "C", "500.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(
                span(
                        305,
                        "Subtract line 20 from line 3",
                        "40.0",
                        "120.0",
                        "120.0",
                        "7.0",
                        UNSCALED));
        spans.add(span(306, "Intervening", "40.0", "130.0", "42.0", "7.0", UNSCALED));
        spans.add(span(307, "111.11", "335.0", "140.0", "28.0", "7.0", UNSCALED));
        spans.add(span(308, "222.22", "367.0", "140.0", "28.0", "7.0", UNSCALED));
        FieldSpec incomeOrLoss =
                grouped(
                        columns("A", "B", "C"),
                        anchorOnLine(0.9, "Subtract line 20 from line 3", SIGNED_MONEY, 2));

        List<FieldOutcome> outcomes = engine.extract(schema(incomeOrLoss), List.of(page(spans)));

        assertThat(outcomes.get(0)).isEqualTo(FieldOutcome.missing(incomeOrLoss, "A"));
        assertThat(outcomes.get(0).valueEvidence())
                .as("neither ambiguous candidate is cited")
                .isEmpty();
    }

    @Test
    void the_offset_zero_rung_still_wins_on_a_colinear_form() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(401, "Properties:", "270.0", "100.0", "40.0", "7.0", UNSCALED));
        spans.add(span(402, "A", "360.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(403, "B", "430.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(404, "C", "500.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(
                span(
                        405,
                        "Subtract line 20 from line 3",
                        "40.0",
                        "120.0",
                        "120.0",
                        "7.0",
                        UNSCALED));
        spans.add(span(413, "3,210.98", "345.0", "120.0", "40.0", "7.0", UNSCALED));
        spans.add(span(414, "(876.54)", "415.0", "120.0", "40.0", "7.0", UNSCALED));
        spans.add(span(406, "Intervening", "40.0", "130.0", "42.0", "7.0", UNSCALED));
        spans.add(span(415, "7,777.77", "345.0", "140.0", "40.0", "7.0", UNSCALED));
        spans.add(span(416, "(777.77)", "415.0", "140.0", "40.0", "7.0", UNSCALED));
        FieldSpec incomeOrLoss =
                grouped(
                        columns("A", "B", "C"),
                        anchorOnLine(0.9, "Subtract line 20 from line 3", SIGNED_MONEY),
                        anchorOnLine(0.9, "Subtract line 20 from line 3", SIGNED_MONEY, 2));

        List<FieldOutcome> outcomes = engine.extract(schema(incomeOrLoss), List.of(page(spans)));

        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(413L);
        assertThat(outcomes.get(1).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(414L);
        assertThat(outcomes.get(2)).isEqualTo(FieldOutcome.missing(incomeOrLoss, "C"));
    }

    @Test
    void an_empty_authored_target_line_does_not_scan_forward_to_later_decoys() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(span(501, "Properties:", "270.0", "100.0", "40.0", "7.0", UNSCALED));
        spans.add(span(502, "A", "360.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(503, "B", "430.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(span(504, "C", "500.0", "110.0", "6.0", "7.0", UNSCALED));
        spans.add(
                span(
                        505,
                        "Subtract line 20 from line 3",
                        "40.0",
                        "120.0",
                        "120.0",
                        "7.0",
                        UNSCALED));
        spans.add(span(506, "Intervening", "40.0", "130.0", "42.0", "7.0", UNSCALED));
        spans.add(span(507, "No amount authored", "40.0", "140.0", "70.0", "7.0", UNSCALED));
        spans.add(span(508, "6,789.01", "345.0", "150.0", "40.0", "7.0", UNSCALED));
        spans.add(span(509, "(678.90)", "415.0", "150.0", "40.0", "7.0", UNSCALED));
        FieldSpec incomeOrLoss =
                grouped(
                        columns("A", "B", "C"),
                        anchorOnLine(0.9, "Subtract line 20 from line 3", SIGNED_MONEY, 2));

        List<FieldOutcome> outcomes = engine.extract(schema(incomeOrLoss), List.of(page(spans)));

        assertThat(outcomes).containsExactly(
                FieldOutcome.missing(incomeOrLoss, "A"),
                FieldOutcome.missing(incomeOrLoss, "B"),
                FieldOutcome.missing(incomeOrLoss, "C"));
        assertThat(outcomes)
                .allSatisfy(outcome -> assertThat(outcome.valueEvidence()).isEmpty());
    }

    @Test
    void an_ungrouped_positive_offset_spec_fails_closed() {
        List<SpanRef> spans = new ArrayList<>();
        spans.add(
                span(
                        701,
                        "Subtract line 20 from line 3",
                        "40.0",
                        "100.0",
                        "120.0",
                        "7.0",
                        UNSCALED));
        spans.add(span(702, "Intervening", "40.0", "110.0", "42.0", "7.0", UNSCALED));
        spans.add(span(703, "5,432.10", "345.0", "120.0", "40.0", "7.0", UNSCALED));
        ExtractorSpec invalidOffset =
                new ExtractorSpec(
                        ExtractionMethod.ANCHOR_LABEL,
                        0.9,
                        new LabelSpec(AnchorKind.LITERAL, "Subtract line 20 from line 3"),
                        null,
                        new ValueSpec(SIGNED_MONEY, 0, ValueScope.LINE, 2));
        FieldSpec incomeOrLoss = ungrouped(invalidOffset);

        List<FieldOutcome> outcomes = engine.extract(schema(incomeOrLoss), List.of(page(spans)));

        assertThat(outcomes).containsExactly(FieldOutcome.missing(incomeOrLoss));
        assertThat(outcomes.get(0).valueEvidence()).isEmpty();
    }

    @Test
    void a_non_line_positive_offset_spec_fails_closed() {
        ExtractorSpec invalidOffset =
                new ExtractorSpec(
                        ExtractionMethod.ANCHOR_LABEL,
                        0.9,
                        new LabelSpec(AnchorKind.LITERAL, "Subtract line 20 from line 3"),
                        null,
                        new ValueSpec(SIGNED_MONEY, 0, ValueScope.PAGE, 2));
        FieldSpec incomeOrLoss = grouped(columns("A", "B", "C"), invalidOffset);

        List<FieldOutcome> outcomes =
                engine.extract(schema(incomeOrLoss), List.of(positiveOffsetGuardPage()));

        assertThat(outcomes).containsExactly(
                FieldOutcome.missing(incomeOrLoss, "A"),
                FieldOutcome.missing(incomeOrLoss, "B"),
                FieldOutcome.missing(incomeOrLoss, "C"));
        assertThat(outcomes)
                .allSatisfy(outcome -> assertThat(outcome.valueEvidence()).isEmpty());
    }

    @Test
    void a_nonzero_occurrence_positive_offset_spec_fails_closed() {
        ExtractorSpec invalidOffset =
                new ExtractorSpec(
                        ExtractionMethod.ANCHOR_LABEL,
                        0.9,
                        new LabelSpec(AnchorKind.LITERAL, "Subtract line 20 from line 3"),
                        null,
                        new ValueSpec(SIGNED_MONEY, 1, ValueScope.LINE, 2));
        FieldSpec incomeOrLoss = grouped(columns("A", "B", "C"), invalidOffset);

        List<FieldOutcome> outcomes =
                engine.extract(schema(incomeOrLoss), List.of(positiveOffsetGuardPage()));

        assertThat(outcomes).containsExactly(
                FieldOutcome.missing(incomeOrLoss, "A"),
                FieldOutcome.missing(incomeOrLoss, "B"),
                FieldOutcome.missing(incomeOrLoss, "C"));
        assertThat(outcomes)
                .allSatisfy(outcome -> assertThat(outcome.valueEvidence()).isEmpty());
    }

    @Test
    void the_amount_in_column_B_is_never_attributed_to_column_A() {
        // Column A is blank and column B carries 14,400.00. An unconfined rung with scope LINE
        // takes the first money match on the line and reports it as PROPERTY A's rent — at full
        // confidence, with an evidence box a reviewer would nod at. This is the wrong number, not
        // a missing one, and it is the whole reason column bands exist.
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(columnBOnlyPage()));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).groupKey()).isEqualTo("A");
        assertThat(outcomes.get(0).found())
                .as("column B's amount must NEVER become column A's value")
                .isFalse();
        assertThat(outcomes.get(0).displayedText()).isNull();
        assertThat(outcomes.get(0).valueEvidence()).isEmpty();

        assertThat(outcomes.get(1).groupKey()).isEqualTo("B");
        assertThat(outcomes.get(1).displayedText()).isEqualTo("14,400.00");
        assertThat(outcomes.get(1).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(14L);
    }

    // ── D5: an empty column is a MISSING occurrence, never a zero ────────────

    @Test
    void an_empty_column_is_a_missing_occurrence_and_never_a_defaulted_zero() {
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(UNSCALED)));

        FieldOutcome columnC = outcomes.get(2);
        assertThat(columnC).isEqualTo(FieldOutcome.missing(rents, "C"));
        assertThat(columnC.groupKey()).isEqualTo("C");
        assertThat(columnC.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(columnC.displayedText()).isNull();
        assertThat(columnC.normalized())
                .as("a zero in a rental expense silently changes a qualifying-income figure")
                .isNull();
        assertThat(columnC.valueEvidence()).isEmpty();
        assertThat(columnC.confidence()).isEqualTo(ConfidenceBreakdown.ZERO);
    }

    @Test
    void a_header_that_is_not_on_the_page_still_yields_one_occurrence_per_declared_key() {
        // "Not absent" is the point: the keys come from the SCHEMA, so even a page that never
        // prints the header owes the reviewer three review-me rows, not silence.
        List<SpanRef> spans = new ArrayList<>(caption(UNSCALED));
        spans.add(span(13, "16,800.00", "350.0", "330.0", "38.0", "7.4", UNSCALED));
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(page(spans)));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("A", "B", "C");
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.found()).isFalse());
    }

    // ── the bands come from the PRINTED headers, not from offsets ────────────

    @Test
    void a_horizontally_rescaled_page_reads_the_same_columns() {
        // The design's risk mitigation, made a test: column x-ranges derive from the printed
        // headers, so a differently scaled scan does not shift them. Only x and width are scaled
        // here, so the visual-line grouping is bit-for-bit identical and the banding is the only
        // thing under test.
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(new BigDecimal("1.5"))));

        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("A", "B", "C");
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(1).displayedText()).isEqualTo("14,400.00");
        assertThat(outcomes.get(2).found()).isFalse();
    }

    // ── an unbounded rung must not run under a column group ──────────────────

    @Test
    void a_page_wide_regex_rung_cannot_answer_a_column_group() {
        // A REGEX rung has no column to be confined to, so it would answer A, B and C with the
        // SAME first match — three properties reported as three copies of one, each at full
        // confidence with its own evidence box. The rung fails instead.
        ExtractorSpec pageWide =
                new ExtractorSpec(
                        ExtractionMethod.REGEX,
                        0.9,
                        null,
                        null,
                        new ValueSpec(MONEY, 0, ValueScope.PAGE));
        FieldSpec rents = grouped(columns("A", "B", "C"), pageWide);

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(UNSCALED)));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.found()).isFalse());
    }

    @Test
    void a_column_group_declaring_a_single_key_cannot_bound_a_column_and_stays_missing() {
        // One printed header gives no pitch to measure a band from. Falling back to an unbounded
        // scope is exactly how a confident wrong-column value is manufactured, so a one-key group
        // refuses rather than guesses. T2's loader rejects an EMPTY key list but accepts a single
        // key, so this refusal lives in the engine — it is the actual bound.
        FieldSpec rents = grouped(columns("A"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(UNSCALED)));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).groupKey()).isEqualTo("A");
        assertThat(outcomes.get(0).found()).isFalse();
    }

    // ── multi-page documents ─────────────────────────────────────────────────

    @Test
    void a_header_printed_on_the_second_page_still_answers_every_declared_key() {
        // Schedule E is a TWO-PAGE form that the split rule deliberately keeps as ONE document
        // (plan CONTRACTS), so the page the group lives on is not necessarily the first page the
        // engine is handed. Page 1 here prints neither the header nor the caption.
        PageContent first = pageWithoutTheGroup(0, 0);
        PageContent second = propertyPage(1, 1000, "16,800.00", "14,400.00");
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(first, second));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("A", "B", "C");
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).pageId())
                .as("the occurrence names the page it was actually read from")
                .isEqualTo(second.pageId());
        assertThat(outcomes.get(1).displayedText()).isEqualTo("14,400.00");
        assertThat(outcomes.get(1).pageId()).isEqualTo(second.pageId());
        assertThat(outcomes.get(2).found()).isFalse();
    }

    @Test
    void a_page_with_no_header_answers_no_key_even_when_it_prints_the_label_line() {
        // The dangerous shape: page 1 carries the "Rents received" line AND an amount, but no
        // printed header row, so no column can be BOUNDED there. A rung that fell back to an
        // unbounded scope would report page 1's 99,999.00 as property A's rent — confident,
        // evidence-backed and wrong. The page must be skipped whole and page 2 read instead.
        List<SpanRef> headerless = new ArrayList<>(caption(UNSCALED));
        headerless.add(span(80, "99,999.00", "350.0", "330.0", "38.0", "7.4", UNSCALED));
        PageContent first = page(0, headerless);
        PageContent second = propertyPage(1, 1000, "16,800.00", "14,400.00");
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(first, second));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes)
                .extracting(FieldOutcome::displayedText)
                .as("the unbounded page's amount is never anyone's value")
                .doesNotContain("99,999.00");
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).pageId()).isEqualTo(second.pageId());
        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(1013L);
    }

    @Test
    void when_two_pages_both_print_the_header_the_first_one_wins_every_key() {
        // A form whose header is reprinted, or a package the boundary split did not break apart.
        // Document order decides: the FIRST page whose ladder succeeds wins the occurrence, so
        // page 2's amounts appear nowhere — and, the part that matters at persist time, the
        // second header does not produce a SECOND occurrence for a key already answered.
        PageContent first = propertyPage(0, 0, "16,800.00", "14,400.00");
        PageContent second = propertyPage(1, 1000, "21,000.00", "19,500.00");
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(first, second));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("A", "B", "C");
        assertThat(outcomes)
                .extracting(FieldOutcome::displayedText)
                .doesNotContain("21,000.00", "19,500.00");
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).pageId()).isEqualTo(first.pageId());
        assertThat(outcomes.get(1).displayedText()).isEqualTo("14,400.00");
        assertThat(outcomes.get(1).pageId()).isEqualTo(first.pageId());
    }

    @Test
    void every_declared_key_yields_exactly_one_occurrence_across_the_whole_page_list() {
        // THE multi-page invariant. extracted_field_one_current is UNIQUE on (org_id,
        // logical_document_id, field_name, coalesce(group_key, '')), so a second occurrence
        // carrying the same key is not a duplicate row — it is a failed persist for the entire
        // document. Three pages that all print the header must still yield three occurrences.
        PageContent first = propertyPage(0, 0, "16,800.00", "14,400.00");
        PageContent second = propertyPage(1, 1000, "21,000.00", "19,500.00");
        PageContent third = propertyPage(2, 2000, "3,000.00", "4,000.00");
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(first, second, third));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("A", "B", "C");
        assertThat(outcomes)
                .extracting(outcome -> outcome.field().name() + " " + outcome.groupKey())
                .as("the unique index's tuple: one row per field name and group key")
                .doesNotHaveDuplicates();
    }

    @Test
    void neither_page_printing_the_header_still_yields_exactly_one_occurrence_per_key() {
        // The missing side of the same invariant: a key that no page can answer owes the
        // reviewer ONE review-me row, not one per page scanned.
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(
                        schema(rents),
                        List.of(pageWithoutTheGroup(0, 0), pageWithoutTheGroup(1, 1000)));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("A", "B", "C");
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.found()).isFalse());
    }

    @Test
    void a_key_the_first_page_leaves_empty_is_answered_by_a_later_page() {
        // Per OCCURRENCE, not per group: each key independently takes the first page whose
        // ladder succeeds, so a page that bounds the columns but leaves one blank does not
        // freeze that key at missing. The consequence is worth naming — two Schedule Es in one
        // package would compose one key set from BOTH forms. That is precisely what the
        // form-boundary split (plan CONTRACTS, T6) prevents upstream: the engine is handed one
        // document's pages and has no boundary of its own to enforce.
        PageContent first = propertyPage(0, 0, "16,800.00", null);
        PageContent second = propertyPage(1, 1000, "21,000.00", "19,500.00");
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(first, second));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).pageId()).isEqualTo(first.pageId());
        assertThat(outcomes.get(1).displayedText()).isEqualTo("19,500.00");
        assertThat(outcomes.get(1).pageId()).isEqualTo(second.pageId());
        assertThat(outcomes.get(2).found())
                .as("column C is blank on both pages, so it stays missing")
                .isFalse();
    }

    // ── confidence and the ungrouped regression ──────────────────────────────

    @Test
    void an_occurrence_confidence_is_exactly_the_three_components() {
        // The V7 contract: three components, never a fourth. 0.8 x 0.9 x 1 = 0.7200.
        List<SpanRef> spans = new ArrayList<>(header(UNSCALED));
        spans.addAll(caption(UNSCALED));
        spans.add(span(13, "16,800.00", "350.0", "330.0", "38.0", "7.4", UNSCALED, "0.8"));
        spans.add(span(14, "14,400.00", "420.0", "330.0", "38.0", "7.4", UNSCALED));
        FieldSpec rents =
                grouped(columns("A", "B", "C"), anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(page(spans)));

        assertThat(outcomes.get(0).confidence().spanConfidence()).isEqualByComparingTo("0.8");
        assertThat(outcomes.get(0).confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(outcomes.get(0).confidence().normalizerCertainty()).isEqualByComparingTo("1");
        assertThat(outcomes.get(0).confidence().overall()).isEqualTo(new BigDecimal("0.7200"));
        // The neighbouring column's shaky span must not taint this one.
        assertThat(outcomes.get(1).confidence().spanConfidence()).isEqualByComparingTo("1");
    }

    @Test
    void an_ungrouped_field_is_still_one_outcome_with_a_null_group_key() {
        // The plan's central regression guard, at the engine level: a field with no group block
        // behaves exactly as it did before this spec — one outcome, the first money match on the
        // label's line, and no key.
        FieldSpec rents = ungrouped(anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes =
                engine.extract(schema(rents), List.of(twoPropertyPage(UNSCALED)));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).groupKey()).isNull();
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(13L);
    }

    @Test
    void an_ungrouped_field_over_several_pages_is_still_exactly_one_outcome() {
        // The shared path's own multi-page guard, and a regression guard for every field that
        // predates this spec: pages are scanned in order and the FIRST page whose ladder
        // succeeds wins THE occurrence — one outcome, never one per page. A second occurrence
        // of a single-valued field collides under exactly the same unique index the group keys
        // ride, with coalesce(group_key, '') standing in for the null key.
        PageContent first = propertyPage(0, 0, "16,800.00", "14,400.00");
        PageContent second = propertyPage(1, 1000, "21,000.00", "19,500.00");
        FieldSpec rents = ungrouped(anchorOnLine(0.9, "Rents received", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(rents), List.of(first, second));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).groupKey()).isNull();
        assertThat(outcomes.get(0).displayedText()).isEqualTo("16,800.00");
        assertThat(outcomes.get(0).pageId()).isEqualTo(first.pageId());
    }
}
