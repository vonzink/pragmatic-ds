package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.GroupRegionSpec;
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
 * ROW groups: one occurrence per ROW of a BOUNDED region, keyed by the row's own ordinal.
 * Unlike a column group, whose key set is declared in the schema, a row group's occurrences are
 * DISCOVERED from the page — so the two contracts that matter are both about not inventing
 * anything:
 *
 * <ul>
 *   <li>a row whose amount is blank is missing FOR THAT ROW only, and
 *   <li>a region with fewer rows than {@code maxRows} does not fabricate empties — {@code
 *       maxRows} is a CAP, never a count.
 * </ul>
 *
 * <p>The key is the ordinal ZERO-PADDED TO TWO DIGITS (plan CONTRACTS): {@code group_key} is
 * text, so every ordering that touches it sorts lexically, and unpadded {@code "10"} would
 * precede {@code "2"} — a ten-entity Schedule E would present its rows scrambled.
 *
 * <p>The geometry is Schedule E Part II: a start anchor, a column header row naming
 * {@code (h) Nonpassive loss} and {@code (j) Nonpassive income} side by side, three entity
 * rows of which the third leaves the income column blank, an end anchor whose own totals line
 * carries an amount IN the income column, and a further line below the region carrying another.
 * The last two are the region's decoys: reading either as a fourth entity would report a
 * column total as one partnership's income.
 */
class RowGroupExtractionTest {

    /** The CONTRACT's money pattern, verbatim. */
    private static final String MONEY =
            "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    // ── span and page builders ───────────────────────────────────────────────

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

    /** {@code Income or Loss From Partnerships and S Corporations} — the region start. */
    private static List<SpanRef> startAnchor() {
        return List.of(
                span(1, "Income", "40.0", "400.0", "30.0", "7.0"),
                span(2, "or", "73.0", "400.0", "10.0", "7.0"),
                span(3, "Loss", "86.0", "400.0", "20.0", "7.0"),
                span(4, "From", "109.0", "400.0", "22.0", "7.0"),
                span(5, "Partnerships", "134.0", "400.0", "55.0", "7.0"));
    }

    /** The column header row: the loss column at x 355-419, the income column at x 453-531. */
    private static List<SpanRef> columnHeaders() {
        return List.of(
                span(10, "(a)", "40.0", "420.0", "12.0", "6.5"),
                span(11, "Name", "55.0", "420.0", "24.0", "6.5"),
                span(12, "(h)", "340.0", "420.0", "12.0", "6.5"),
                span(13, "Nonpassive", "355.0", "420.0", "45.0", "6.5"),
                span(14, "loss", "403.0", "420.0", "16.0", "6.5"),
                span(15, "(j)", "440.0", "420.0", "10.0", "6.5"),
                span(16, "Nonpassive", "453.0", "420.0", "45.0", "6.5"),
                span(17, "income", "501.0", "420.0", "30.0", "6.5"));
    }

    private static List<SpanRef> rowOne() {
        return List.of(
                span(20, "A", "40.0", "440.0", "6.0", "7.4"),
                span(21, "ACME", "55.0", "440.0", "26.0", "7.4"),
                span(22, "PARTNERS", "85.0", "440.0", "48.0", "7.4"),
                span(23, "P", "300.0", "440.0", "6.0", "7.4"),
                span(24, "1,200.00", "360.0", "440.0", "34.0", "7.4"),
                span(25, "8,400.00", "470.0", "440.0", "34.0", "7.4"));
    }

    private static List<SpanRef> rowTwo() {
        return List.of(
                span(30, "B", "40.0", "460.0", "6.0", "7.4"),
                span(31, "BOREAL", "55.0", "460.0", "34.0", "7.4"),
                span(32, "HOLDINGS", "93.0", "460.0", "48.0", "7.4"),
                span(33, "S", "300.0", "460.0", "6.0", "7.4"),
                span(34, "2,100.00", "360.0", "460.0", "34.0", "7.4"),
                span(35, "5,250.00", "470.0", "460.0", "34.0", "7.4"));
    }

