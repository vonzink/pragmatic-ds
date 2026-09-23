"""POST /v1/render over an IMAGE source.

The defect this suite exists for: four borrower paystubs arrived as JPEG photos,
ingestion accepted them (MimeSniffer recognises image/jpeg), and RENDERING died
CORRUPT_PDF three times with zero pages persisted. Nothing downstream ever ran.

The design answer is that there is nothing to *render* for an image — the image
already IS the page raster. /v1/render therefore transcodes it to the contract's
PNG without resampling and derives the canonical page box from the pixel count.

Every expected number here is hand-computed from the nominal-DPI rule, never read
back out of the implementation. The fixture is 1224x1584 px, which is US Letter at
exactly 144 DPI — so `round(1584 * 72 / 792) = 144` and the page box must come out
612.0 x 792.0 pt. If the rule is ever wrong, these numbers say so out loud.
"""

import io
import json

from image_fixtures import heic_bytes
from PIL import Image

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def metadata_of(mixed_parts, response) -> dict:
    return json.loads(mixed_parts(response)[0]["content"])


class TestTheReportedDefect:
    def test_a_jpeg_photo_renders_one_page_instead_of_failing_corrupt_pdf(
        self, client, render_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")
        assert jpeg[:3] == b"\xff\xd8\xff", "fixture must really be a JPEG"

        response = render_of(client, jpeg)

        assert response.status_code == 200, response.text
        pages = metadata_of(mixed_parts, response)["pages"]
        assert len(pages) == 1

    def test_an_iphone_heic_renders_one_page(self, client, render_of, mixed_parts):
        # The same defect one format over: HEIC is what an iPhone writes by default,
        # so a borrower who photographs a paystub without changing a camera setting
        # sends one of these. It is the only accepted format ingestion cannot decode
        # (no JVM HEIF reader exists), which makes this route its first real gate.
        heic = heic_bytes()
        assert heic[4:12] == b"ftypheic", "fixture must really be a HEIC"

        response = render_of(client, heic)

        assert response.status_code == 200, response.text
        pages = metadata_of(mixed_parts, response)["pages"]
        assert len(pages) == 1
        assert (pages[0]["widthPt"], pages[0]["heightPt"]) == (612.0, 792.0)

    def test_a_png_screenshot_renders_one_page(
        self, client, render_of, paystub_image, mixed_parts
    ):
        png, _truth = paystub_image("PNG")

        response = render_of(client, png)

        assert response.status_code == 200, response.text
        assert len(metadata_of(mixed_parts, response)["pages"]) == 1


class TestPageGeometry:
    def test_pixels_are_the_uploaded_image_pixels_untouched(
        self, client, render_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        page = metadata_of(mixed_parts, render_of(client, jpeg))["pages"][0]

        # No resampling: the raster IS the upload, so its pixel count survives.
        assert (page["widthPx"], page["heightPx"]) == (1224, 1584)

    def test_page_box_is_letter_sized_for_a_letter_shaped_image(
        self, client, render_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        page = metadata_of(mixed_parts, render_of(client, jpeg))["pages"][0]

        # 1584 px long edge / 792 pt nominal page => 144 DPI; 1224 / 2 = 612 pt.
        assert page["dpi"] == 144
        assert page["widthPt"] == 612.0
        assert page["heightPt"] == 792.0

    def test_an_image_page_is_never_rotated(
        self, client, render_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        page = metadata_of(mixed_parts, render_of(client, jpeg))["pages"][0]

        # There is no /Rotate on an image, and EXIF orientation is baked into the
        # pixels at decode, so the raster is ALWAYS already the rotation-0 frame.
        assert page["rotation"] == 0

    def test_the_requested_dpi_cannot_invent_resolution_an_image_does_not_have(
        self, client, render_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        page = metadata_of(mixed_parts, render_of(client, jpeg, {"dpi": 600}))["pages"][0]

        # A PDF re-renders at 600; an image has exactly the pixels it has, and
        # upsampling them would be a fabricated raster. The reported DPI stays the
        # honest one, which is what OCR converts its pixel boxes with.
        assert page["dpi"] == 144
        assert (page["widthPx"], page["heightPx"]) == (1224, 1584)


class TestPngPayload:
    def test_the_page_part_is_a_real_png_of_the_uploaded_pixels(
        self, client, render_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        parts = mixed_parts(render_of(client, jpeg))

        assert parts[1]["name"] == "page-0"
        assert parts[1]["contentType"] == "image/png"
        assert parts[1]["content"].startswith(PNG_MAGIC)
        decoded = Image.open(io.BytesIO(parts[1]["content"]))
        assert decoded.size == (1224, 1584)


class TestMultiFrameTiff:
    def test_a_two_frame_tiff_renders_two_pages(
        self, client, render_of, multiframe_tiff, mixed_parts
    ):
        # Silently dropping frames 1..n would be a data loss no error ever reports.
        pages = metadata_of(mixed_parts, render_of(client, multiframe_tiff()))["pages"]

        assert [page["pageIndex"] for page in pages] == [0, 1]

    def test_page_selection_works_on_an_image_document(
        self, client, render_of, multiframe_tiff, mixed_parts
    ):
        response = render_of(client, multiframe_tiff(), {"pages": [1]})

        pages = metadata_of(mixed_parts, response)["pages"]
        assert [page["pageIndex"] for page in pages] == [1]

    def test_page_beyond_the_frame_count_is_page_out_of_range(
        self, client, render_of, multiframe_tiff
    ):
        response = render_of(client, multiframe_tiff(), {"pages": [7]})

        assert response.status_code == 400
        assert response.json()["error"] == "PAGE_OUT_OF_RANGE"


class TestErrorHonesty:
    def test_an_undecodable_jpeg_is_corrupt_image_never_corrupt_pdf(self, client, render_of):
        # JPEG magic bytes, then garbage — the shape of a truncated phone upload.
        truncated = b"\xff\xd8\xff\xe0" + b"\x00" * 64

        response = render_of(client, truncated)

        assert response.status_code == 400
        assert response.json()["error"] == "CORRUPT_IMAGE"

    def test_a_truncated_upload_is_refused_here_even_though_ingest_accepted_it(
        self, client, render_of, paystub_image
    ):
        # The worker is the STRICT decode gate (ImageProbe's javadoc explains why
        # ingestion is the lenient one): Pillow refuses an incomplete scan outright.
        # The point is that it refuses it BY NAME — a damaged photo must not reach
        # OCR and become a document of confidently-wrong numbers.
        jpeg, _truth = paystub_image("JPEG")

        response = render_of(client, jpeg[: len(jpeg) // 4])

        assert response.status_code == 400
        assert response.json()["error"] == "CORRUPT_IMAGE"

    def test_bytes_that_are_neither_pdf_nor_image_stay_corrupt_pdf(self, client, render_of):
        # Unchanged behaviour: only the three magics ingestion accepts as images
        # take the image path, so garbage still reports the PDF failure it always did.
        response = render_of(client, b"not a document at all")

        assert response.status_code == 400
        assert response.json()["error"] == "CORRUPT_PDF"
