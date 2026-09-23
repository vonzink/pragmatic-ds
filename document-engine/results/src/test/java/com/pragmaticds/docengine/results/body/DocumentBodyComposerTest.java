package com.pragmaticds.docengine.results.body;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyBlock;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceElement;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourcePage;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One test per ordering rule in {@link DocumentBodyComposer}'s contract, named for the rule.
 *
 * <p>No database and no Spring: the composer is a pure function of a record, which is the entire
 * point of the seam. Ids are minted from a counter rather than {@code randomUUID} so a tie-break
 * assertion on id order is reproducible instead of passing 50% of the time.
 */
class DocumentBodyComposerTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Deterministic ids: uuid(1) < uuid(2) < uuid(3) by {@link UUID#compareTo}. */
    private static UUID uuid(int n) {
        return new UUID(0L, n);
    }

    private static BodyBox box(double y) {
        return new BodyBox(
                BigDecimal.valueOf(72.0),
                BigDecimal.valueOf(y),
                BigDecimal.valueOf(100.0),
                BigDecimal.valueOf(12.0));
    }

    private static SourceElement element(UUID id, LayoutElementType type, int ordinal, String text) {
        return new SourceElement(
                id, null, type, ordinal, box(0), text, null, null, List.of(), null, null, null,
                null, null, null);
    }

    private static SourceElement element(
            UUID id, LayoutElementType type, int ordinal, String text, BodyBox box) {
        return new SourceElement(
                id, null, type, ordinal, box, text, null, null, List.of(), null, null, null, null,
                null, null);
    }

    private static SourceElement child(
            UUID id, UUID parent, LayoutElementType type, int ordinal, String text) {
        return new SourceElement(
                id, parent, type, ordinal, box(0), text, null, null, List.of(), null, null, null,
                null, null, null);
    }

    private static SourceElement cell(
            UUID id, UUID row, int rowIndex, int colIndex, int ordinal, String text) {
        return new SourceElement(
                id,
                row,
                LayoutElementType.TABLE_CELL,
                ordinal,
                box(0),
                text,
                null,
                null,
                List.of(),
                rowIndex,
                colIndex,
                null,
                null,
                null,
                null);
    }

    private static SourceElement table(
            UUID id, int ordinal, Integer rows, Integer cols, Boolean ruled) {
        return new SourceElement(
                id, null, LayoutElementType.TABLE, ordinal, box(0), "", null, null, List.of(), null,
                null, rows, cols, ruled, null);
    }

    private static SourcePage page(int packageIndex, int documentOrdinal, SourceElement... elements) {
        return new SourcePage(
                uuid(9000 + documentOrdinal), packageIndex, documentOrdinal, List.of(elements));
    }

    private static DocumentBody compose(SourcePage... pages) {
        return DocumentBodyComposer.compose(
                new DocumentBodySource(uuid(1), "BANK_STATEMENT", List.of(pages)));
    }

    private static List<String> texts(DocumentBody body) {
        return body.blocks().stream().map(BodyBlock::text).toList();
    }

    // ── rule 1: pages ascend by documentPageOrdinal, whatever order they arrive ──

    @Test
    @DisplayName("R1: pages are ordered by documentPageOrdinal even when supplied shuffled")
    void pages_sort_by_document_ordinal_not_arrival_order() {
        // The batch layout query orders by page id (a UUID). Arrival order is therefore
        // meaningless and the composer must not trust it.
        DocumentBody body =
                compose(
                        page(30, 2, element(uuid(20), LayoutElementType.PARAGRAPH, 0, "third")),
                        page(10, 0, element(uuid(21), LayoutElementType.PARAGRAPH, 0, "first")),
                        page(20, 1, element(uuid(22), LayoutElementType.PARAGRAPH, 0, "second")));

        assertThat(texts(body)).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("R1: each block carries its own page's packagePageIndex")
    void blocks_carry_their_page_index() {
        DocumentBody body =
                compose(
                        page(41, 1, element(uuid(20), LayoutElementType.PARAGRAPH, 0, "b")),
                        page(40, 0, element(uuid(21), LayoutElementType.PARAGRAPH, 0, "a")));

        assertThat(body.blocks()).extracting(BodyBlock::packagePageIndex).containsExactly(40, 41);
        assertThat(body.pageCount()).isEqualTo(2);
    }

    // ── rule 2: within a page, ordinal wins over geometry ────────────────────

    @Test
    @DisplayName("R2: a two-column page follows column-major ordinal, not top-to-bottom geometry")
    void multi_column_page_follows_ordinal_not_y() {
        // The worker already ordered these column-major: left column top-to-bottom, then right.
        // Sorting by y would read across the gutter and interleave the columns into nonsense.
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                element(uuid(10), LayoutElementType.PARAGRAPH, 0, "left-top", box(100)),
                                element(uuid(11), LayoutElementType.PARAGRAPH, 1, "left-bottom", box(300)),
                                element(uuid(12), LayoutElementType.PARAGRAPH, 2, "right-top", box(100)),
                                element(uuid(13), LayoutElementType.PARAGRAPH, 3, "right-bottom", box(300))));

