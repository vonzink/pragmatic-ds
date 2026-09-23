package com.pragmaticds.docengine.results.body;

import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything {@link DocumentBodyComposer} composes — the persisted L2 rows of a logical document's
 * member pages, plus the two document-level facts an L2 row does not carry (which document, and
 * where each page sits in it).
 *
 * <p>A record rather than a set of repository calls inside the composer, so the composer stays a
 * PURE function of its input. Same reasoning as {@code MarkdownDocumentSource}: the determinism
 * claim is only testable if nothing the composer reads can change between two calls.
 *
 * <h2>The loader adapts; the composer computes</h2>
 *
 * <p>Attribute JSON is parsed by the LOADER into the typed members below — {@code cellRow}, {@code
 * tableRows}, {@code checked}. The composer never touches Jackson. A pure function that parses
 * documents is not pure in the way that matters: it can fail on input shape, which is exactly the
 * failure the seam exists to keep out of the ordering logic.
 *
 * <h2>Order is data, not arrangement</h2>
 *
 * <p>Neither list is required to arrive sorted. {@code pages} carries {@link
 * SourcePage#documentPageOrdinal} and {@code elements} carries {@link SourceElement#ordinal}, and
 * the composer sorts on both. This is deliberate defence against a real hazard: the batch query
 * {@code LayoutElementRepository.findByPageIdInOrderByPageIdAscOrdinalAsc} orders by page id — a
 * UUID — not by document page order. A composer that trusted arrival order would emit shuffled
 * prose whose every citation box was individually correct, which is the worst failure shape
 * available: it reads plausibly and passes a smoke test.
 */
public record DocumentBodySource(
        UUID logicalDocumentId, String documentTypeCode, List<SourcePage> pages) {

    public DocumentBodySource {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /**
     * One member page and every layout element persisted for it — children included, unfiltered.
     *
     * @param packagePageIndex the page's index within the PACKAGE, which is what a citation names
     *     and what a reviewer types into a page viewer
     * @param documentPageOrdinal the page's position within THIS document, from {@code
     *     logical_document_page.ordinal} — the outer sort key, and the only thing that makes
     *     document-level reading order well defined
     */
    public record SourcePage(
            UUID pageId,
            int packagePageIndex,
            int documentPageOrdinal,
            List<SourceElement> elements) {

        public SourcePage {
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    /**
     * One {@code layout_element} row, with its attribute JSON already decoded.
     *
     * @param parentElementId null for a top-level element; a table id for a row; a row id for a
     *     cell
     * @param ordinal reading order across the page's elements. Multi-column pages order
     *     COLUMN-MAJOR, so this deliberately disagrees with top-to-bottom geometry and the composer
     *     must follow it rather than the box.
     * @param text the denormalised member-span text; null is normalised to empty, because a blank
     *     cell is text {@code ""} ON THE PAGE (L2's own rule) and not an absence
     * @param textConfidence the MINIMUM over member spans, supplied by the loader — never
     *     recomputed here, so the one axis L2 and L3 share stays the same number
     * @param spans the element's member spans in LINK order, each carrying the x-extent that
     *     {@link DocumentBodyComposer} needs to tell one column from another inside a cell the
     *     detector merged. Element {@code text} IS these spans joined by a single space — {@code
     *     LayoutElementService} denormalises them that way — which is what makes a split provably
     *     lossless rather than a re-reading of the page.
     * @param cellRow the {@code row} attribute of a {@code TABLE_CELL}, null when unaddressed
     * @param tableRows the {@code rows} attribute DECLARED by the detector, null when the parse
     *     predates the declaration; the composer takes a census only when it is null
     * @param checked the {@code checked} attribute of a {@code CHECKBOX}; null means the detector
     *     could not tell, which is materially different from false
     */
    public record SourceElement(
            UUID id,
            UUID parentElementId,
            LayoutElementType type,
            int ordinal,
            BodyBox box,
            String text,
            BigDecimal structureConfidence,
            BigDecimal textConfidence,
            List<SourceSpan> spans,
            Integer cellRow,
            Integer cellCol,
            Integer tableRows,
            Integer tableCols,
            Boolean tableRuled,
            Boolean checked) {

        public SourceElement {
            text = text == null ? "" : text;
            spans = spans == null ? List.of() : List.copyOf(spans);
        }

        /** The member span ids, in link order — the shape the body's citation contract carries. */
        public List<Long> spanIds() {
            return spans.stream().map(SourceSpan::id).toList();
        }
    }

    /**
     * One {@code text_span} an element claims: its id, its horizontal extent, and its own text.
     *
     * <p>Geometry is carried at SPAN granularity because a cell's own box cannot say whether the
     * ink inside it came from one column or two, and that question is the difference between an
     * hours figure and a year-to-date figure sharing a cell. Only {@code x} and {@code width} are
     * carried: a splice is a horizontal fact, and a body that also reasoned about {@code y} inside
     * a cell would be re-running line detection at read time.
     *
     * @param x null only when the linked {@code text_span} row could not be read; a column holding
     *     any such span is left exactly as the detector addressed it, never split on partial
     *     geometry
     */
    public record SourceSpan(
            long id, BigDecimal x, BigDecimal width, String text, BigDecimal confidence) {

        public SourceSpan {
            text = text == null ? "" : text;
        }

        /** The right edge, or null where the span has no readable geometry. */
        public BigDecimal right() {
            return x == null || width == null ? null : x.add(width);
        }

        /** Whether this span can take part in column analysis at all. */
        public boolean located() {
            return x != null && width != null;
        }
    }
}
