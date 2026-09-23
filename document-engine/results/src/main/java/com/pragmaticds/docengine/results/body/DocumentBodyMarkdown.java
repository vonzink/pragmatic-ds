package com.pragmaticds.docengine.results.body;

import com.pragmaticds.docengine.results.body.DocumentBody.BodyBlock;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyCell;
import com.pragmaticds.docengine.results.body.DocumentBody.TableSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link DocumentBody} into Markdown: the prose half of {@code DOCENGINE-BODY-1}, for the
 * consumer that wants to read the document rather than cite it.
 *
 * <h2>The determinism contract</h2>
 *
 * <p><b>Same input, same bytes — always.</b> A pure static function of {@link DocumentBody}: no
 * clock, no hostname, no locale-sensitive formatting, no iteration over an unordered collection, no
 * repository call, no Spring. Every rule below is TOTAL, so there is no tie left for a hash seed or
 * a query planner to break. This is {@code DOCENGINE-MD-1}'s contract applied to the body, and it
 * is what makes the rendering cacheable, diffable and citable.
 *
 * <h2>Blocks are the contract; this is a rendering of them</h2>
 *
 * <p>The direction is one-way. Blocks carry the geometry that resolves a citation; Markdown is a
 * projection of blocks and is never read back. <b>Nothing here emits, returns or records a
 * character offset into the rendered string.</b> Masking changes text length, so an offset-based
 * citation would be silently invalidated the day masking lands — which is exactly why chunks are
 * runs of blocks rather than slices of this string.
 *
 * <h2>Rendering rules, in force</h2>
 *
 * <ol>
 *   <li>One block renders to at most one Markdown construct, and constructs are separated by
 *       exactly one blank line, in block order. Nothing is reordered, merged or grouped here: the
 *       composer already fixed reading order, and a renderer that re-grouped would be asserting
 *       structure no detector declared.
 *   <li>{@code HEADER} → {@code ##}. Always level two: the body has no heading hierarchy to draw
 *       from — {@code LayoutElementType} has no level and no {@code SECTION} — and inferring one
 *       from font size would be detection at read time.
 *   <li>{@code PARAGRAPH}, {@code FORM_FIELD} and {@code LINE} → a prose paragraph.
 *   <li>{@code TABLE} → a GFM table assembled from {@link TableSpec#cells()} by {@code (row,
 *       col)}. Row 0 heads the table when it is labels rather than values; otherwise the header
 *       row is empty. See "The header row" below.
 *   <li>{@code CHECKBOX} → {@code - [x]} checked, {@code - [ ]} clear, {@code - [?]} where the
 *       detector could not tell. {@code - [?]} is not valid GFM task-list syntax and renders as
 *       literal text, which is the point: an unknown state must not look like a decided one.
 *   <li>{@code SIGNATURE} / {@code IMAGE} → a labelled placeholder, {@code _[signature]_} /
 *       {@code _[image]_}. <b>Never invented alt text.</b> Where the element carries captured text
 *       of its own, that text — and only that text — appears inside the placeholder, because it is
 *       ink on the page and dropping it would delete document content.
 *   <li>A block whose rendering would carry no text contributes NOTHING, and no separator with it.
 *       An empty heading or an empty paragraph is punctuation asserting a structure the reader
 *       cannot see. {@code CHECKBOX} and the placeholders are the exceptions: for them the mark
 *       itself IS the content, and a detected signature that rendered as nothing would be a
 *       deletion of the most consequential thing on the page.
 *   <li>LF endings, no trailing spaces, exactly one trailing newline. An empty body renders the
 *       empty string rather than a lone newline, so "no structure was detected" is distinguishable
 *       from "one blank block was".
 * </ol>
 *
 * <h2>A standalone {@code LINE} is its own paragraph</h2>
 *
 * <p>The design says {@code LINE} is "joined into its parent paragraph". That is not implementable
 * against the composer as built: a {@code LINE} arrives as a TOP-LEVEL block with no parent, and
 * the composer emits every top-level element in its own right. Two alternatives were rejected:
 *
 * <ul>
 *   <li><b>Joining a {@code LINE} into the preceding block.</b> That is inference — deciding two
 *       elements are one paragraph is exactly the detection D1 forbids at read time.
 *   <li><b>Merging consecutive {@code LINE} blocks into one paragraph.</b> Tempting and wrong: a
 *       chunk's text is assembled from its member blocks (D4), so a chunk boundary falling between
 *       two merged lines would produce text that disagrees with the same run of this string. One
 *       block, one construct, keeps the two renderings in lockstep by construction.
 * </ul>
 *
 * <p>{@code LINE}, {@code IMAGE} and {@code FORM_FIELD} have no producer today. This is the
 * behaviour chosen for the day one appears, not a description of current data.
 *
 * <h2>{@code FORM_FIELD} renders its text verbatim</h2>
 *
 * <p>D3's {@code **label:** value} arm requires the element to RESOLVE both, and nothing in the
 * seam does: {@link BodyBlock} carries one {@code text}. Splitting on the first colon would be
 * re-detection at read time (D1) and would corrupt every field whose value contains a colon — a
 * time, a ratio, a URL. The bold arm therefore stays unreachable until the seam gains a typed
 * label/value pair, at which point this method gains one branch and nothing else changes.
 *
 * <h2>The header row</h2>
 *
 * <p>GFM has no table without a header row, so both available renderings are claims: an empty row
 * says the columns are unnamed, and a promoted row 0 says it holds their names. Neither is free.
 * {@link #labelRow} decides on the only evidence present — whether row 0 contains values — and
 * falls back to the empty row. Nothing is deleted either way: an unpromoted row 0 remains a body
 * row, and a promoted one appears exactly once.
 *
 * <h2>A table that could not be columnised is prose</h2>
 *
 * <p>Where the composer proved a cell holds two columns\u2019 content and could not separate it, the
 * rows are served as prose rather than as a grid. See {@link #proseRows}.
 *
 * <h2>A hole renders as an empty cell</h2>
 *
 * <p>{@link TableSpec#rows()} and {@code cols()} are the detector's census and may exceed the
 * addressed cells. An unaddressed position renders exactly as an addressed cell whose text is
 * {@code ""}, because Markdown has no representation that separates "not addressed" from "blank on
 * the page" and inventing one ({@code —}, {@code n/a}) would print a character the document does
 * not contain. The distinction survives where it is resolvable: the cell is simply absent from
 * {@code blocks[].table.cells}.
 *
 * <p>The grid is the UNION of the declaration and the addressed cells, never the declaration
 * alone — a cell addressed past the census must still print, since deleting page text is the one
 * failure a body may not have. The declaration is clamped at {@link #MAX_GRID}: a body must not be
 * expandable into a gigabyte of pipes by one bad integer.
 *
 * <h2>Escaping is correctness, not polish</h2>
 *
 * <p>Document text is arbitrary; Markdown syntax is not. An unescaped pipe breaks a table row into
 * different cells, an underscore pair italicises across a name, a leading {@code #} turns a
 * sentence into a heading. Each of those makes the body misrepresent the document, which is the
 * failure class this system exists to prevent, so escaping runs at ONE choke point every rendered
 * character passes through ({@link #inline}) rather than per call site.
 *
 * <p>The set is deliberately narrow rather than "all ASCII punctuation". Escaping every punctuation
 * mark is also correct, but the body's other consumer is a retrieval pipeline reading the prose,
 * and {@code 3\.5\%} degrades that for no rendering benefit. So: the characters that can change
 * rendering ANYWHERE are escaped everywhere ({@link #ALWAYS}), and the characters that can only
 * open a block at the START of a line are escaped only there ({@link #openLine}) — a hyphen inside
 * a word stays a hyphen.
 */
public final class DocumentBodyMarkdown {

    /**
     * The rendering contract. Markdown is part of {@code DOCENGINE-BODY-1} rather than a contract
     * of its own: it is a projection of the same blocks, so a consumer that sees the body version
     * change has already been told the prose may have. Any change to these bytes bumps it.
     *
     * <p>The endpoint (T4) should reference this constant rather than mint a second literal — two
     * spellings of one version is how a contract quietly forks.
     */
    public static final String CONTRACT = "DOCENGINE-BODY-1/1.0.0";

    /**
     * Characters escaped wherever they appear, because each can change rendering mid-line: escape
     * itself, code span, emphasis (both spellings), link, autolink/raw HTML, entity reference,
     * table cell separator, strikethrough.
     */
    private static final String ALWAYS = "\\`*_[]<>&|~";

    /** An amount once currency, grouping, accounting parentheses and percent are stripped. */
    private static final java.util.regex.Pattern AMOUNT =
            java.util.regex.Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

    /** A printed date: {@code 3/4}, {@code 03/04/26}, {@code 2026-03-04}. */
    private static final java.util.regex.Pattern DATE =
            java.util.regex.Pattern.compile("\\d{1,4}[-/]\\d{1,2}(?:[-/]\\d{1,4})?");

    /**
     * The largest grid the renderer will print per axis. A page cannot print a thousand rows, so a
     * larger DECLARED census is a corrupt attribute rather than a table — and honouring it would
     * turn one bad integer into an unbounded response. Addressed cells are never clamped away.
     */
    private static final int MAX_GRID = 1000;

    private DocumentBodyMarkdown() {}

    /** The whole rendering. UTF-8 text, LF endings, at most one trailing newline. */
    public static String render(DocumentBody body) {
        List<String> constructs = new ArrayList<>(body.blocks().size());
        for (BodyBlock block : body.blocks()) {
            String rendered = block(block);
            if (!rendered.isEmpty()) {
                constructs.add(rendered);
            }
        }
        if (constructs.isEmpty()) {
            return "";
        }
        return String.join("\n\n", constructs) + "\n";
    }

    /** One block as one construct, or the empty string where it carries nothing to show. */
    private static String block(BodyBlock block) {
        String text = inline(block.text());
        return switch (block.type()) {
            case HEADER -> text.isEmpty() ? "" : "## " + oneLine(text);
            case TABLE -> table(block.table());
            case CHECKBOX -> checkbox(block.checked(), oneLine(text));
            case SIGNATURE -> placeholder("signature", oneLine(text));
            case IMAGE -> placeholder("image", oneLine(text));
            // PARAGRAPH, LINE, FORM_FIELD, and the child types the composer promotes to top level
            // (an orphan row, an unaddressed cell) — all of them are prose carrying page text, and
            // a promoted child that rendered as nothing would be a silent deletion.
            default -> paragraph(text);
        };
    }

    /**
     * Prose, one line of the element's text per line of output.
     *
     * <p>Internal newlines are kept: they are where the page broke, and a soft break renders as one
     * paragraph anyway. Every line is opened safely — a block marker can start any line, not just
     * the first.
     */
    private static String paragraph(String escaped) {
        List<String> lines = new ArrayList<>();
        for (String line : escaped.split("\n", -1)) {
            lines.add(openLine(trim(line)));
        }
        String joined = String.join("\n", lines).strip();
        return joined;
    }

    private static String checkbox(Boolean checked, String label) {
        String mark = checked == null ? "- [?]" : (checked ? "- [x]" : "- [ ]");
        return label.isEmpty() ? mark : mark + " " + label;
    }

    /**
     * A labelled placeholder. The label is ours and says only what the detector said; anything
     * inside the colon is the element's own captured characters, never a description of the mark.
     */
    private static String placeholder(String label, String captured) {
        return captured.isEmpty() ? "_[" + label + "]_" : "_[" + label + ": " + captured + "]_";
    }

    /**
     * A GFM table: the column labels where the table carries them, the delimiter, then the rows.
     *
     * <p>A table with no addressed cell renders as a placeholder rather than a grid of blanks — an
     * empty grid asserts a shape ("this many empty rows") that nothing detected. A table whose
     * columns could not be separated renders as prose, for the reason on {@link #proseRows}.
     */
    private static String table(TableSpec spec) {
        if (spec == null || spec.cells().isEmpty()) {
            return "_[table]_";
        }

        int rows = extent(spec.rows(), spec.cells(), true);
        int cols = extent(spec.cols(), spec.cells(), false);

        String[][] grid = new String[rows][cols];
        for (BodyCell cell : spec.cells()) {
            // Guarded rather than assumed: a negative or clamped-out address would otherwise take
            // the whole document down over one cell.
            if (cell.row() >= 0 && cell.row() < rows && cell.col() >= 0 && cell.col() < cols) {
                grid[cell.row()][cell.col()] = oneLine(inline(cell.text()));
            }
        }

        if (!spec.columnsTrustworthy()) {
            return proseRows(grid);
        }

        boolean labelled = labelRow(grid);
        StringBuilder out = new StringBuilder();
        row(out, labelled ? grid[0] : new String[cols]);
        String[] delimiter = new String[cols];
        java.util.Arrays.fill(delimiter, "---");
        row(out, delimiter);
        for (int index = labelled ? 1 : 0; index < grid.length; index++) {
            row(out, grid[index]);
        }
        out.setLength(out.length() - 1); // the caller owns the final newline
        return out.toString();
    }

    /**
     * Whether row 0 is the table's column labels, and may therefore head it.
     *
     * <p>GFM has no table without a header row, so the choice is between naming the columns and
     * printing {@code |  |  |}. Both are claims. The empty row claims "these columns are unnamed",
     * which is false whenever the detector put the labels in row 0 — the usual shape of a bank
     * statement's transaction grid and a Closing Disclosure's fee tables — and it leaves a reader
     * to recover the column from position. Promoting row 0 claims "these are the labels", which is
     * false on a grid that starts straight into data.
     *
     * <p>So the promotion runs on the one piece of evidence in hand and nothing else: <b>a label
     * row contains no values.</b> Every non-blank cell in row 0 must fail {@link #valueLike}, and
     * some cell below must pass it. That is why the measured paystub earnings grid keeps its empty
     * header — its row 0 is rates and hours, and naming a column {@code 25.00} would invent a
     * relationship no detector declared. Nothing is deleted either way: an unpromoted row 0 stays
     * a body row, and a promoted one appears exactly once, as the header.
     */
    private static boolean labelRow(String[][] grid) {
        if (grid.length < 2) {
            return false;
        }
        boolean anyLabel = false;
        for (String cell : grid[0]) {
            if (cell == null || cell.isBlank()) {
                continue;
            }
            if (valueLike(cell)) {
                return false;
            }
            anyLabel = true;
        }
        if (!anyLabel) {
            return false;
        }
        for (int index = 1; index < grid.length; index++) {
            for (String cell : grid[index]) {
                if (cell != null && valueLike(cell)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a cell reads as a value rather than a label: an amount or a date.
     *
     * <p>Currency and accounting decoration is stripped first, so {@code $1,200.00} and {@code
     * (84.19)} are values. Dates are included because a transaction grid's first column is dates,
     * and a first row of dates is data — treating it as labels would name every column after a
     * borrower's transaction.
     */
    private static boolean valueLike(String cell) {
        String text = cell.strip();
        if (DATE.matcher(text).matches()) {
            return true;
        }
        String bare = text.replaceAll("[$,()%\\s]", "");
        return !bare.isEmpty() && AMOUNT.matcher(bare).matches();
    }

    /**
     * The rows as prose, for a table whose columns could not be separated.
     *
     * <p>{@link TableSpec#columnsTrustworthy()} is false only when a cell provably holds two
     * columns' content and the split would not have been lossless. Drawing that as a grid would
     * put two different columns' amounts in one cell and let a reader — or a model computing
     * qualifying income — take the pair for one value, with a citation that looks legitimate.
     * Blanking the cell instead would delete page text, which a body may never do. So the text is
     * served in full, in row order, with the column claim withdrawn: the reader gets everything
     * the page says and no structure it cannot rely on.
     */
    private static String proseRows(String[][] grid) {
        StringBuilder out = new StringBuilder("_[table: columns could not be separated]_");
        for (String[] cells : grid) {
            List<String> present = new ArrayList<>();
            for (String cell : cells) {
                if (cell != null && !cell.isBlank()) {
                    present.add(cell);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            out.append('\n').append(openLine(String.join(" \u00b7 ", present)));
        }
        return out.toString();
    }

    /** One table line. A null entry is a hole and prints exactly as a blank cell does. */
    private static void row(StringBuilder out, String[] cells) {
        out.append('|');
        for (String cell : cells) {
            out.append(' ').append(cell == null ? "" : cell).append(" |");
        }
        out.append('\n');
    }

    /**
     * The grid extent on one axis: the declared census and the addressed cells, whichever reaches
     * further, with the DECLARATION alone clamped. Declared counts can be corrupt; an address is
     * evidence a cell exists.
     */
    private static int extent(int declared, List<BodyCell> cells, boolean rowAxis) {
        int addressed = 0;
        for (BodyCell cell : cells) {
            int index = rowAxis ? cell.row() : cell.col();
            addressed = Math.max(addressed, index + 1);
        }
        return Math.max(addressed, Math.min(Math.max(declared, 0), MAX_GRID));
    }

    // ── escaping ────────────────────────────────────────────────────────────

    /**
     * Escapes every character that can change rendering mid-line, and normalises line endings so
     * the output is one byte sequence regardless of what the parser captured.
     *
     * <p>A backslash before ASCII punctuation is a CommonMark escape that renders the literal
     * character, so this is lossless: the reader sees the document's characters.
     */
    private static String inline(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                // CRLF and lone CR both become LF; the ending is a capture artifact, not content.
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    continue;
                }
                out.append('\n');
                continue;
            }
            if (c == '\t') {
                out.append(' '); // a tab indent would open a code block
                continue;
            }
            if (ALWAYS.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Escapes the markers that open a BLOCK, which only do so at the start of a line: ATX heading,
     * bullet list, setext underline, ordered list. A hyphen inside a word is left alone — escaping
     * it everywhere would be correct and would litter every hyphenated term in the document.
     *
     * <p>Runs after {@link #inline}, whose set is disjoint from this one.
     */
    private static String openLine(String line) {
        if (line.isEmpty()) {
            return line;
        }
        char first = line.charAt(0);
        if (first == '#' || first == '-' || first == '+' || first == '=') {
            return "\\" + line;
        }
        int digits = 0;
        while (digits < line.length() && line.charAt(digits) >= '0' && line.charAt(digits) <= '9') {
            digits++;
        }
        if (digits > 0 && digits < line.length()) {
            char marker = line.charAt(digits);
            if (marker == '.' || marker == ')') {
                return line.substring(0, digits) + "\\" + line.substring(digits);
            }
        }
        return line;
    }

    /**
     * Collapses all whitespace to single spaces, for a construct that IS one line — a heading, a
     * list item, a table cell. GFM has no in-cell line break that survives escaping, and a heading
     * ends at its newline, so keeping the break would either lose the tail or split the construct.
     * The words survive; the break was layout, and layout is what the box carries.
     */
    private static String oneLine(String escaped) {
        StringBuilder out = new StringBuilder(escaped.length());
        boolean pendingSpace = false;
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == ' ' || c == '\n') {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Strips leading and trailing spaces from one line. Leading because four of them open an
     * indented code block, which would render the document's own prose as source; trailing because
     * two of them are a hard break and because the output contract forbids them.
     */
    private static String trim(String line) {
        int start = 0;
        int end = line.length();
        while (start < end && line.charAt(start) == ' ') {
            start++;
        }
        while (end > start && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(start, end);
    }
}
