package com.pragmaticds.docengine.results.body;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyBlock;
import com.pragmaticds.docengine.results.body.DocumentBody.TableSpec;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceElement;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourcePage;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceSpan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The grid has to survive, or the body manufactures wrong values that look sourced.
 *
 * <p>Three synthetic grids, one per document type where flattening does the most damage: a paystub
 * earnings grid whose current-period and year-to-date amounts the detector merged into one cell, a
 * bank-statement transaction grid, and a Closing Disclosure fee table. <b>Every value here is
 * fabricated.</b> The GEOMETRY is modelled on a measured real paystub — column x-extents, the
 * gutters between them, and the two-span cell that produced {@code DOCENGINE-BODY-1}'s reported
 * defect — because the defect only reproduces against realistic spacing. No borrower text, name,
 * employer or amount appears in this file or anywhere else in the repository.
 *
 * <h2>The round-trip property</h2>
 *
 * <p>{@link RoundTrip} is the general check and the strongest one available: every amount a
 * document carries must appear in EXACTLY ONE cell of the rendered table, under the column its
 * header names. It is stated over a fixture's declared amounts rather than over {@code
 * extracted_field}, because the composer is a pure function and an extraction-backed assertion
 * would prove the extractor instead. The property is the same either way: no amount lost, no
 * amount duplicated, no amount sharing a cell with a different column's amount.
 */
class DocumentBodyTableFidelityTest {

    // ── fixture builders ────────────────────────────────────────────────────

