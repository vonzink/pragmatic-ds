package com.pragmaticds.docengine.results.body;

import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyBlock;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyCell;
import com.pragmaticds.docengine.results.body.DocumentBody.TableSpec;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceElement;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourcePage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Composes {@link DocumentBodySource} into {@link DocumentBody}: the persisted L2 rows of a
 * document's member pages, flattened into one reading-order sequence.
 *
 * <h2>The determinism contract</h2>
 *
 * <p><b>Same input, same output — always.</b> A pure static function: no clock, no hostname, no
 * locale-sensitive formatting, no iteration over an unordered collection. Every ordering rule below
 * is TOTAL, so there is no tie left for a hash seed or a query planner to break. A body that
 * differed run-to-run could not be cached, diffed, or cited.
 *
 * <h2>Ordering rules, in force</h2>
 *
 * <ol>
 *   <li>Pages ascend by {@code documentPageOrdinal}, whatever order they arrive in, then by page id.
 *   <li>Within a page, blocks ascend by element {@code ordinal} — NOT by geometry. A multi-column
 *       page orders column-major, so the second block of a two-column page sits below the first on
 *       the paper, not beside it. Sorting by {@code y} would read the page across the gutter.
 *   <li>An {@code ordinal} tie breaks on {@code layoutElementId} ascending. Ordinals are unique in
 *       practice; the rule exists so the order is total even when they are not.
 *   <li>Only top-level elements become blocks. Rows and cells are reached through their table.
 *   <li>A table's cells ascend by {@code (row, col)}, then {@code layoutElementId}.
 *   <li>Row and column counts are the detector's declaration where it made one, else a census of
 *       {@code 1 + max(index)} over the addressed cells, else zero.
 *   <li>A column whose own spans prove it is two columns the detector merged is separated into
 *       one column per printed position — see {@link TableColumns}. This is the one place the
 *       composer alters a detector's addressing, and it does so only on the parser's own measured
 *       span extents, only when the rewrite reproduces the cell's text exactly, and never by
 *       moving a value into a column it does not occupy on the page.
 * </ol>
 *
 * <h2>Where this deliberately diverges from L2</h2>
 *
 * <p><b>Nothing is dropped for want of a shape.</b> {@code FORM_FIELD}, {@code IMAGE} and {@code
 * LINE} become blocks here (see {@link DocumentBody.BodyBlock}).
 *
 * <p><b>A malformed child is promoted, never discarded.</b> Two cases: a child whose parent is
 * absent from the page, and a {@code TABLE_CELL} carrying no {@code (row, col)} address. L2 drops
 * the first silently and THROWS on the second, and for an index both are right — serving a cell
 * under an invented address would be a wrong record. A body trades differently: an exception would
 * let one corrupt cell suppress an entire document, and a silent drop would delete text from prose
 * that claims to be the document. Both are therefore promoted to top-level blocks at their own
 * element ordinal, so the text survives, and the table still renders from whatever cells ARE
 * addressed.
 */
public final class DocumentBodyComposer {

    private DocumentBodyComposer() {}

    /** Total order over elements within one page: ordinal, then id. See rules 2 and 3. */
    private static final Comparator<SourceElement> READING_ORDER =
            Comparator.comparingInt(SourceElement::ordinal).thenComparing(SourceElement::id);

    /** Total order over a table's cells: row, then column, then id. See rule 5. */
    private static final Comparator<SourceElement> CELL_ORDER =
            Comparator.comparingInt((SourceElement cell) -> cell.cellRow())
                    .thenComparingInt(SourceElement::cellCol)
                    .thenComparing(SourceElement::id);

    /** Total order over pages: document ordinal, then page id. See rule 1. */
    private static final Comparator<SourcePage> PAGE_ORDER =
            Comparator.comparingInt(SourcePage::documentPageOrdinal)
                    .thenComparing(SourcePage::pageId);

    /** Composes the body. See the class contract for every ordering rule. */
    public static DocumentBody compose(DocumentBodySource source) {
        List<SourcePage> pages = new ArrayList<>(source.pages());
        pages.sort(PAGE_ORDER);

        List<BodyBlock> blocks = new ArrayList<>();
        for (SourcePage page : pages) {
            blocks.addAll(blocksOf(page));
        }
        return new DocumentBody(
                source.logicalDocumentId(), source.documentTypeCode(), pages.size(), blocks);
    }

