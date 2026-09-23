package com.pragmaticds.docengine.results.body;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyBlock;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyCell;
import com.pragmaticds.docengine.results.body.DocumentBody.TableSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One test per rendering rule in {@link DocumentBodyMarkdown}'s contract, named for the rule.
 *
 * <p>No database and no Spring: the renderer is a pure function of a record, mirroring {@code
 * DocumentBodyComposerTest}. Ids are minted from a counter rather than {@code randomUUID} because a
 * rendering assertion must be reproducible byte-for-byte rather than passing on a lucky draw.
 */
class DocumentBodyMarkdownTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    private static UUID uuid(int n) {
        return new UUID(0L, n);
    }

    private static BodyBox box() {
        return new BodyBox(
                BigDecimal.valueOf(72.0),
                BigDecimal.valueOf(96.0),
                BigDecimal.valueOf(100.0),
                BigDecimal.valueOf(12.0));
    }

    private static BodyBlock block(LayoutElementType type, String text) {
        return new BodyBlock(
                uuid(1), type, uuid(900), 3, box(), text, null, null, List.of(), null, null);
    }

    private static BodyBlock checkbox(String text, Boolean checked) {
        return new BodyBlock(
                uuid(1),
                LayoutElementType.CHECKBOX,
                uuid(900),
                3,
                box(),
                text,
                null,
                null,
                List.of(),
                null,
                checked);
    }

    private static BodyCell cell(int row, int col, String text) {
        return new BodyCell(uuid(100 + row * 10 + col), row, col, box(), text, List.of());
    }

    /** A table whose columns the composer separated cleanly; see {@link #untrustworthyTable}. */
    private static BodyBlock table(int rows, int cols, BodyCell... cells) {
        return new BodyBlock(
                uuid(1),
                LayoutElementType.TABLE,
                uuid(900),
                3,
                box(),
                "",
                null,
                null,
                List.of(),
                new TableSpec(rows, cols, false, true, List.of(cells)),
                null);
    }

    /** A table the composer marked not columnisable: a cell provably holds two columns. */
    private static BodyBlock untrustworthyTable(int rows, int cols, BodyCell... cells) {
        return new BodyBlock(
                uuid(1),
                LayoutElementType.TABLE,
                uuid(900),
                3,
                box(),
                "",
                null,
                null,
                List.of(),
                new TableSpec(rows, cols, false, false, List.of(cells)),
                null);
    }

    private static String render(BodyBlock... blocks) {
        return DocumentBodyMarkdown.render(
                new DocumentBody(uuid(1), "BANK_STATEMENT", 1, List.of(blocks)));
    }

    // ── HEADER → ## ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("HEADER renders as a level-two heading")
    void header_renders_as_level_two_heading() {
        assertThat(render(block(LayoutElementType.HEADER, "Account Summary")))
                .isEqualTo("## Account Summary\n");
    }

    @Test
    @DisplayName("HEADER collapses an internal newline, because a heading is one line by definition")
    void header_collapses_internal_newlines() {
        assertThat(render(block(LayoutElementType.HEADER, "Account\nSummary")))
                .isEqualTo("## Account Summary\n");
    }

    // ── PARAGRAPH → prose ───────────────────────────────────────────────────

    @Test
    @DisplayName("PARAGRAPH renders as a prose paragraph")
    void paragraph_renders_as_prose() {
        assertThat(render(block(LayoutElementType.PARAGRAPH, "The borrower attests as follows.")))
                .isEqualTo("The borrower attests as follows.\n");
    }

    @Test
    @DisplayName("blocks are separated by exactly one blank line")
    void blocks_are_separated_by_one_blank_line() {
        assertThat(
                        render(
                                block(LayoutElementType.HEADER, "Title"),
                                block(LayoutElementType.PARAGRAPH, "Body.")))
                .isEqualTo("## Title\n\nBody.\n");
    }

    // ── TABLE → GFM ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("TABLE assembles a GFM table from cells by (row, col)")
    void table_assembles_by_row_and_col() {
        assertThat(
                        render(
                                table(
                                        2,
                                        2,
                                        cell(1, 1, "$1,200"),
                                        cell(0, 0, "Item"),
                                        cell(1, 0, "Rent"),
                                        cell(0, 1, "Amount"))))
                .isEqualTo(
                        """
                        | Item | Amount |
                        | --- | --- |
                        | Rent | $1,200 |
                        """);
    }

    @Test
    @DisplayName("TABLE emits an empty header row where nothing distinguishes labels from data")
    void table_emits_an_empty_header_when_row_zero_is_not_labels() {
        // One row: there is no row below to be the values, so row 0 cannot be shown to be labels.
        String markdown = render(table(1, 2, cell(0, 0, "Rent"), cell(0, 1, "$1,200")));

        assertThat(markdown.lines().toList())
                .containsExactly("|  |  |", "| --- | --- |", "| Rent | $1,200 |");
    }

    @Test
    @DisplayName("TABLE never promotes a row that holds values")
    void table_never_promotes_a_value_row() {
        String markdown =
                render(
                        table(
                                2,
                                2,
                                cell(0, 0, "Rent"),
                                cell(0, 1, "$1,200"),
                                cell(1, 0, "Utilities"),
                                cell(1, 1, "$90")));

        assertThat(markdown.lines().toList()).startsWith("|  |  |", "| --- | --- |");
    }

    @Test
    @DisplayName("TABLE whose columns could not be separated renders as prose, never as a grid")
    void table_with_unseparable_columns_renders_as_prose() {
        // The composer proved a cell holds two columns\u2019 content and could not split it. A grid
        // would present the pair as one value with a citation that looks legitimate.
        String markdown =
                render(
                        untrustworthyTable(
                                2,
                                2,
                                cell(0, 0, "REG"),
                                cell(0, 1, "40.00 1,000.00"),
                                cell(1, 0, "OT"),
                                cell(1, 1, "8.00 200.00")));

        assertThat(markdown.lines().toList())
                .containsExactly(
                        "_[table: columns could not be separated]_",
                        "REG \u00b7 40.00 1,000.00",
                        "OT \u00b7 8.00 200.00");
    }

    @Test
    @DisplayName("TABLE renders an unaddressed hole as an empty cell")
    void table_hole_renders_as_an_empty_cell() {
        assertThat(render(table(2, 2, cell(0, 0, "Item"), cell(1, 1, "$1,200"))))
                .isEqualTo(
                        """
                        | Item |  |
                        | --- | --- |
                        |  | $1,200 |
                        """);
    }

    @Test
    @DisplayName("TABLE grows past the declared census rather than dropping an addressed cell")
    void table_grid_is_the_union_of_declaration_and_addressed_cells() {
        // Declared 1x1, but a cell is addressed at (1, 1). Honouring the declaration alone would
        // delete page text, which is the one thing a body may never do.
        assertThat(render(table(1, 1, cell(0, 0, "a"), cell(1, 1, "d"))))
                .isEqualTo(
                        """
                        |  |  |
                        | --- | --- |
                        | a |  |
                        |  | d |
                        """);
    }

    @Test
    @DisplayName("TABLE with no addressed cells renders a labelled placeholder, not an empty grid")
    void table_with_no_cells_renders_a_placeholder() {
        assertThat(render(table(0, 0))).isEqualTo("_[table]_\n");
    }

    @Test
    @DisplayName("TABLE clamps an absurd declared census to what a page can print")
    void table_clamps_an_absurd_declared_census() {
        String markdown = render(table(Integer.MAX_VALUE, 1, cell(0, 0, "a")));

        // Header + delimiter + the clamp bound of body rows.
        assertThat(markdown.lines().count()).isEqualTo(2 + 1000);
    }

    // ── CHECKBOX → task list item ───────────────────────────────────────────

    @Test
    @DisplayName("CHECKBOX renders - [x] when checked, - [ ] when clear, - [?] when unknown")
    void checkbox_renders_its_three_states() {
        assertThat(render(checkbox("Owner occupied", Boolean.TRUE)))
                .isEqualTo("- [x] Owner occupied\n");
        assertThat(render(checkbox("Owner occupied", Boolean.FALSE)))
                .isEqualTo("- [ ] Owner occupied\n");
        assertThat(render(checkbox("Owner occupied", null))).isEqualTo("- [?] Owner occupied\n");
    }

    @Test
    @DisplayName("CHECKBOX with no text still renders its state")
    void checkbox_without_text_still_renders_its_state() {
        assertThat(render(checkbox("", Boolean.TRUE))).isEqualTo("- [x]\n");
    }

    // ── SIGNATURE / IMAGE → labelled placeholder ────────────────────────────

    @Test
    @DisplayName("SIGNATURE and IMAGE render a labelled placeholder with no invented alt text")
    void signature_and_image_render_a_labelled_placeholder() {
        assertThat(render(block(LayoutElementType.SIGNATURE, ""))).isEqualTo("_[signature]_\n");
        assertThat(render(block(LayoutElementType.IMAGE, ""))).isEqualTo("_[image]_\n");
    }

    @Test
    @DisplayName("a placeholder carries the element's own captured text, never a description")
    void placeholder_keeps_captured_text_verbatim() {
        assertThat(render(block(LayoutElementType.SIGNATURE, "Jane Q. Borrower")))
                .isEqualTo("_[signature: Jane Q. Borrower]_\n");
    }

    // ── FORM_FIELD → text verbatim ──────────────────────────────────────────

    @Test
    @DisplayName("FORM_FIELD renders its text verbatim, never split into label and value")
    void form_field_renders_its_text_verbatim() {
        // The seam carries no label/value pair, and splitting on a colon here would be detection
        // at read time.
        assertThat(render(block(LayoutElementType.FORM_FIELD, "Borrower Name: Jane Q. Borrower")))
                .isEqualTo("Borrower Name: Jane Q. Borrower\n");
    }

    // ── LINE → its own paragraph ────────────────────────────────────────────

    @Test
    @DisplayName("a standalone LINE renders as its own paragraph, never merged with a neighbour")
    void standalone_line_renders_as_its_own_paragraph() {
        assertThat(
                        render(
                                block(LayoutElementType.LINE, "first line"),
                                block(LayoutElementType.LINE, "second line")))
                .isEqualTo("first line\n\nsecond line\n");
    }

    // ── escaping ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a pipe in a cell is escaped, so one character cannot break the table")
    void pipe_in_a_cell_is_escaped() {
        assertThat(render(table(1, 1, cell(0, 0, "a | b"))))
                .isEqualTo(
                        """
                        |  |
                        | --- |
                        | a \\| b |
                        """);
    }

    @Test
    @DisplayName("inline formatting characters are escaped so document text renders as itself")
    void inline_formatting_characters_are_escaped() {
        assertThat(render(block(LayoutElementType.PARAGRAPH, "a_b_c *x* `y` [z] <t> & ~n~ \\d")))
                .isEqualTo("a\\_b\\_c \\*x\\* \\`y\\` \\[z\\] \\<t\\> \\& \\~n\\~ \\\\d\n");
    }

    @Test
    @DisplayName("a line-leading block marker is escaped so prose cannot become a heading or list")
    void line_leading_block_markers_are_escaped() {
        assertThat(render(block(LayoutElementType.PARAGRAPH, "# 5 Star\n- item\n1. first\n=== ")))
                .isEqualTo("\\# 5 Star\n\\- item\n1\\. first\n\\===\n");
    }

    @Test
    @DisplayName("a leading indent is stripped so page whitespace cannot become a code block")
    void leading_indent_is_stripped() {
        assertThat(render(block(LayoutElementType.PARAGRAPH, "    indented")))
                .isEqualTo("indented\n");
    }

    // ── output conventions ──────────────────────────────────────────────────

    @Test
    @DisplayName("a block with no text contributes nothing rather than an empty construct")
    void a_textless_block_contributes_nothing() {
        assertThat(
                        render(
                                block(LayoutElementType.PARAGRAPH, ""),
                                block(LayoutElementType.HEADER, "Only me")))
                .isEqualTo("## Only me\n");
    }

    @Test
    @DisplayName("an empty body renders an empty string, not a lone newline")
    void an_empty_body_renders_an_empty_string() {
        assertThat(render()).isEmpty();
    }

    @Test
    @DisplayName("LF endings, no trailing spaces, exactly one trailing newline")
    void output_conventions_hold() {
        String markdown =
                render(
                        block(LayoutElementType.HEADER, "Title"),
                        block(LayoutElementType.PARAGRAPH, "Body.  "));

        assertThat(markdown).doesNotContain("\r").endsWith(".\n");
        assertThat(markdown).doesNotContain(" \n").doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("the same body renders the same bytes on every call")
    void rendering_is_deterministic() {
        DocumentBody body =
                new DocumentBody(
                        uuid(1),
                        "BANK_STATEMENT",
                        1,
                        List.of(
                                block(LayoutElementType.HEADER, "Account Summary"),
                                table(1, 2, cell(0, 1, "b"), cell(0, 0, "a")),
                                checkbox("flag", null)));

        assertThat(DocumentBodyMarkdown.render(body))
                .isEqualTo(DocumentBodyMarkdown.render(body))
                .isEqualTo(DocumentBodyMarkdown.render(body));
    }
}