    /** The third entity: a loss only. The income column is BLANK on this row. */
    private static List<SpanRef> rowThreeIncomeBlank() {
        return List.of(
                span(40, "C", "40.0", "480.0", "6.0", "7.4"),
                span(41, "CEDAR", "55.0", "480.0", "30.0", "7.4"),
                span(42, "TRUSTS", "89.0", "480.0", "34.0", "7.4"),
                span(43, "P", "300.0", "480.0", "6.0", "7.4"),
                span(44, "3,300.00", "360.0", "480.0", "34.0", "7.4"));
    }

    /** The region end, whose own line carries the COLUMN TOTAL inside the income column. */
    private static List<SpanRef> endAnchor() {
        return List.of(
                span(50, "Total", "40.0", "505.0", "22.0", "7.0"),
                span(51, "partnership", "65.0", "505.0", "50.0", "7.0"),
                span(52, "and", "118.0", "505.0", "16.0", "7.0"),
                span(53, "S", "137.0", "505.0", "6.0", "7.0"),
                span(54, "corporation", "146.0", "505.0", "50.0", "7.0"),
                span(55, "13,650.00", "470.0", "505.0", "38.0", "7.0"));
    }

    /** A line BELOW the region that also carries an amount in the income column. */
    private static List<SpanRef> belowTheRegion() {
        return List.of(
                span(60, "Schedule", "40.0", "525.0", "40.0", "7.4"),
                span(61, "E", "83.0", "525.0", "8.0", "7.4"),
                span(62, "(Form", "95.0", "525.0", "26.0", "7.4"),
                span(63, "1040)", "124.0", "525.0", "26.0", "7.4"),
                span(64, "9,999.00", "470.0", "525.0", "34.0", "7.4"));
    }

    @SafeVarargs
    private static PageContent page(List<SpanRef>... lines) {
        return page(0, lines);
    }

    /** A page at its own index in the package — a multi-page document is not all page 0. */
    @SafeVarargs
    private static PageContent page(int packagePageIndex, List<SpanRef>... lines) {
        List<SpanRef> spans = new ArrayList<>();
        for (List<SpanRef> line : lines) {
            spans.addAll(line);
        }
        return new PageContent(UUID.randomUUID(), packagePageIndex, List.copyOf(spans), List.of());
    }

    /**
     * The same line printed on a LATER page: identical geometry, span ids shifted by 1000. Two
     * pages of one document may hold the same y coordinates, so the span id is what tells an
     * evidence box which page it came from.
     */
    private static List<SpanRef> reprint(List<SpanRef> line) {
        return line.stream()
                .map(
                        span ->
                                new SpanRef(
                                        span.id() + 1000,
                                        span.text(),
                                        span.box(),
                                        span.confidence()))
                .toList();
    }

    /** The whole Part II table: start, headers, three rows, end anchor, and a line past it. */
    private static PageContent partTwoPage() {
        return page(
                startAnchor(),
                columnHeaders(),
                rowOne(),
                rowTwo(),
                rowThreeIncomeBlank(),
                endAnchor(),
                belowTheRegion());
    }

    /** The whole Part II table again, as a LATER page of the same document. */
    private static PageContent partTwoPageReprinted(int index) {
        return page(
                index,
                reprint(startAnchor()),
                reprint(columnHeaders()),
                reprint(rowOne()),
                reprint(rowTwo()),
                reprint(rowThreeIncomeBlank()),
                reprint(endAnchor()),
                reprint(belowTheRegion()));
    }

    /** A page of the same document that prints neither of the region's anchors. */
    private static PageContent pageWithoutTheRegion(int index) {
        return page(index, columnHeaders(), belowTheRegion());
    }

    // ── schema builders ──────────────────────────────────────────────────────

    private static GroupSpec rows(int maxRows) {
        return GroupSpec.row(
                new GroupRegionSpec(
                        new LabelSpec(AnchorKind.LITERAL, "Income or Loss From Partnerships"),
                        new LabelSpec(
                                AnchorKind.LITERAL, "Total partnership and S corporation")),
                maxRows);
    }

