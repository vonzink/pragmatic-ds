"""ClusteringLayoutEngine — the per-page orchestration.

Pipeline order matters and is deliberate:

    lines -> tables -> pairs -> headers -> paragraphs -> reading order

Tables consume their bands FIRST so a grid's bold column-header row can never
leak into header detection; label/value pairs are lifted next so a bold
"Gross Pay" beside its figure is a FORM_FIELD rather than a HEADER next to a
PARAGRAPH (header isolation is still judged against the paired lines — a pair
is a neighbour like any other); headers are carved from what remains so
paragraph grouping never swallows a title; whatever is left becomes paragraphs. Reading
order runs over top-level elements, then ids and ordinals are assigned in one
depth-first pass (parent before children, rows before their cells), which makes
`ordinal` the page-wide reading order the contract requires.

CHECKBOX and SIGNATURE detection (Spec 3) run on the page raster when the
/v1/layout caller attached the PDF: pixel detections ride behind the
clustered reading order, each stamping its own detector identity. Without
pixels the page declares both types in `notImplemented` — an empty result
must stay distinguishable from "did not look" (WORKER_CONTRACT.md
/v1/layout).
"""

from dataclasses import dataclass

from pragmaticds_docengine_worker import __version__
from pragmaticds_docengine_worker.layout.checkbox import detect_checkboxes
from pragmaticds_docengine_worker.layout.headers import detect_headers
from pragmaticds_docengine_worker.layout.lines import build_lines
from pragmaticds_docengine_worker.layout.model import Element, LayoutSpan, Rulings, union_box
from pragmaticds_docengine_worker.layout.pairs import detect_pairs
from pragmaticds_docengine_worker.layout.paragraphs import group_paragraphs
from pragmaticds_docengine_worker.layout.raster import PageRaster
from pragmaticds_docengine_worker.layout.reading_order import order_elements
from pragmaticds_docengine_worker.layout.signature import detect_signatures
from pragmaticds_docengine_worker.layout.tables import detect_tables

NOT_IMPLEMENTED = ("CHECKBOX", "SIGNATURE")
#: Detector coverage when pixels are available — both Spec 3 detectors
#: landed, so a rendered page's notImplemented is [].
IMPLEMENTED_WITH_PIXELS = ("CHECKBOX", "SIGNATURE")

#: Clustering heuristics have no per-element probability model; these are the
#: engine's honest fixed priors. Table confidence comes from tables.py instead
#: (0.95 ruling-confirmed / 0.75 whitespace-only).
PARAGRAPH_CONFIDENCE = 0.9
HEADER_CONFIDENCE = 0.9


@dataclass(frozen=True)
class PageSpans:
    """One page of the /v1/layout request, already validated and canonical."""

    page_index: int
    width_pt: float
    height_pt: float
    spans: list[LayoutSpan]


class ClusteringLayoutEngine:
    detector = "clustering"
    detector_version = __version__

    def analyze_page(
        self,
        page: PageSpans,
        rulings: Rulings | None = None,
        raster: PageRaster | None = None,
    ) -> dict:
        lines = build_lines(page.spans)
        tables, remaining = detect_tables(lines, rulings)
        pairs, unpaired = detect_pairs(remaining)
        unpaired_ids = {id(line) for line in unpaired}

        headers = [
            line for line in detect_headers(remaining, page.spans) if id(line) in unpaired_ids
        ]
        header_ids = {id(line) for line in headers}
        body = [line for line in unpaired if id(line) not in header_ids]

        top_level = list(tables) + list(pairs)
        top_level += [
            Element(
                element_type="HEADER",
                box=line.box,
                spans=line.spans,
                confidence=HEADER_CONFIDENCE,
            )
            for line in headers
        ]
        top_level += [
            Element(
                element_type="PARAGRAPH",
                box=union_box([line.box for line in group]),
                spans=tuple(span for line in group for span in line.spans),
                confidence=PARAGRAPH_CONFIDENCE,
            )
            for group in group_paragraphs(body)
        ]

        ordered = order_elements(top_level, page.width_pt, page.height_pt)
        ordered += self._detections(page, raster)
        return {
            "pageIndex": page.page_index,
            "elements": self._serialize(ordered),
            "notImplemented": self._not_implemented(raster),
        }

    @staticmethod
    def _detections(page: PageSpans, raster: PageRaster | None) -> list[Element]:
        """Pixel detections ride BEHIND the clustered reading order: reading
        order is a property of the text flow, and detections are addressed
        by anchor proximity (CHECKBOX_STATE) or window intersection
        (SIGNATURE_PRESENCE), never by ordinal. The signature detector gets
        the page's span boxes: ink under them is machine text, not
        handwriting."""
        if raster is None:
            return []
        detections = detect_checkboxes(
            raster.image, raster.dpi, raster.rotation, page.width_pt, page.height_pt
        )
        detections += detect_signatures(
            raster.image,
            raster.dpi,
            raster.rotation,
            page.width_pt,
            page.height_pt,
            [span.box for span in page.spans],
        )
        detections.sort(key=lambda element: (element.box.y, element.box.x))
        return detections

    @staticmethod
    def _not_implemented(raster: PageRaster | None) -> list[str]:
        """No pixels = did not look. With pixels, implemented detectors leave
        the list; an empty result then really means 'looked, found none'."""
        if raster is None:
            return list(NOT_IMPLEMENTED)
        return [t for t in NOT_IMPLEMENTED if t not in IMPLEMENTED_WITH_PIXELS]

    def _serialize(self, ordered: list[Element]) -> list[dict]:
        """Depth-first id/ordinal assignment: parent, then children in order."""
        payloads: list[dict] = []

        def emit(element: Element, parent_id: str | None) -> None:
            element_id = f"e{len(payloads)}"
            payloads.append(
                {
                    "elementId": element_id,
                    "parentElementId": parent_id,
                    "elementType": element.element_type,
                    "ordinal": len(payloads),
                    "x": element.box.x,
                    "y": element.box.y,
                    "width": element.box.width,
                    "height": element.box.height,
                    "confidence": element.confidence,
                    "attributes": dict(element.attributes),
                    "spanOrdinals": [span.ordinal for span in element.spans],
                    "detector": element.detector or self.detector,
                    "detectorVersion": element.detector_version or self.detector_version,
                }
            )
            for child in element.children:
                emit(child, element_id)

        for element in ordered:
            emit(element, None)
        return payloads