        assertThat(texts(body))
                .containsExactly("left-top", "left-bottom", "right-top", "right-bottom");
    }

    @Test
    @DisplayName("R2: blocks ascend by ordinal when supplied out of order")
    void elements_sort_by_ordinal() {
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                element(uuid(10), LayoutElementType.PARAGRAPH, 2, "c"),
                                element(uuid(11), LayoutElementType.HEADER, 0, "a"),
                                element(uuid(12), LayoutElementType.PARAGRAPH, 1, "b")));

        assertThat(texts(body)).containsExactly("a", "b", "c");
    }

    // ── rule 3: ordinal ties break on id, so the order is total ──────────────

    @Test
    @DisplayName("R3: an ordinal collision breaks on layoutElementId ascending")
    void ordinal_tie_breaks_on_id() {
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                element(uuid(3), LayoutElementType.PARAGRAPH, 7, "third"),
                                element(uuid(1), LayoutElementType.PARAGRAPH, 7, "first"),
                                element(uuid(2), LayoutElementType.PARAGRAPH, 7, "second")));

        assertThat(texts(body)).containsExactly("first", "second", "third");
    }

    // ── rule 4: only top-level elements become blocks ────────────────────────

    @Test
    @DisplayName("R4: rows and cells are reached through their table, never emitted as blocks")
    void children_are_not_top_level_blocks() {
        UUID tableId = uuid(10);
        UUID rowId = uuid(11);
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                table(tableId, 0, 1, 2, true),
                                child(rowId, tableId, LayoutElementType.TABLE_ROW, 1, ""),
                                cell(uuid(12), rowId, 0, 0, 2, "left"),
                                cell(uuid(13), rowId, 0, 1, 3, "right")));

        assertThat(body.blocks()).hasSize(1);
        assertThat(body.blocks().get(0).type()).isEqualTo(LayoutElementType.TABLE);
        assertThat(body.blocks().get(0).table().cells())
                .extracting(DocumentBody.BodyCell::text)
                .containsExactly("left", "right");
    }

    // ── rule 5: cells ascend by (row, col), then id ──────────────────────────

    @Test
    @DisplayName("R5: cells are ordered by (row, col) then id")
    void cells_sort_by_row_then_col() {
        UUID tableId = uuid(10);
        UUID rowId = uuid(11);
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                table(tableId, 0, null, null, null),
                                child(rowId, tableId, LayoutElementType.TABLE_ROW, 1, ""),
                                cell(uuid(15), rowId, 1, 1, 5, "r1c1"),
                                cell(uuid(16), rowId, 0, 1, 6, "r0c1"),
                                cell(uuid(17), rowId, 1, 0, 7, "r1c0"),
                                cell(uuid(18), rowId, 0, 0, 8, "r0c0")));

        assertThat(body.blocks().get(0).table().cells())
                .extracting(DocumentBody.BodyCell::text)
                .containsExactly("r0c0", "r0c1", "r1c0", "r1c1");
    }

    // ── rule 6: declared census wins; derived census is the fallback ─────────

    @Test
    @DisplayName("R6: declared rows/cols/ruled are used verbatim")
    void declared_census_wins() {
        UUID tableId = uuid(10);
        UUID rowId = uuid(11);
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                table(tableId, 0, 9, 4, true),
                                child(rowId, tableId, LayoutElementType.TABLE_ROW, 1, ""),
                                cell(uuid(12), rowId, 0, 0, 2, "only")));

        DocumentBody.TableSpec spec = body.blocks().get(0).table();
        assertThat(spec.rows()).isEqualTo(9);
        assertThat(spec.cols()).isEqualTo(4);
        assertThat(spec.ruled()).isTrue();
    }

    @Test
    @DisplayName("R6: an undeclared census is derived from the addressed cells, and ruled is false")
    void census_is_derived_when_undeclared() {
        UUID tableId = uuid(10);
        UUID rowId = uuid(11);
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                table(tableId, 0, null, null, null),
                                child(rowId, tableId, LayoutElementType.TABLE_ROW, 1, ""),
                                cell(uuid(12), rowId, 2, 1, 2, "a"),
                                cell(uuid(13), rowId, 0, 0, 3, "b")));

        DocumentBody.TableSpec spec = body.blocks().get(0).table();
        assertThat(spec.rows()).isEqualTo(3);
        assertThat(spec.cols()).isEqualTo(2);
        // Absent means unconfirmed — never an unearned true.
        assertThat(spec.ruled()).isFalse();
    }

    @Test
    @DisplayName("R6: a table with no cells censuses to zero rather than failing")
    void empty_table_censuses_to_zero() {
        DocumentBody body = compose(page(0, 0, table(uuid(10), 0, null, null, null)));

        DocumentBody.TableSpec spec = body.blocks().get(0).table();
        assertThat(spec.rows()).isZero();
        assertThat(spec.cols()).isZero();
        assertThat(spec.cells()).isEmpty();
    }

    // ── divergence from L2: nothing is dropped ───────────────────────────────

    @Test
    @DisplayName("L2-divergence: FORM_FIELD, IMAGE and LINE become blocks here")
    void unshaped_types_are_included() {
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                element(uuid(10), LayoutElementType.FORM_FIELD, 0, "field"),
                                element(uuid(11), LayoutElementType.IMAGE, 1, ""),
                                element(uuid(12), LayoutElementType.LINE, 2, "a line")));

        assertThat(body.blocks())
                .extracting(BodyBlock::type)
                .containsExactly(
                        LayoutElementType.FORM_FIELD,
                        LayoutElementType.IMAGE,
                        LayoutElementType.LINE);
    }

    @Test
    @DisplayName("L2-divergence: an orphan child is promoted, not discarded")
    void orphan_child_is_promoted() {
        // parentElementId points at an element that is not on this page.
        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                element(uuid(10), LayoutElementType.PARAGRAPH, 0, "kept"),
                                child(uuid(11), uuid(999), LayoutElementType.TABLE_CELL, 1, "orphan")));

        assertThat(texts(body)).containsExactly("kept", "orphan");
    }

    @Test
    @DisplayName("L2-divergence: an unaddressed cell is promoted, and its table still renders")
    void unaddressed_cell_is_promoted_without_throwing() {
        UUID tableId = uuid(10);
        UUID rowId = uuid(11);
        SourceElement unaddressed =
                new SourceElement(
                        uuid(13),
                        rowId,
                        LayoutElementType.TABLE_CELL,
                        4,
                        box(0),
                        "unaddressed",
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        DocumentBody body =
                compose(
                        page(
                                0,
                                0,
                                table(tableId, 0, null, null, null),
                                child(rowId, tableId, LayoutElementType.TABLE_ROW, 1, ""),
                                cell(uuid(12), rowId, 0, 0, 2, "addressed"),
                                unaddressed));

        // The table survives with the cell that IS addressed...
        assertThat(body.blocks().get(0).table().cells())
                .extracting(DocumentBody.BodyCell::text)
                .containsExactly("addressed");
        // ...and the unaddressed cell's text is not lost.
        assertThat(texts(body)).containsExactly("", "unaddressed");
    }

    // ── normalisation and degenerate input ───────────────────────────────────

    @Test
    @DisplayName("null text normalises to empty, because a blank cell is blank ON THE PAGE")
    void null_text_becomes_empty() {
        DocumentBody body = compose(page(0, 0, element(uuid(10), LayoutElementType.PARAGRAPH, 0, null)));

        assertThat(body.blocks().get(0).text()).isEmpty();
    }

    @Test
    @DisplayName("a page with no elements contributes no blocks but still counts as a page")
    void page_without_structure_still_counts() {
        DocumentBody body = compose(page(0, 0), page(1, 1, element(uuid(10), LayoutElementType.PARAGRAPH, 0, "only")));

        assertThat(body.blocks()).hasSize(1);
        assertThat(body.pageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a document with no pages composes to an empty body rather than failing")
    void empty_document_composes() {
        DocumentBody body = DocumentBodyComposer.compose(new DocumentBodySource(uuid(1), "UNKNOWN", List.of()));

        assertThat(body.blocks()).isEmpty();
        assertThat(body.pageCount()).isZero();
        assertThat(body.documentTypeCode()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("composition is deterministic: two calls on the same input are equal")
    void composition_is_deterministic() {
        DocumentBodySource source =
                new DocumentBodySource(
                        uuid(1),
                        "PAYSTUB",
                        List.of(
                                page(
                                        1,
                                        1,
                                        element(uuid(11), LayoutElementType.PARAGRAPH, 1, "b"),
                                        element(uuid(10), LayoutElementType.HEADER, 0, "a")),
                                page(0, 0, element(uuid(12), LayoutElementType.PARAGRAPH, 0, "z"))));

        assertThat(DocumentBodyComposer.compose(source))
                .isEqualTo(DocumentBodyComposer.compose(source));
    }
}
