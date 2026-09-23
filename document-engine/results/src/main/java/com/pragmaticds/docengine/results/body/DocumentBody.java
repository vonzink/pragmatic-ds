package com.pragmaticds.docengine.results.body;

import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DOCENGINE-BODY-1: one logical document as a single reading-order sequence of blocks, each
 * carrying the page and box that justify it.
 *
 * <p>This is the artifact that answers a question no schema anticipated. An extraction schema is a
 * fixed field list, so the read model can only answer what someone named at authoring time; a body
 * needs no schema at all, which is also why it is the only artifact that survives a document
 * classified {@code UNKNOWN}.
 *
 * <h2>Blocks are the contract; Markdown is a rendering of them</h2>
 *
 * <p>{@link BodyBlock} carries the geometry, so a citation resolves without arithmetic. Markdown is
 * a pure function of this list (T2) and never the other way round. Nothing here carries a character
 * offset into any rendered string: masking changes text length, and an offset-based citation would
 * be silently invalidated the day masking lands.
 *
 * @param pageCount member pages, not blocks — a document with no detected structure still has pages
 */
public record DocumentBody(
        UUID logicalDocumentId, String documentTypeCode, int pageCount, List<BodyBlock> blocks) {

    public DocumentBody {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /**
     * One top-level element in document reading order.
     *
     * <p><b>Every type appears here, including the three L2 declines to shape.</b> {@code
     * PageStructureController.assemble} drops {@code FORM_FIELD}, {@code IMAGE} and {@code LINE}
     * because they have no shape in {@code DOCENGINE-L2-1/1.0.0}, and for an INDEX that is right —
     * a consumer that wants them walks down to L1. A body is not an index. Dropping an element from
     * a body means its text is simply not in the prose, and a body that silently omits document
     * content is the confident-partial failure this whole system is built to prevent. They have no
     * producer today, so the practical difference is nil; encoding L2's segregation as though it
     * were the document's structure is the part that would age badly.
     *
     * @param table populated only for {@link LayoutElementType#TABLE}, null otherwise
     * @param checked populated only for {@link LayoutElementType#CHECKBOX}; null means the detector
     *     could not tell, never false
     */
    public record BodyBlock(
            UUID layoutElementId,
            LayoutElementType type,
            UUID pageId,
            int packagePageIndex,
            BodyBox box,
            String text,
            BigDecimal structureConfidence,
            BigDecimal textConfidence,
            List<Long> spanIds,
            TableSpec table,
            Boolean checked) {

        public BodyBlock {
            text = text == null ? "" : text;
            spanIds = spanIds == null ? List.of() : List.copyOf(spanIds);
        }
    }

    /**
     * A table's addressed cells plus the detector's own census.
     *
     * @param rows the DECLARED row count where the detector stated one, else a census derived from
     *     the cells a consumer would count — derived, never invented
     * @param ruled absent means unconfirmed: {@code true} is a claim vector rulings must have made
     * @param columnsTrustworthy false when {@link TableColumns} proved a cell holds two columns'
     *     content and could not separate it losslessly. A grid drawn from such cells would present
     *     two different columns' amounts as one value with a citation that looks legitimate, so
     *     the renderer emits the rows as prose instead. The cells themselves are unchanged: no
     *     page text is dropped, only the claim that it is laid out in columns.
     */
    public record TableSpec(
            int rows, int cols, boolean ruled, boolean columnsTrustworthy, List<BodyCell> cells) {

        public TableSpec {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    /**
     * One {@code (row, col)}-addressed cell. An empty cell is text {@code ""} ON THE PAGE.
     *
     * <p>{@code layoutElementId} is the element the characters came from, which after a column
     * split is shared by every fragment of one detected cell — the parser wrote one row there, and
     * a citation naming anything else would name a row that does not exist.
     */
    public record BodyCell(
            UUID layoutElementId, int row, int col, BodyBox box, String text, List<Long> spanIds) {

        public BodyCell {
            text = text == null ? "" : text;
            spanIds = spanIds == null ? List.of() : List.copyOf(spanIds);
        }
    }
}
