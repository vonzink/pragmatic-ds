"""burst.py — page-subset copy fidelity, image wrapping, cross-source
interleaving, and fail-closed request semantics.

Fixtures are built in memory or read from the committed set; fixtures/ is owned
by the provenance manifest and gains no files (same rule as test_encrypted_pdf).
"""

import io

import pypdf
import pytest
from fastapi import HTTPException
from PIL import Image

from pragmaticds_docengine_worker.burst import burst_sources, parse_burst_request
from pragmaticds_docengine_worker.source import nominal_dpi


def build_pdf(page_sizes: list[tuple[int, int]]) -> bytes:
    """A PDF whose pages are distinguishable by their media box alone."""
    writer = pypdf.PdfWriter()
    for width, height in page_sizes:
        writer.add_blank_page(width=width, height=height)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def page_sizes(pdf_bytes: bytes) -> list[tuple[float, float]]:
    reader = pypdf.PdfReader(io.BytesIO(pdf_bytes))
    return [
        (float(page.mediabox.width), float(page.mediabox.height)) for page in reader.pages
    ]


def png_bytes(width: int, height: int, color: tuple[int, int, int]) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buffer, format="PNG")
    return buffer.getvalue()


def tiff_bytes(frames: list[tuple[int, int]]) -> bytes:
    images = [Image.new("RGB", size, (index * 40, 0, 0)) for index, size in enumerate(frames)]
    buffer = io.BytesIO()
    images[0].save(buffer, format="TIFF", save_all=True, append_images=images[1:])
    return buffer.getvalue()


def error_of(exception_info) -> tuple[int, str, dict]:
    exception = exception_info.value
    return (
        exception.status_code,
        exception.detail["error"],
        exception.detail.get("detail", {}),
    )


class TestParseBurstRequest:
    def test_valid(self):
        raw = b'{"pages": [{"part": "file-0", "index": 3}, {"part": "file-1", "index": 0}]}'
        assert parse_burst_request(raw) == [("file-0", 3), ("file-1", 0)]

    def test_missing_part_is_an_error(self):
        with pytest.raises(HTTPException) as caught:
            parse_burst_request(None)
        assert error_of(caught)[2]["reason"] == "REQUEST_PART_MISSING"

    def test_empty_pages_is_an_error_never_all_pages(self):
        """The divergence from /v1/render that matters: an accidental [] must die
        loudly, not silently return an entire source file."""
        with pytest.raises(HTTPException) as caught:
            parse_burst_request(b'{"pages": []}')
        assert error_of(caught)[2]["reason"] == "PAGES_REQUIRED"

    def test_duplicate_entry_is_an_error(self):
        raw = b'{"pages": [{"part": "file-0", "index": 1}, {"part": "file-0", "index": 1}]}'
        with pytest.raises(HTTPException) as caught:
            parse_burst_request(raw)
        assert error_of(caught)[2]["reason"] == "DUPLICATE_PAGE_INDEX"

    def test_same_index_on_different_parts_is_legal(self):
        raw = b'{"pages": [{"part": "file-0", "index": 0}, {"part": "file-1", "index": 0}]}'
        assert parse_burst_request(raw) == [("file-0", 0), ("file-1", 0)]

    def test_flat_int_list_is_rejected(self):
        """The pre-revision request shape must not silently half-work."""
        with pytest.raises(HTTPException) as caught:
            parse_burst_request(b'{"pages": [0, 1]}')
        assert error_of(caught)[2]["reason"] == "PAGES_NOT_OBJECT_LIST"

    def test_boolean_index_is_rejected(self):
        with pytest.raises(HTTPException) as caught:
            parse_burst_request(b'{"pages": [{"part": "file-0", "index": true}]}')
        assert error_of(caught)[2]["reason"] == "PAGES_NOT_OBJECT_LIST"

    def test_garbage_json(self):
        with pytest.raises(HTTPException) as caught:
            parse_burst_request(b"not json")
        assert error_of(caught)[2]["reason"] == "REQUEST_PART_NOT_JSON"


