"""POST /v1/text over an IMAGE source.

An image has no text layer — there are no glyph operators to extract, only pixels.
That is precisely the SCANNED verdict, and SCANNED is what sends the page to OCR
(WorkerParserAdapter.OCR_ELIGIBLE). The other half of the contract still has to
hold: `inkFraction` is a real measurement (the SCANNED/NONE splitter for wordless
pages), and the page dims must agree with what /v1/render said about the same file
or every downstream box lands in a different frame from the raster it describes.
"""


class TestVerdict:
    def test_a_photographed_paystub_is_scanned_so_ocr_will_run(
        self, client, text_of, paystub_image
    ):
        jpeg, _truth = paystub_image("JPEG")

        response = text_of(client, jpeg)

        assert response.status_code == 200, response.text
        page = response.json()["pages"][0]
        assert page["verdict"] == "SCANNED"

    def test_an_image_never_reports_native_spans(self, client, text_of, paystub_image):
        jpeg, _truth = paystub_image("JPEG")

        page = text_of(client, jpeg).json()["pages"][0]

        assert page["spans"] == []
        # uncoveredRegions is a MIXED-page concept: a whole-page image is OCR'd whole.
        assert "uncoveredRegions" not in page

    def test_a_blank_image_is_none_not_scanned(self, client, text_of, blank_image):
        page = text_of(client, blank_image("PNG")).json()["pages"][0]

        # A white photo is a blank page, not a page whose text OCR failed to find —
        # same INK_FLOOR split a wordless PDF page gets.
        assert page["verdict"] == "NONE"

    def test_ink_fraction_is_measured_not_fabricated(self, client, text_of, paystub_image, blank_image):
        inked = text_of(client, paystub_image("JPEG")[0]).json()["pages"][0]
        blank = text_of(client, blank_image("PNG")).json()["pages"][0]

        assert inked["inkFraction"] > blank["inkFraction"]
        assert blank["inkFraction"] < 0.005  # INK_FLOOR


class TestGeometryAgreesWithRender:
    def test_the_page_box_matches_what_render_reported_for_the_same_bytes(
        self, client, text_of, render_of, paystub_image, mixed_parts
    ):
        import json

        jpeg, _truth = paystub_image("JPEG")

        rendered = json.loads(mixed_parts(render_of(client, jpeg))[0]["content"])["pages"][0]
        extracted = text_of(client, jpeg).json()["pages"][0]

        assert (extracted["widthPt"], extracted["heightPt"]) == (
            rendered["widthPt"],
            rendered["heightPt"],
        )
        assert extracted["rotation"] == rendered["rotation"] == 0


class TestMultiFrameAndErrors:
    def test_every_tiff_frame_gets_a_verdict(self, client, text_of, multiframe_tiff):
        pages = text_of(client, multiframe_tiff()).json()["pages"]

        assert [page["pageIndex"] for page in pages] == [0, 1]
        assert [page["verdict"] for page in pages] == ["SCANNED", "SCANNED"]

    def test_an_undecodable_image_is_corrupt_image_never_corrupt_pdf(self, client, text_of):
        response = text_of(client, b"\x89PNG\r\n\x1a\n" + b"\x00" * 40)

        assert response.status_code == 400
        assert response.json()["error"] == "CORRUPT_IMAGE"
