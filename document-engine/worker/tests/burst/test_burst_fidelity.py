"""A4 — the fidelity gate: re-parsing a burst reproduces the source pages exactly.

This is the property the whole design rests on. `GET /v1/documents/{id}/pdf`
promises "the paystub, as a file" with no loss, and every consumer that
re-ingests one must land on the same spans, the same boxes, and the same page
geometry the original package produced — otherwise a burst document's evidence
boxes would point at the wrong place and the parse-once guarantee would be a
lie for anything that round-trips.

It is asserted HERE rather than as a Java integration test on purpose. The
engine-side IT can only prove which bytes it shipped and which page sequence it
asked for (`DocumentPdfApiIT` does exactly that, against a mock worker); whether
those bytes SURVIVE is a property of the burst itself, and this is where the
real parser lives. Running it as a worker test also means it runs on every
commit without Docker or a database.

The comparison is always the SAME extractor over the SAME committed fixture, so
any difference is attributable to the burst and to nothing else. Text, box
geometry to the 0.1pt contract, reading-order ordinals, page dimensions and
rotation are all compared — a burst that preserved words but shifted a box by a
point would pass a text-only check and break every highlight downstream.
"""

import io

import pypdf
import pytest

from pragmaticds_docengine_worker.burst import burst_sources
from pragmaticds_docengine_worker.text import extract_text


def spans_of(pdf_bytes: bytes, page_index: int) -> list[dict]:
    """Every span of one page, as the wire payload the engine persists."""
    pages = extract_text(pdf_bytes, [page_index])
    assert len(pages) == 1
    return [span.payload() for span in pages[0].spans]


def geometry_of(pdf_bytes: bytes, page_index: int) -> tuple:
    page = extract_text(pdf_bytes, [page_index])[0]
    return (page.width_pt, page.height_pt, page.rotation, page.verdict)


class TestSinglePageBurstFidelity:
    def test_every_span_survives_verbatim(self, fixture_bytes):
        source = fixture_bytes("paystub_complete.pdf")
        burst = burst_sources({"file-0": source}, [("file-0", 0)])

        original = spans_of(source, 0)
        reparsed = spans_of(burst, 0)

        assert reparsed == original
        # The assertion above is only meaningful on a page that HAS text.
        assert len(original) > 10

    def test_page_geometry_and_verdict_survive(self, fixture_bytes):
        source = fixture_bytes("paystub_complete.pdf")
        burst = burst_sources({"file-0": source}, [("file-0", 0)])
        assert geometry_of(burst, 0) == geometry_of(source, 0)


class TestMultiPageBurstFidelity:
    def test_a_page_subset_keeps_each_pages_own_content(self, fixture_bytes):
        """The reordering case: burst page 0 is source page 1, and its spans must
        be source page 1's — not page 0's, and not a blend."""
        source = fixture_bytes("paystub_twopage.pdf")
        assert len(pypdf.PdfReader(io.BytesIO(source)).pages) == 2

        burst = burst_sources({"file-0": source}, [("file-0", 1), ("file-0", 0)])

        assert spans_of(burst, 0) == spans_of(source, 1)
        assert spans_of(burst, 1) == spans_of(source, 0)
        # And the two pages are genuinely different, so the check above cannot
        # pass by the pages being interchangeable.
        assert spans_of(source, 0) != spans_of(source, 1)

    def test_selecting_one_page_of_many_drops_nothing_from_it(self, fixture_bytes):
        source = fixture_bytes("combined_package.pdf")
        page_count = len(pypdf.PdfReader(io.BytesIO(source)).pages)
        assert page_count >= 3

        # A middle page — the case where an off-by-one would still produce a
        # plausible-looking one-page PDF.
        burst = burst_sources({"file-0": source}, [("file-0", 1)])
        assert spans_of(burst, 0) == spans_of(source, 1)
        assert len(pypdf.PdfReader(io.BytesIO(burst)).pages) == 1


class TestCrossSourceFidelity:
    def test_pages_from_two_sources_each_keep_their_own_content(self, fixture_bytes):
        """A logical document may span source files (a page from each of two
        uploads). Each burst page must match ITS OWN source, which is the check
        that a concatenation bug — carrying the wrong file's page — would fail."""
        source_a = fixture_bytes("paystub_complete.pdf")
        source_b = fixture_bytes("bank_statement.pdf")

        burst = burst_sources(
            {"file-0": source_a, "file-1": source_b}, [("file-1", 0), ("file-0", 0)]
        )

        assert len(pypdf.PdfReader(io.BytesIO(burst)).pages) == 2
        assert spans_of(burst, 0) == spans_of(source_b, 0)
        assert spans_of(burst, 1) == spans_of(source_a, 0)


class TestRotationFidelity:
    @pytest.mark.parametrize("degrees", [90, 180, 270])
    def test_declared_rotation_and_canonical_boxes_survive(self, fixture_bytes, degrees):
        """A page-subset copy must carry /Rotate with the page. If it were dropped,
        the spans would still extract but every box would be in the wrong frame —
        the exact failure the coordinate contract exists to prevent, and one that
        an unrotated fixture cannot catch."""
        source = fixture_bytes(f"paystub_complete_rot{degrees}.pdf")
        burst = burst_sources({"file-0": source}, [("file-0", 0)])

        assert geometry_of(burst, 0) == geometry_of(source, 0)
        assert extract_text(burst, [0])[0].rotation == degrees
        assert spans_of(burst, 0) == spans_of(source, 0)