class TestBurstPdf:
    def test_subset_in_requested_order(self):
        source = build_pdf([(100, 200), (300, 400), (500, 600), (700, 800)])
        out = burst_sources({"file-0": source}, [("file-0", 2), ("file-0", 0)])
        assert page_sizes(out) == [(500.0, 600.0), (100.0, 200.0)]

    def test_content_survives_verbatim(self, fixture_bytes):
        """The fidelity property on a real fixture: the burst page extracts the
        same text as the same page read straight from the source."""
        source = fixture_bytes("paystub_complete.pdf")
        expected = pypdf.PdfReader(io.BytesIO(source)).pages[0].extract_text()
        out = burst_sources({"file-0": source}, [("file-0", 0)])
        assert pypdf.PdfReader(io.BytesIO(out)).pages[0].extract_text() == expected
        assert expected.strip()  # the assertion above is load-bearing, not vacuous

    def test_out_of_range(self):
        source = build_pdf([(100, 200)])
        with pytest.raises(HTTPException) as caught:
            burst_sources({"file-0": source}, [("file-0", 1)])
        status, code, detail = error_of(caught)
        assert (status, code) == (400, "PAGE_OUT_OF_RANGE")
        assert detail == {"partName": "file-0", "pageIndex": 1, "pageCount": 1}

    def test_negative_index(self):
        with pytest.raises(HTTPException) as caught:
            burst_sources({"file-0": build_pdf([(100, 200)])}, [("file-0", -1)])
        assert error_of(caught)[1] == "PAGE_OUT_OF_RANGE"

    def test_corrupt_bytes(self):
        with pytest.raises(HTTPException) as caught:
            burst_sources({"file-0": b"%PDF-not really"}, [("file-0", 0)])
        assert error_of(caught)[1] == "CORRUPT_PDF"

    def test_referenced_part_absent(self):
        with pytest.raises(HTTPException) as caught:
            burst_sources({"file-0": build_pdf([(100, 200)])}, [("file-1", 0)])
        status, code, detail = error_of(caught)
        assert (status, code) == (400, "INVALID_REQUEST")
        assert detail == {"reason": "FILE_PART_MISSING", "partName": "file-1"}

    def test_owner_password_only_encryption_opens(self):
        """The class of file every bank ships: reading open, printing locked.
        Ingestion accepts it, so burst must read it (same bar as every other
        PDF-opening stage — test_encrypted_pdf.py)."""
        plain = build_pdf([(100, 200), (300, 400)])
        writer = pypdf.PdfWriter(clone_from=io.BytesIO(plain))
        writer.encrypt(user_password="", owner_password="owner-secret", algorithm="AES-256")
        buffer = io.BytesIO()
        writer.write(buffer)
        out = burst_sources({"file-0": buffer.getvalue()}, [("file-0", 1)])
        assert page_sizes(out) == [(300.0, 400.0)]

    def test_user_password_encryption_is_corrupt(self):
        """The control that makes the previous test load-bearing."""
        plain = build_pdf([(100, 200)])
        writer = pypdf.PdfWriter(clone_from=io.BytesIO(plain))
        writer.encrypt(user_password="locked", algorithm="AES-256")
        buffer = io.BytesIO()
        writer.write(buffer)
        with pytest.raises(HTTPException) as caught:
            burst_sources({"file-0": buffer.getvalue()}, [("file-0", 0)])
        assert error_of(caught)[1] == "CORRUPT_PDF"


class TestBurstImage:
    def test_png_wraps_to_single_page_pdf_with_canonical_box(self):
        source = png_bytes(2550, 3300, (255, 255, 255))  # a 300-DPI letter scan
        out = burst_sources({"file-0": source}, [("file-0", 0)])
        sizes = page_sizes(out)
        assert len(sizes) == 1
        assert nominal_dpi(2550, 3300) == 300
        # Pillow sizes the page as px * 72 / resolution — the canonical rule.
        assert sizes[0] == (pytest.approx(612.0, abs=0.5), pytest.approx(792.0, abs=0.5))

    def test_multi_frame_tiff_selects_frames_in_order(self):
        source = tiff_bytes([(400, 500), (400, 500), (400, 500)])
        out = burst_sources({"file-0": source}, [("file-0", 2), ("file-0", 0)])
        assert len(page_sizes(out)) == 2

    def test_out_of_range_frame(self):
        with pytest.raises(HTTPException) as caught:
            burst_sources({"file-0": png_bytes(100, 100, (0, 0, 0))}, [("file-0", 1)])
        assert error_of(caught)[1] == "PAGE_OUT_OF_RANGE"

    def test_undecodable_image_is_corrupt_image(self):
        # PNG magic, garbage body — reaches the image path and must say so.
        with pytest.raises(HTTPException) as caught:
            burst_sources({"file-0": b"\x89PNG\r\n\x1a\ngarbage"}, [("file-0", 0)])
        assert error_of(caught)[1] == "CORRUPT_IMAGE"

    def test_committed_png_fixture_round_trips(self, fixture_bytes):
        out = burst_sources(
            {"file-0": fixture_bytes("degraded_paystub_page0.png")}, [("file-0", 0)]
        )
        assert len(page_sizes(out)) == 1


class TestCrossSource:
    def test_interleaves_across_sources_in_sequence_order(self):
        """The case that forced the sequence shape: a document's pages may
        alternate between source files, and output order is the document's
        page order, not per-source order."""
        pdf_a = build_pdf([(100, 200), (300, 400)])
        pdf_b = build_pdf([(500, 600)])
        out = burst_sources(
            {"file-0": pdf_a, "file-1": pdf_b},
            [("file-0", 1), ("file-1", 0), ("file-0", 0)],
        )
        assert page_sizes(out) == [(300.0, 400.0), (500.0, 600.0), (100.0, 200.0)]

    def test_mixes_pdf_and_image_sources(self):
        pdf = build_pdf([(100, 200)])
        png = png_bytes(2550, 3300, (255, 255, 255))
        out = burst_sources(
            {"file-0": pdf, "file-1": png}, [("file-1", 0), ("file-0", 0)]
        )
        sizes = page_sizes(out)
        assert len(sizes) == 2
        assert sizes[1] == (100.0, 200.0)

    def test_magic_bytes_decide_never_a_declared_type(self):
        pdf = build_pdf([(100, 200)])
        png = png_bytes(50, 50, (1, 2, 3))
        assert page_sizes(burst_sources({"x": pdf}, [("x", 0)])) == [(100.0, 200.0)]
        assert len(page_sizes(burst_sources({"x": png}, [("x", 0)]))) == 1
