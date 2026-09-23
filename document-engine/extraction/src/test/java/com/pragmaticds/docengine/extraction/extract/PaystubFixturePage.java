package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@code fixtures/truth/paystub_complete.json} page rebuilt word-for-word: all 50 words in
 * reading order (span ids 1..50) plus the earnings grid as a TABLE → 5 TABLE_ROWs → 25
 * TABLE_CELLs layout tree whose cells link the SAME spans. Truth boxes are exact.
 */
public final class PaystubFixturePage {

    public static final UUID PAGE_ID =
            UUID.nameUUIDFromBytes("paystub-complete-page-0".getBytes(StandardCharsets.UTF_8));

    /** Table words are ids 19..43, row-major five per row. */
    private static final int FIRST_CELL_WORD_ID = 19;

    private static final List<SpanRef> WORDS =
            List.of(
                    w(1, "ACME", "72.0", "61.9", "41.2", "13.0"),
                    w(2, "WIDGETS", "119.2", "61.9", "65.3", "13.0"),
                    w(3, "LLC", "190.6", "61.9", "27.2", "13.0"),
                    w(4, "Employee:", "72.0", "94.1", "52.0", "10.2"),
                    w(5, "Jordan", "130.0", "94.1", "33.6", "10.2"),
                    w(6, "Q.", "169.6", "94.1", "11.6", "10.2"),
                    w(7, "Fixture", "187.2", "94.1", "33.6", "10.2"),
                    w(8, "Pay", "72.0", "112.1", "19.0", "10.2"),
                    w(9, "Period:", "97.0", "112.1", "34.8", "10.2"),
                    w(10, "01/01/2026", "137.8", "112.1", "55.0", "10.2"),
                    w(11, "-", "198.8", "112.1", "3.7", "10.2"),
                    w(12, "01/15/2026", "208.5", "112.1", "55.0", "10.2"),
                    w(13, "Pay", "72.0", "130.1", "19.0", "10.2"),
                    w(14, "Date:", "97.0", "130.1", "26.3", "10.2"),
                    w(15, "01/17/2026", "129.2", "130.1", "55.0", "10.2"),
                    w(16, "Pay", "72.0", "148.1", "19.0", "10.2"),
                    w(17, "Frequency:", "97.0", "148.1", "55.0", "10.2"),
                    w(18, "Bi-Weekly", "158.0", "148.1", "49.5", "10.2"),
                    w(19, "Earnings", "72.0", "174.1", "47.1", "10.2"),
                    w(20, "Rate", "200.0", "174.1", "23.8", "10.2"),
                    w(21, "Hours", "290.0", "174.1", "31.8", "10.2"),
                    w(22, "Current", "380.0", "174.1", "39.7", "10.2"),
                    w(23, "YTD", "490.0", "174.1", "22.0", "10.2"),
                    w(24, "Regular", "72.0", "196.1", "38.5", "10.2"),
                    w(25, "48.0771", "200.0", "196.1", "39.8", "10.2"),
                    w(26, "80.00", "290.0", "196.1", "27.5", "10.2"),
                    w(27, "3,846.17", "380.0", "196.1", "42.8", "10.2"),
                    w(28, "3,846.17", "490.0", "196.1", "42.8", "10.2"),
                    w(29, "Overtime", "72.0", "218.1", "44.6", "10.2"),
                    w(30, "72.1157", "200.0", "218.1", "39.8", "10.2"),
                    w(31, "4.50", "290.0", "218.1", "21.4", "10.2"),
                    w(32, "324.52", "380.0", "218.1", "33.6", "10.2"),
                    w(33, "324.52", "490.0", "218.1", "33.6", "10.2"),
                    w(34, "Bonus", "72.0", "240.1", "31.2", "10.2"),
                    w(35, "0.0000", "200.0", "240.1", "33.6", "10.2"),
                    w(36, "0.00", "290.0", "240.1", "21.4", "10.2"),
                    w(37, "500.00", "380.0", "240.1", "33.6", "10.2"),
                    w(38, "500.00", "490.0", "240.1", "33.6", "10.2"),
                    w(39, "Gross", "72.0", "262.1", "31.8", "10.2"),
                    w(40, "-", "200.0", "262.1", "3.7", "10.2"),
                    w(41, "-", "290.0", "262.1", "3.7", "10.2"),
                    w(42, "4,670.69", "380.0", "262.1", "42.8", "10.2"),
                    w(43, "4,670.69", "490.0", "262.1", "42.8", "10.2"),
                    w(44, "Federal", "72.0", "314.1", "37.3", "10.2"),
                    w(45, "Withholding", "115.3", "314.1", "57.5", "10.2"),
                    w(46, "612.44", "178.8", "314.1", "33.6", "10.2"),
                    w(47, "612.44", "218.4", "314.1", "33.6", "10.2"),
                    w(48, "Net", "72.0", "343.4", "19.3", "11.1"),
                    w(49, "Pay", "97.3", "343.4", "21.3", "11.1"),
                    w(50, "$3,565.87", "124.7", "343.4", "53.4", "11.1"));

    private PaystubFixturePage() {}

    public static PageContent page() {
        return new PageContent(PAGE_ID, 0, WORDS, List.of(table()));
    }

    public static PageContent pageWithoutTables() {
        return new PageContent(PAGE_ID, 0, WORDS, List.of());
    }

    public static SpanRef word(int id) {
        return WORDS.get(id - 1);
    }

    public static UUID cellId(int row, int col) {
        return UUID.nameUUIDFromBytes(
                ("paystub-cell-" + row + "-" + col).getBytes(StandardCharsets.UTF_8));
    }

    private static LayoutNode table() {
        List<LayoutNode> rows = new ArrayList<>();
        for (int row = 0; row < 5; row++) {
            List<LayoutNode> cells = new ArrayList<>();
            for (int col = 0; col < 5; col++) {
                SpanRef span = word(FIRST_CELL_WORD_ID + row * 5 + col);
                cells.add(
                        new LayoutNode(
                                cellId(row, col),
                                LayoutElementType.TABLE_CELL,
                                span.box(),
                                row,
                                col,
                                List.of(span),
                                List.of()));
            }
            // Row/table boxes are unused by extraction; the first cell's box stands in.
            rows.add(
                    new LayoutNode(
                            UUID.nameUUIDFromBytes(
                                    ("paystub-row-" + row).getBytes(StandardCharsets.UTF_8)),
                            LayoutElementType.TABLE_ROW,
                            cells.get(0).box(),
                            row,
                            null,
                            List.of(),
                            List.copyOf(cells)));
        }
        return new LayoutNode(
                UUID.nameUUIDFromBytes("paystub-table".getBytes(StandardCharsets.UTF_8)),
                LayoutElementType.TABLE,
                rows.get(0).box(),
                null,
                null,
                List.of(),
                List.copyOf(rows));
    }

    private static SpanRef w(long id, String text, String x, String y, String width, String height) {
        return new SpanRef(
                id,
                text,
                new Box(
                        new BigDecimal(x),
                        new BigDecimal(y),
                        new BigDecimal(width),
                        new BigDecimal(height)),
                BigDecimal.ONE);
    }
}
