"""Shared layout value types. Boxes are canonical (PDF points, top-left,
rotation-0, 0.1pt) end to end — layout neither converts nor sees pixels."""

from dataclasses import dataclass, field
from statistics import median

from pragmaticds_docengine_worker.geometry import Box


@dataclass(frozen=True)
class LayoutSpan:
    """One caller-supplied span. `ordinal` is the caller's identifier — element
    membership refers to it verbatim. fontSize/fontName are None for OCR spans."""

    ordinal: int
    text: str
    box: Box
    font_size: float | None = None
    font_name: str | None = None

    @property
    def size(self) -> float:
        """Font-size proxy: real fontSize when present, box height for OCR spans."""
        return self.font_size if self.font_size is not None else self.box.height

    @property
    def bold(self) -> bool:
        return self.font_name is not None and "bold" in self.font_name.lower()


@dataclass(frozen=True)
class Line:
    """A visual line fragment: one y-band's spans between wide horizontal gaps,
    left-to-right. Fragments of the same band share `band` — a table row is one
    band whose fragments are its cells."""

    spans: tuple[LayoutSpan, ...]
    band: int

    @property
    def box(self) -> Box:
        x0 = min(s.box.x for s in self.spans)
        y0 = min(s.box.y for s in self.spans)
        x1 = max(s.box.x + s.box.width for s in self.spans)
        y1 = max(s.box.y + s.box.height for s in self.spans)
        return Box(x0, y0, x1 - x0, y1 - y0)

    @property
    def left(self) -> float:
        return min(s.box.x for s in self.spans)

    @property
    def top(self) -> float:
        return min(s.box.y for s in self.spans)

    @property
    def right(self) -> float:
        return max(s.box.x + s.box.width for s in self.spans)

    @property
    def bottom(self) -> float:
        return max(s.box.y + s.box.height for s in self.spans)

    @property
    def size(self) -> float:
        """Median span size proxy — the line's font-size stand-in."""
        return median(s.size for s in self.spans)

    @property
    def bold(self) -> bool:
        """A line is bold only when EVERY span says so — one bold word inside a
        body line must not promote the line to a header."""
        return all(s.bold for s in self.spans)


def union_box(boxes: list[Box]) -> Box:
    x0 = min(b.x for b in boxes)
    y0 = min(b.y for b in boxes)
    x1 = max(b.x + b.width for b in boxes)
    y1 = max(b.y + b.height for b in boxes)
    return Box(x0, y0, x1 - x0, y1 - y0)


@dataclass(frozen=True)
class Rulings:
    """Axis-aligned drawn segments from the OPTIONAL PDF part, canonical space.
    verticals: (x, top, bottom); horizontals: (y, x0, x1). They only ever CONFIRM
    a detected grid — never contribute geometry of their own."""

    verticals: tuple[tuple[float, float, float], ...] = ()
    horizontals: tuple[tuple[float, float, float], ...] = ()


@dataclass
class Element:
    """One layout element before wire serialisation. Children link by object;
    ids/ordinals are assigned once, in reading order, by the engine.

    `detector`/`detector_version` are None for clustered elements — the engine
    stamps its class-level identity at serialisation. Pixel detectors (Spec 3)
    set both, so a CHECKBOX carries "checkbox-cv" while the paragraphs around
    it stay "clustering"."""

    element_type: str
    box: Box
    spans: tuple[LayoutSpan, ...]
    confidence: float
    attributes: dict = field(default_factory=dict)
    children: list["Element"] = field(default_factory=list)
    detector: str | None = None
    detector_version: str | None = None