    private static UUID uuid(int n) {
        return new UUID(0L, n);
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v);
    }

    /** One span at a measured x-extent. Ids ascend so link order is reproducible. */
    private record Span(String text, double x, double width) {}

    private static Span span(String text, double x, double width) {
        return new Span(text, x, width);
    }

    /**
     * A table built the way the persisted rows are built: element text is its spans joined by a
     * single space, exactly as {@code LayoutElementService} denormalises them.
     */
    private static final class TableBuilder {

        private final int ordinalBase;
        private final UUID tableId;
        private final Integer declaredCols;
        private final List<SourceElement> elements = new ArrayList<>();
        private final Map<Integer, UUID> rowIds = new LinkedHashMap<>();
        private int nextOrdinal;
        private int nextId;
        private long nextSpanId;
        private int rows;

        TableBuilder(int idBase, int ordinalBase, Integer declaredCols) {
            this.ordinalBase = ordinalBase;
            this.nextOrdinal = ordinalBase;
            this.nextId = idBase;
            this.nextSpanId = idBase * 100L;
            this.declaredCols = declaredCols;
            this.tableId = uuid(nextId++);
        }

        TableBuilder cell(int row, int col, double y, Span... spans) {
            rows = Math.max(rows, row + 1);
            UUID rowId =
                    rowIds.computeIfAbsent(
                            row,
                            index -> {
                                UUID id = uuid(nextId++);
                                elements.add(
                                        new SourceElement(
                                                id,
                                                tableId,
                                                LayoutElementType.TABLE_ROW,
                                                nextOrdinal++,
                                                new BodyBox(dec(46), dec(y), dec(200), dec(7)),
                                                "",
                                                null,
                                                null,
                                                List.of(),
                                                index,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null));
                                return id;
                            });
            List<SourceSpan> members = new ArrayList<>(spans.length);
            StringBuilder text = new StringBuilder();
            double left = 0;
            double right = 0;
            if (spans.length > 0) {
                left = Double.MAX_VALUE;
                right = -Double.MAX_VALUE;
            }
            for (Span s : spans) {
                members.add(
                        new SourceSpan(
                                nextSpanId++, dec(s.x()), dec(s.width()), s.text(), dec(0.99)));
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(s.text());
                left = Math.min(left, s.x());
                right = Math.max(right, s.x() + s.width());
            }
            elements.add(
                    new SourceElement(
                            uuid(nextId++),
                            rowId,
                            LayoutElementType.TABLE_CELL,
                            nextOrdinal++,
                            new BodyBox(dec(left), dec(y), dec(right - left), dec(7)),
                            text.toString(),
                            null,
                            null,
                            members,
                            row,
                            col,
                            null,
                            null,
                            null,
                            null));
            return this;
        }

        SourcePage page() {
            List<SourceElement> all = new ArrayList<>();
            all.add(
                    new SourceElement(
                            tableId,
                            null,
                            LayoutElementType.TABLE,
                            ordinalBase - 1,
                            new BodyBox(dec(46), dec(110), dec(200), dec(40)),
                            "",
                            null,
                            null,
                            List.of(),
                            null,
                            null,
                            rows,
                            declaredCols,
                            false,
                            null));
            all.addAll(elements);
            return new SourcePage(uuid(9001), 0, 0, all);
        }
    }

    private static DocumentBody compose(SourcePage page) {
        return DocumentBodyComposer.compose(
                new DocumentBodySource(uuid(1), "PAYSTUB", List.of(page)));
    }

    private static TableSpec tableOf(DocumentBody body) {
        for (BodyBlock block : body.blocks()) {
            if (block.type() == LayoutElementType.TABLE) {
                return block.table();
            }
        }
        throw new AssertionError("no table block composed");
    }

    // ── the three grids ─────────────────────────────────────────────────────

    /**
     * A paystub earnings grid, at the geometry that produced the reported defect: the detector
     * declared three columns and swept the current-period AND year-to-date amounts into the third
     * one, so two cells each hold two amounts from two different columns.
     *
     * <p>Rows 0 and 1 hold their second amount at DIFFERENT x-extents (188.5 vs 241.5 right edge),
     * which is the evidence that column 2 is really three columns: no single column right-aligns
     * its amounts in two places.
     */
    private static SourcePage paystubEarningsGrid() {
        return new TableBuilder(100, 11, 3)
                .cell(0, 0, 114.8, span("REG", 46.0, 13.0))
                .cell(0, 1, 114.8, span("25.00", 95.1, 19.5))
                .cell(0, 2, 114.8, span("40.00", 135.1, 19.5), span("1000.00", 160.4, 28.1))
                .cell(1, 0, 125.8, span("OT", 46.0, 13.0))
                .cell(1, 1, 125.8, span("37.50", 95.1, 19.5))
                .cell(1, 2, 125.8, span("8.00", 135.1, 19.5), span("2400.00", 211.2, 30.3))
                .cell(2, 0, 136.8, span("BON", 46.0, 13.0))
                .cell(2, 1, 136.8, span("0.00", 95.1, 19.5))
                .cell(2, 2, 136.8, span("0.00", 135.1, 19.5))
                .page();
    }

    /** A bank-statement transaction grid: labels in row 0, one span per cell, nothing merged. */
    private static SourcePage bankStatementGrid() {
        return new TableBuilder(200, 11, 4)
                .cell(0, 0, 100.0, span("Date", 46.0, 20.0))
                .cell(0, 1, 100.0, span("Description", 90.0, 55.0))
                .cell(0, 2, 100.0, span("Withdrawals", 200.0, 55.0))
                .cell(0, 3, 100.0, span("Deposits", 280.0, 40.0))
                .cell(1, 0, 111.0, span("03/04", 46.0, 22.0))
                .cell(1, 1, 111.0, span("PAYROLL DEPOSIT", 90.0, 70.0))
                .cell(1, 2, 111.0)
                .cell(1, 3, 111.0, span("3120.55", 285.0, 32.0))
                .cell(2, 0, 122.0, span("03/06", 46.0, 22.0))
                .cell(2, 1, 122.0, span("CARD PURCHASE", 90.0, 65.0))
                .cell(2, 2, 122.0, span("84.19", 210.0, 26.0))
                .cell(2, 3, 122.0)
                .page();
    }

    /**
     * A Closing Disclosure fee table with the same merge defect as the paystub: the borrower-paid
     * column has swallowed the at-closing and before-closing amounts.
     */
    private static SourcePage closingDisclosureFeeGrid() {
        return new TableBuilder(300, 11, 3)
                .cell(0, 0, 100.0, span("Loan Costs", 46.0, 50.0))
                .cell(0, 1, 100.0, span("Borrower-Paid", 200.0, 60.0))
                .cell(0, 2, 100.0, span("Paid by Others", 320.0, 62.0))
                .cell(1, 0, 111.0, span("Origination Charges", 46.0, 90.0))
                .cell(
                        1,
                        1,
                        111.0,
                        span("1200.00", 205.0, 30.0),
                        span("350.00", 262.0, 26.0))
                .cell(1, 2, 111.0, span("0.00", 330.0, 20.0))
                .cell(2, 0, 122.0, span("Appraisal Fee", 46.0, 70.0))
                .cell(2, 1, 122.0, span("650.00", 205.0, 30.0))
                .cell(2, 2, 122.0, span("0.00", 330.0, 20.0))
                .page();
    }

    // ── defect 3: one cell per column ───────────────────────────────────────

    @Nested
    @DisplayName("a cell never holds two columns' amounts")
    class Splice {

        @Test
        @DisplayName("the paystub's merged current/YTD cell splits into one cell per column")
        void paystub_current_and_ytd_stop_sharing_a_cell() {
            TableSpec spec = tableOf(compose(paystubEarningsGrid()));

            // Three declared columns became five: the third column right-aligned its amounts in
            // three distinct places, which no single column does.
            assertThat(spec.cols()).isEqualTo(5);
            assertThat(cellTexts(spec))
                    .containsExactly(
                            List.of("REG", "25.00", "40.00", "1000.00", ""),
                            List.of("OT", "37.50", "8.00", "", "2400.00"),
                            List.of("BON", "0.00", "0.00", "", ""));
        }

        @Test
        @DisplayName("the rendered Markdown carries each amount in its own cell")
        void paystub_markdown_keeps_one_amount_per_cell() {
            String markdown = DocumentBodyMarkdown.render(compose(paystubEarningsGrid()));

            assertThat(markdown).doesNotContain("40.00 1000.00").doesNotContain("8.00 2400.00");
            assertThat(markdown).contains("| REG | 25.00 | 40.00 | 1000.00 |  |");
            assertThat(markdown).contains("| OT | 37.50 | 8.00 |  | 2400.00 |");
        }

        @Test
        @DisplayName("the Closing Disclosure's merged borrower-paid cell splits too")
        void closing_disclosure_fee_amounts_split() {
            TableSpec spec = tableOf(compose(closingDisclosureFeeGrid()));

            assertThat(spec.cols()).isEqualTo(4);
            assertThat(cellTexts(spec))
                    .containsExactly(
                            List.of("Loan Costs", "Borrower-Paid", "", "Paid by Others"),
                            List.of("Origination Charges", "1200.00", "350.00", "0.00"),
                            List.of("Appraisal Fee", "650.00", "", "0.00"));
        }

        @Test
        @DisplayName("a column whose cells each hold one span is left exactly as detected")
        void coherent_columns_are_not_split() {
            TableSpec spec = tableOf(compose(bankStatementGrid()));

            assertThat(spec.cols()).isEqualTo(4);
            assertThat(spec.columnsTrustworthy()).isTrue();
        }

        @Test
        @DisplayName("a split cell keeps citing the element its text came from")
        void split_cells_keep_their_element_id() {
            TableSpec spec = tableOf(compose(paystubEarningsGrid()));

            List<DocumentBody.BodyCell> rowZero =
                    spec.cells().stream().filter(cell -> cell.row() == 0).toList();
            DocumentBody.BodyCell hours =
                    rowZero.stream().filter(cell -> cell.col() == 2).findFirst().orElseThrow();
            DocumentBody.BodyCell amount =
                    rowZero.stream().filter(cell -> cell.col() == 3).findFirst().orElseThrow();

            assertThat(amount.layoutElementId()).isEqualTo(hours.layoutElementId());
            assertThat(hours.spanIds()).hasSize(1);
            assertThat(amount.spanIds()).hasSize(1);
            assertThat(hours.spanIds()).doesNotContainAnyElementsOf(amount.spanIds());
        }
    }

    // ── defects 1 and 2: a real header row ──────────────────────────────────

    @Nested
    @DisplayName("the header row carries the column labels the detector put in the table")
    class Header {

        @Test
        @DisplayName("a label row becomes the GFM header row rather than an empty one")
        void bank_statement_labels_head_the_table() {
            String markdown = DocumentBodyMarkdown.render(compose(bankStatementGrid()));

            assertThat(markdown).doesNotContain("|  |  |  |  |\n| --- |");
            assertThat(markdown)
                    .startsWith("| Date | Description | Withdrawals | Deposits |\n"
                            + "| --- | --- | --- | --- |\n");
        }

        @Test
        @DisplayName("a row of amounts is never promoted to a header")
        void a_value_row_stays_a_body_row() {
            String markdown = DocumentBodyMarkdown.render(compose(paystubEarningsGrid()));

            // Row 0 is data. Naming a column "25.00" would be a claim nobody made, so the header
            // stays empty and the row keeps its place in the body.
            assertThat(markdown).startsWith("|  |  |  |  |  |\n| --- | --- | --- | --- | --- |\n");
            assertThat(markdown).contains("| REG | 25.00 |");
        }

        @Test
        @DisplayName("a date in row 0 counts as a value, not a label")
        void a_date_row_is_not_a_header() {
            SourcePage page =
                    new TableBuilder(400, 11, 2)
                            .cell(0, 0, 100.0, span("03/04", 46.0, 22.0))
                            .cell(0, 1, 100.0, span("120.00", 200.0, 30.0))
                            .cell(1, 0, 111.0, span("03/05", 46.0, 22.0))
                            .cell(1, 1, 111.0, span("90.00", 200.0, 30.0))
                            .page();

            assertThat(DocumentBodyMarkdown.render(compose(page))).startsWith("|  |  |\n");
        }

        @Test
        @DisplayName("the label row is not duplicated into the body")
        void a_promoted_label_row_appears_once() {
            String markdown = DocumentBodyMarkdown.render(compose(bankStatementGrid()));

            assertThat(markdown.split("\\| Date \\|", -1)).hasSize(2);
        }
    }

    // ── the round-trip property ─────────────────────────────────────────────

    @Nested
    @DisplayName("round trip: every amount lands in exactly one cell, under its own column")
    class RoundTrip {

        @Test
        @DisplayName("paystub")
        void paystub() {
            // The column each amount belongs to is unnamed on this document, so the property is
            // stated positionally: one cell, one amount, and no two amounts sharing a cell.
            assertEveryAmountHasItsOwnCell(
                    paystubEarningsGrid(),
                    List.of("25.00", "37.50", "40.00", "1000.00", "8.00", "2400.00"));
        }

        @Test
        @DisplayName("bank statement, by column name")
        void bank_statement() {
            assertAmountUnderHeader(bankStatementGrid(), "3120.55", "Deposits");
            assertAmountUnderHeader(bankStatementGrid(), "84.19", "Withdrawals");
        }

        @Test
        @DisplayName("closing disclosure")
        void closing_disclosure() {
            assertEveryAmountHasItsOwnCell(
                    closingDisclosureFeeGrid(), List.of("1200.00", "350.00", "650.00"));
        }

        private void assertEveryAmountHasItsOwnCell(SourcePage page, List<String> amounts) {
            List<List<String>> grid = renderedGrid(page);
            for (String amount : amounts) {
                long occurrences =
                        grid.stream()
                                .flatMap(List::stream)
                                .filter(cell -> cell.equals(amount))
                                .count();
                assertThat(occurrences)
                        .describedAs("amount %s must occupy exactly one cell", amount)
                        .isEqualTo(1);
            }
            for (List<String> row : grid) {
                for (String cell : row) {
                    long amountsInCell =
                            amounts.stream().filter(amount -> cell.contains(amount)).count();
                    assertThat(amountsInCell)
                            .describedAs("cell %s must not carry two amounts", cell)
                            .isLessThanOrEqualTo(1);
                }
            }
        }

        private void assertAmountUnderHeader(SourcePage page, String amount, String header) {
            List<List<String>> grid = renderedGrid(page);
            List<String> headers = grid.get(0);
            int column = headers.indexOf(header);
            assertThat(column).describedAs("header %s must be a column", header).isNotNegative();

            List<int[]> found = new ArrayList<>();
            for (int row = 0; row < grid.size(); row++) {
                for (int col = 0; col < grid.get(row).size(); col++) {
                    if (grid.get(row).get(col).equals(amount)) {
                        found.add(new int[] {row, col});
                    }
                }
            }
            assertThat(found).describedAs("amount %s must occupy exactly one cell", amount).hasSize(1);
            assertThat(found.get(0)[1]).isEqualTo(column);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The composed grid, holes filled with the empty string a hole renders as. */
    private static List<List<String>> cellTexts(TableSpec spec) {
        List<List<String>> grid = new ArrayList<>();
        for (int row = 0; row < spec.rows(); row++) {
            List<String> line = new ArrayList<>();
            for (int col = 0; col < spec.cols(); col++) {
                int r = row;
                int c = col;
                line.add(
                        spec.cells().stream()
                                .filter(cell -> cell.row() == r && cell.col() == c)
                                .map(DocumentBody.BodyCell::text)
                                .findFirst()
                                .orElse(""));
            }
            grid.add(line);
        }
        return grid;
    }

    /**
     * The RENDERED table parsed back into cells — header row first, delimiter dropped. Reading the
     * Markdown rather than the spec is the point: the property must hold on the bytes a consumer
     * actually gets.
     */
    private static List<List<String>> renderedGrid(SourcePage page) {
        String markdown = DocumentBodyMarkdown.render(compose(page));
        List<List<String>> grid = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : line.substring(1, line.length() - 1).split("\\|", -1)) {
                cells.add(cell.trim());
            }
            if (cells.stream().allMatch("---"::equals)) {
                continue;
            }
            grid.add(cells);
        }
        return grid;
    }
}
