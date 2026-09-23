package com.pragmaticds.docengine.parsing.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L2 on the wire: one page's geometric structure, exactly as persisted (full-capture design §6.2,
 * contract {@code DOCENGINE-L2-1/1.0.0}).
 *
 * <h2>What L2 is</h2>
 *
 * <p>The INDEX into the record. L1 is every captured word; L2 is what the geometry supports without
 * any schema — tables addressed by {@code (row, col)}, visual blocks, pixel marks — each carrying
 * the span ids that lead back down to L1. It is 10–20% of L1 by size, which is the intended access
 * pattern: read structure first, fetch spans only for the regions that matter.
 *
 * <p>It is a READ MODEL over {@code layout_element} + {@code layout_element_span} as the worker
 * persisted them (design D7). No detection happens at read time: re-detecting would produce a
 * second answer needing reconciliation with the one the extraction engine already cites as
 * evidence. Consequently every {@code id} here IS a {@code layout_element.id} — the same value
 * {@code evidence[].layoutElementId} carries on {@code /fields} and in the canonical envelope,
 * which is what makes the L3 → L2 → L1 walk close (design D3).
 *
 * <h2>Two confidences, never one (design D10)</h2>
 *
 * <p>{@code structureConfidence} is the element's own stored confidence — "how sure are we this is
 * a cell". {@code textConfidence} is the MINIMUM over member spans' per-word confidence — "how sure
 * are we of the glyphs" — the same rule as L3's {@code spanConfidence}, so the one axis the layers
 * share stays commensurable. They are never multiplied: L2 has no anchor and no normalizer, so two
 * of L3's three components have no meaning here, and one blended number would be a lie with four
 * decimal places. A structure with no member spans has no text to be sure of: {@code
 * textConfidence} is null, not 1.0.
 *
 * <h2>"Missing" does not exist here (design D11)</h2>
 *
 * <p>L2 has no expectations — a schema names things to look for, and that is L3. An empty cell is
 * {@code text: ""} with no span ids: the cell is blank ON THE PAGE. A region no detector resolved
 * is simply absent, and {@link #detectorCoverage} says whether anyone looked. L2 says what is on
 * the page; L3 says what we looked for.
 *
 * @param contentHash the page's parse pin, same value and same caveats as on {@code /spans}: it is
 *     the duplicate-detection digest — compare across two reads of the SAME page, never across
 *     pages — and null on a page parsed before the signal shipped.
 * @param structureContract the L2 shape+semantics version. Bumps when the structure vocabulary
 *     changes (P3 adds {@code pairs}); rides in the ETag so a consumer sees the change instead of
 *     absorbing it silently.
 * @param detectorCoverage per detector family: {@code RAN} (the worker looked on this page),
 *     {@code NOT_IMPLEMENTED} (the worker declared it did not look — no detector in that build, or
 *     no pixels for a pixel detector), {@code UNKNOWN} (the parse predates the persisted
 *     declaration, so nobody can say). {@code UNKNOWN} is materially different from {@code RAN}
 *     with an empty result — that distinction is the point of the member.
 */
public record PageStructureView(
        UUID pageId,
        BigDecimal widthPt,
        BigDecimal heightPt,
        int rotation,
        String contentHash,
        String structureContract,
        Map<String, String> detectorCoverage,
        List<BlockView> blocks,
        List<TableView> tables,
        List<MarkView> marks) {

    /** The L2 contract this response shape implements; carried on the wire and in the ETag. */
    public static final String STRUCTURE_CONTRACT = "DOCENGINE-L2-1/1.0.0";

    /** A box in the canonical frame: PDF points, top-left origin, rotation-0. */
    public record BoxView(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}

    /**
     * A visual block — lines the clustering grouped. {@code kind} is {@code PARAGRAPH}, {@code
     * HEADER} or {@code FORM_FIELD} (a label and its figure, spans label-then-value). The block's text is the linked spans' text in link order, denormalized at parse
     * time; it names nothing ("ACME WIDGETS LLC" is a block, not a company name — nothing on the
     * page says it is one).
     */
    public record BlockView(
            UUID id,
            String kind,
            BoxView box,
            BigDecimal structureConfidence,
            BigDecimal textConfidence,
            String text,
            List<Long> spanIds) {}

    /**
     * A detected table. {@code rows}/{@code cols}/{@code ruled} are the detector's own declaration
     * (the TABLE element's attributes). {@code ruled: true} means vector rulings independently
     * confirmed the whitespace grid — 0.95 versus the honest unruled 0.75 — and a rotated page can
     * never claim it: the worker skips rulings entirely on nonzero {@code /Rotate}.
     *
     * <p>Cells ride flat with {@code (row, col)} addressing; the TABLE_ROW rung of the persisted
     * tree is walked, not exposed, because a row is fully described by its cells' shared {@code
     * row}. The table has no {@code textConfidence} of its own — text certainty is per cell.
     */
    public record TableView(
            UUID id,
            BoxView box,
            int rows,
            int cols,
            boolean ruled,
            BigDecimal structureConfidence,
            List<CellView> cells) {}

    /** One cell. An empty cell is {@code text: ""}, no spans, null {@code textConfidence}. */
    public record CellView(
            UUID id,
            int row,
            int col,
            BoxView box,
            String text,
            BigDecimal textConfidence,
            List<Long> spanIds) {}

    /**
     * A pixel detection: {@code kind} is {@code CHECKBOX} or {@code SIGNATURE}. {@code checked} is
     * the checkbox detector's own measured state, null for signatures — a signature asserts
     * PRESENCE only, never identity and never a state. Marks have no member spans (ink is not
     * text), so {@code textConfidence} is always null and {@code spanIds} empty.
     */
    public record MarkView(
            UUID id,
            String kind,
            BoxView box,
            BigDecimal structureConfidence,
            BigDecimal textConfidence,
            Boolean checked,
            List<Long> spanIds) {}
}