    private static List<BodyBlock> blocksOf(SourcePage page) {
        Map<UUID, SourceElement> byId = new LinkedHashMap<>();
        for (SourceElement element : page.elements()) {
            byId.put(element.id(), element);
        }

        Map<UUID, List<SourceElement>> childrenByParent = new LinkedHashMap<>();
        for (SourceElement element : page.elements()) {
            if (element.parentElementId() != null) {
                childrenByParent
                        .computeIfAbsent(element.parentElementId(), parent -> new ArrayList<>())
                        .add(element);
            }
        }

        List<SourceElement> topLevel = new ArrayList<>();
        for (SourceElement element : page.elements()) {
            if (isTopLevel(element, byId)) {
                topLevel.add(element);
            }
        }
        topLevel.sort(READING_ORDER);

        List<BodyBlock> blocks = new ArrayList<>(topLevel.size());
        for (SourceElement element : topLevel) {
            blocks.add(block(page, element, childrenByParent));
        }
        return blocks;
    }

    /**
     * Whether an element is emitted as a block in its own right.
     *
     * <p>A genuine top-level element, plus the two promotion cases the class contract describes: an
     * orphan whose parent is not on this page, and an unaddressed cell no table can place. Both
     * carry text that would otherwise vanish from prose claiming to be the document.
     */
    private static boolean isTopLevel(SourceElement element, Map<UUID, SourceElement> byId) {
        if (element.parentElementId() == null) {
            return true;
        }
        if (!byId.containsKey(element.parentElementId())) {
            return true; // orphan: promoted rather than dropped
        }
        return element.type() == LayoutElementType.TABLE_CELL && !addressed(element);
    }

    /** A cell is placeable only when the detector addressed BOTH of its coordinates. */
    private static boolean addressed(SourceElement cell) {
        return cell.cellRow() != null && cell.cellCol() != null;
    }

    private static BodyBlock block(
            SourcePage page,
            SourceElement element,
            Map<UUID, List<SourceElement>> childrenByParent) {
        return new BodyBlock(
                element.id(),
                element.type(),
                page.pageId(),
                page.packagePageIndex(),
                element.box(),
                element.text(),
                element.structureConfidence(),
                element.textConfidence(),
                element.spanIds(),
                element.type() == LayoutElementType.TABLE
                        ? table(element, childrenByParent)
                        : null,
                element.type() == LayoutElementType.CHECKBOX ? element.checked() : null);
    }

    private static TableSpec table(
            SourceElement table, Map<UUID, List<SourceElement>> childrenByParent) {
        List<SourceElement> addressedCells = new ArrayList<>();
        for (SourceElement row : childrenByParent.getOrDefault(table.id(), List.of())) {
            if (row.type() != LayoutElementType.TABLE_ROW) {
                continue;
            }
            for (SourceElement cell : childrenByParent.getOrDefault(row.id(), List.of())) {
                if (cell.type() == LayoutElementType.TABLE_CELL && addressed(cell)) {
                    addressedCells.add(cell);
                }
            }
        }
        addressedCells.sort(CELL_ORDER);

        // Rule 7: a detected cell that provably holds two columns' ink is separated by the span
        // geometry the parser already measured, so no two columns' amounts share a cell.
        TableColumns.Resolved resolved = TableColumns.resolve(addressedCells);
        List<BodyCell> cells = resolved.cells();

        // A declared column count that predates the split is adjusted by exactly what the split
        // added. Contradicting the declaration outright would discard the detector's own census of
        // trailing empty columns; ignoring the split would under-report the grid it produced.
        Integer declaredCols =
                table.tableCols() == null ? null : table.tableCols() + resolved.addedColumns();

        return new TableSpec(
                census(table.tableRows(), cells, BodyCell::row),
                census(declaredCols, cells, BodyCell::col),
                // Absent means unconfirmed: "ruled: true" is a claim vector rulings must have made.
                Boolean.TRUE.equals(table.tableRuled()),
                resolved.trustworthy(),
                cells);
    }

    /**
     * The detector's declared count, else one derived from the cells a consumer would count.
     *
     * <p>Derived, never invented: a table with no addressed cells censuses to zero rather than to a
     * guess, because "we found no cells" and "there is one empty row" are different claims.
     */
    private static int census(
            Integer declared, List<BodyCell> cells, java.util.function.ToIntFunction<BodyCell> axis) {
        if (declared != null) {
            return declared;
        }
        return 1 + cells.stream().mapToInt(axis).max().orElse(-1);
    }
}