    private static ExtractorSpec rowCell(double strength, String columnHeader, String pattern) {
        return new ExtractorSpec(
                ExtractionMethod.ROW_CELL,
                strength,
                null,
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE),
                null,
                null,
                null,
                null,
                0.5,
                new LabelSpec(AnchorKind.LITERAL, columnHeader));
    }

    private static FieldSpec grouped(String name, GroupSpec group, ExtractorSpec... rungs) {
        return new FieldSpec(name, DataType.MONEY, false, "money", false, List.of(rungs), group);
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("SCHEDULE_E", "1.0.0", List.of(fields));
    }

    // ── one occurrence per row, keyed by row ordinal ─────────────────────────

    @Test
    void every_row_of_the_region_is_its_own_occurrence_keyed_by_its_ordinal() {
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes)
                .extracting(FieldOutcome::groupKey)
                .as("the key is the row's own ordinal, 1-based, zero-padded to two digits")
                .containsExactly("01", "02", "03");
        assertThat(outcomes.get(0).method()).isEqualTo(ExtractionMethod.ROW_CELL);
        assertThat(outcomes.get(0).displayedText()).isEqualTo("8,400.00");
        assertThat(outcomes.get(1).displayedText()).isEqualTo("5,250.00");
        assertThat(outcomes.get(0).normalized().number())
                .isEqualByComparingTo(new BigDecimal("8400.00"));
    }

    @Test
    void the_key_is_zero_padded_so_it_sorts_lexically_in_row_order() {
        // group_key is text, so the repository's OrderBy...GroupKeyAsc, the export's
        // Comparator.naturalOrder() and the unique index all sort it LEXICALLY. Unpadded, "10"
        // would precede "2" and a ten-entity Schedule E would read 1, 10, 2, 3... Every value
        // would still be right; the reviewer's first read would be scrambled, and a consumer
        // taking "the first entity" would take the wrong one.
        List<List<SpanRef>> lines = new ArrayList<>();
        lines.add(startAnchor());
        lines.add(columnHeaders());
        for (int i = 0; i < 11; i++) {
            long base = 100 + i * 10L;
            String y = String.valueOf(440 + i * 5);
            lines.add(
                    List.of(
                            span(base, "E" + i, "40.0", y, "10.0", "4.0"),
                            span(base + 1, "1,00" + (i % 10) + ".00", "470.0", y, "34.0", "4.0")));
        }
        lines.add(
                List.of(
                        span(900, "Total", "40.0", "600.0", "22.0", "7.0"),
                        span(901, "partnership", "65.0", "600.0", "50.0", "7.0"),
                        span(902, "and", "118.0", "600.0", "16.0", "7.0"),
                        span(903, "S", "137.0", "600.0", "6.0", "7.0"),
                        span(904, "corporation", "146.0", "600.0", "50.0", "7.0")));
        @SuppressWarnings("unchecked")
        PageContent elevenRows = page(lines.toArray(new List[0]));

        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(elevenRows));

        assertThat(outcomes).hasSize(11);
        assertThat(outcomes)
                .extracting(FieldOutcome::groupKey)
                .containsExactly(
                        "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11");
        assertThat(outcomes.stream().map(FieldOutcome::groupKey).sorted().toList())
                .as("lexical order IS row order once the key is padded")
                .isEqualTo(outcomes.stream().map(FieldOutcome::groupKey).toList());
    }

    @Test
    void the_same_row_keeps_its_key_across_a_re_extract() {
        // T5's orphan sweep supersedes by (field name, group key). A key that drifted between
        // runs would delete and re-insert every row on every re-extract, churning ids and the
        // evidence that hangs off them. The key is positional geometry, so it cannot drift for
        // an unchanged page.
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));
        PageContent samePage = partTwoPage();

        List<FieldOutcome> first = engine.extract(schema(income), List.of(samePage));
        List<FieldOutcome> second = engine.extract(schema(income), List.of(samePage));

        assertThat(second).extracting(FieldOutcome::groupKey).containsExactly("01", "02", "03");
        assertThat(second.stream().map(FieldOutcome::groupKey).toList())
                .isEqualTo(first.stream().map(FieldOutcome::groupKey).toList());
        assertThat(second.stream().map(FieldOutcome::displayedText).toList())
                .isEqualTo(first.stream().map(FieldOutcome::displayedText).toList());
    }

    @Test
    void a_row_whose_amount_is_blank_is_missing_for_that_row_only() {
        // Row 3 is a real partnership with a loss and no nonpassive income. Its occurrence
        // exists and is MISSING; rows 1 and 2 keep their values. A row group is never
        // all-or-nothing, and a blank cell is never a defaulted 0.00.
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes.get(0).found()).isTrue();
        assertThat(outcomes.get(1).found()).isTrue();
        FieldOutcome thirdRow = outcomes.get(2);
        assertThat(thirdRow.groupKey()).isEqualTo("03");
        assertThat(thirdRow.found()).isFalse();
        assertThat(thirdRow.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(thirdRow.displayedText()).isNull();
        assertThat(thirdRow.normalized()).isNull();
        assertThat(thirdRow.valueEvidence()).isEmpty();
        assertThat(thirdRow.confidence()).isEqualTo(ConfidenceBreakdown.ZERO);
    }

    @Test
    void a_region_with_fewer_rows_than_maxRows_does_not_fabricate_empties() {
        // maxRows is a CAP, never a count. Twenty review-me rows for a borrower with three
        // partnerships is noise that trains reviewers to ignore the missing-field signal.
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes).hasSize(3);
    }

    @Test
    void maxRows_caps_the_occurrences_at_the_first_rows_of_the_region() {
        FieldSpec income =
                grouped("passthroughIncome", rows(2), rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("01", "02");
        assertThat(outcomes.get(1).displayedText()).isEqualTo("5,250.00");
    }

    @Test
    void maxRows_stops_a_runaway_region_dead() {
        // The design's risk table: a malformed page whose rows never stop must not emit
        // hundreds of persisted occurrences. maxRows is a HARD stop, not a hint.
        List<List<SpanRef>> lines = new ArrayList<>();
        lines.add(startAnchor());
        lines.add(columnHeaders());
        for (int i = 0; i < 500; i++) {
            long base = 1000 + i * 10L;
            String y = String.valueOf(440 + i * 5);
            lines.add(
                    List.of(
                            span(base, "E" + i, "40.0", y, "10.0", "4.0"),
                            span(base + 1, "1,111.00", "470.0", y, "34.0", "4.0")));
        }
        lines.add(
                List.of(
                        span(90000, "Total", "40.0", "9000.0", "22.0", "7.0"),
                        span(90001, "partnership", "65.0", "9000.0", "50.0", "7.0"),
                        span(90002, "and", "118.0", "9000.0", "16.0", "7.0"),
                        span(90003, "S", "137.0", "9000.0", "6.0", "7.0"),
                        span(90004, "corporation", "146.0", "9000.0", "50.0", "7.0")));
        @SuppressWarnings("unchecked")
        PageContent runaway = page(lines.toArray(new List[0]));

        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(runaway));

        assertThat(outcomes).hasSize(20);
        assertThat(outcomes.get(19).groupKey()).isEqualTo("20");
    }

    // ── the column bound ─────────────────────────────────────────────────────

    @Test
    void the_neighbouring_columns_amount_is_never_taken() {
        // The nonpassive LOSS sits on the same row, 110 pt to the left. Taking it would report
        // a loss as income — a sign error in a qualifying-income figure, confident and
        // evidence-backed.
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes.get(0).displayedText()).isEqualTo("8,400.00");
        assertThat(outcomes.get(0).valueEvidence())
                .as("row 1's evidence points at the income cell, never the loss cell")
                .extracting(EvidenceRef::spanId)
                .containsExactly(25L);
        assertThat(outcomes.get(1).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(35L);
    }

    @Test
    void the_same_page_reads_the_loss_column_when_that_is_the_header_asked_for() {
        // The other half of the same bound: the column is DATA. Asking for the loss column
        // must yield the loss amounts from the identical page.
        FieldSpec loss =
                grouped("passthroughLoss", rows(20), rowCell(0.9, "Nonpassive loss", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(loss), List.of(partTwoPage()));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("01", "02", "03");
        assertThat(outcomes.get(0).displayedText()).isEqualTo("1,200.00");
        assertThat(outcomes.get(1).displayedText()).isEqualTo("2,100.00");
        assertThat(outcomes.get(2).displayedText())
                .as("row 3 HAS a loss even though it has no income")
                .isEqualTo("3,300.00");
    }

    // ── the region bound ─────────────────────────────────────────────────────

    @Test
    void the_end_anchors_own_total_is_not_a_fourth_row() {
        // 13,650.00 sits squarely in the income column, on the end anchor's line. Reading it
        // would report the COLUMN TOTAL as one partnership's income.
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes)
                .extracting(FieldOutcome::displayedText)
                .doesNotContain("13,650.00")
                .doesNotContain("9,999.00");
    }

    @Test
    void a_region_with_no_end_anchor_fails_the_rung_rather_than_running_to_the_page_foot() {
        // An unbounded region is the runaway the design's risk table names. maxRows alone is a
        // cap, not a boundary: without an end the rung refuses.
        PageContent unbounded =
                page(
                        startAnchor(),
                        columnHeaders(),
                        rowOne(),
                        rowTwo(),
                        rowThreeIncomeBlank(),
                        belowTheRegion());
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(unbounded));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).found()).isFalse();
        assertThat(outcomes.get(0).groupKey())
                .as("no region means no rows, so there is no ordinal to name")
                .isNull();
    }

    @Test
    void a_page_without_the_regions_start_anchor_yields_one_missing_occurrence() {
        PageContent noStart =
                page(columnHeaders(), rowOne(), rowTwo(), rowThreeIncomeBlank(), endAnchor());
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(noStart));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).found()).isFalse();
        assertThat(outcomes.get(0).groupKey()).isNull();
    }

    @Test
    void a_column_header_that_is_not_in_the_region_fails_the_rung() {
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Section 179 expense deduction", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).found()).isFalse();
    }

    // ── evidence and confidence ──────────────────────────────────────────────

    @Test
    void each_occurrence_carries_its_own_value_box_and_the_column_header_as_its_label() {
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes.get(0).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(25L);
        assertThat(outcomes.get(0).valueEvidence().get(0).box().y())
                .isEqualByComparingTo("440.0");
        assertThat(outcomes.get(1).valueEvidence().get(0).box().y())
                .isEqualByComparingTo("460.0");
        assertThat(outcomes.get(0).valueEvidence())
                .allSatisfy(evidence -> assertThat(evidence.layoutElementId()).isNull());
        // The column header is the LABEL that makes the cell mean anything — the anchor
        // phrase, spans 16 and 17, never the "(j)" reference number in span 15.
        assertThat(outcomes.get(0).labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(16L, 17L);
        assertThat(outcomes.get(1).labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(16L, 17L);
        assertThat(outcomes.get(2).labelEvidence())
                .as("a missing row carries no evidence at all")
                .isEmpty();
    }

    @Test
    void a_row_occurrence_confidence_is_exactly_the_three_components() {
        // The V7 contract: three components, never a fourth. 0.8 x 0.9 x 1 = 0.7200.
        List<SpanRef> shakyRow =
                List.of(
                        span(20, "A", "40.0", "440.0", "6.0", "7.4"),
                        span(21, "ACME", "55.0", "440.0", "26.0", "7.4"),
                        span(24, "1,200.00", "360.0", "440.0", "34.0", "7.4"),
                        span(25, "8,400.00", "470.0", "440.0", "34.0", "7.4", "0.8"));
        PageContent shaky =
                page(startAnchor(), columnHeaders(), shakyRow, rowTwo(), endAnchor());
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(shaky));

        assertThat(outcomes.get(0).confidence().spanConfidence()).isEqualByComparingTo("0.8");
        assertThat(outcomes.get(0).confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(outcomes.get(0).confidence().normalizerCertainty())
                .isEqualByComparingTo("1");
        assertThat(outcomes.get(0).confidence().overall()).isEqualTo(new BigDecimal("0.7200"));
        assertThat(outcomes.get(1).confidence().spanConfidence())
                .as("one shaky row does not taint the next")
                .isEqualByComparingTo("1");
    }

    // ── multi-page documents ─────────────────────────────────────────────────

    @Test
    void a_region_that_lies_entirely_on_the_second_page_still_yields_its_rows() {
        // Schedule E is a TWO-PAGE form the split rule keeps as ONE document (plan CONTRACTS):
        // Part I's rental columns are on page 1 and Parts II-IV's entity rows on page 2, so the
        // region routinely lives on a page the engine reaches second.
        PageContent first = pageWithoutTheRegion(0);
        PageContent second = partTwoPageReprinted(1);
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(first, second));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("01", "02", "03");
        assertThat(outcomes.get(0).displayedText()).isEqualTo("8,400.00");
        assertThat(outcomes.get(0).pageId()).isEqualTo(second.pageId());
        assertThat(outcomes.get(0).valueEvidence())
                .as("the evidence box belongs to page 2's span, not page 1's")
                .extracting(EvidenceRef::spanId)
                .containsExactly(1025L);
        assertThat(outcomes.get(1).displayedText()).isEqualTo("5,250.00");
        assertThat(outcomes.get(2).found()).isFalse();
    }

    @Test
    void rows_on_two_pages_never_both_produce_an_01_the_first_page_with_rows_wins() {
        // THE multi-page invariant, and an accidental one worth pinning. rowKey() counts from 1
        // within ONE call of the rung — it is per PAGE — and extractRowGroup RETURNS on the
        // first page whose rung yields rows. Those two facts together are the only reason two
        // pages cannot each emit an occurrence keyed "01". extracted_field_one_current is
        // UNIQUE on (org_id, logical_document_id, field_name, coalesce(group_key, '')), so a
        // second "01" is not a duplicate row: it is a failed persist for the whole document.
        PageContent first = partTwoPage();
        PageContent second = partTwoPageReprinted(1);
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(first, second));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("01", "02", "03");
        assertThat(outcomes)
                .extracting(outcome -> outcome.field().name() + " " + outcome.groupKey())
                .as("the unique index's tuple: one row per field name and group key")
                .doesNotHaveDuplicates();
        assertThat(outcomes.get(0).pageId()).isEqualTo(first.pageId());
        assertThat(outcomes.get(0).valueEvidence())
                .as("every row was read from page 1 — page 2's identical table is not reached")
                .extracting(EvidenceRef::spanId)
                .containsExactly(25L);
        assertThat(outcomes.get(1).valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(35L);
    }

    @Test
    void a_page_whose_region_never_ends_is_skipped_for_the_page_that_bounds_it() {
        // Page 1 opens the region and never closes it — the runaway the design's risk table
        // names, and on its own a failed rung. A failed rung must not end the FIELD: the ladder
        // moves to page 2, where both anchors are printed.
        PageContent first =
                page(
                        0,
                        startAnchor(),
                        columnHeaders(),
                        rowOne(),
                        rowTwo(),
                        rowThreeIncomeBlank(),
                        belowTheRegion());
        PageContent second = partTwoPageReprinted(1);
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(first, second));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(FieldOutcome::groupKey).containsExactly("01", "02", "03");
        assertThat(outcomes.get(0).displayedText()).isEqualTo("8,400.00");
        assertThat(outcomes.get(0).pageId()).isEqualTo(second.pageId());
        assertThat(outcomes.get(0).valueEvidence())
                .as("the bounded page is the one that answered")
                .extracting(EvidenceRef::spanId)
                .containsExactly(1025L);
    }

    @Test
    void neither_page_carrying_the_region_yields_one_missing_occurrence_not_one_per_page() {
        // The missing side of the same invariant: no region anywhere is ONE review-me row with
        // a null key, not one per page scanned. There is no ordinal to name when there are no
        // rows, and two null-keyed occurrences would collide under coalesce(group_key, '').
        PageContent first = pageWithoutTheRegion(0);
        PageContent second = page(1, reprint(columnHeaders()), reprint(belowTheRegion()));
        FieldSpec income =
                grouped(
                        "passthroughIncome",
                        rows(20),
                        rowCell(0.9, "Nonpassive income", MONEY));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(first, second));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).found()).isFalse();
        assertThat(outcomes.get(0).groupKey()).isNull();
    }

    // ── the ungrouped regression ─────────────────────────────────────────────

    @Test
    void a_row_cell_rung_on_an_ungrouped_field_finds_nothing_rather_than_guessing() {
        // ROW_CELL without a ROW group has no region to walk. It must not degrade into a
        // page-wide grab; the field goes missing and a later rung, if any, decides.
        FieldSpec income =
                new FieldSpec(
                        "passthroughIncome",
                        DataType.MONEY,
                        false,
                        "money",
                        false,
                        List.of(rowCell(0.9, "Nonpassive income", MONEY)));

        List<FieldOutcome> outcomes = engine.extract(schema(income), List.of(partTwoPage()));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).found()).isFalse();
        assertThat(outcomes.get(0).groupKey()).isNull();
    }
}
