"""POST /v1/layout with an IMAGE as the optional `file` part.

PARSING always attaches the original bytes (WorkerParserAdapter). Two things read
that part, and they answer the image question differently:

  * rulings.py opens it with pdfplumber to CONFIRM ruled tables on native ink. An
    image has no vector ink at all, so the honest answer is "no rulings" — the same
    degrade-to-nothing a /Rotate page already gets, not a failure.
  * raster.py renders pages for the pixel detectors. An image needs no rendering;
    it IS the raster, so the detectors get real pixels and the page keeps its
    pixel path instead of retiring the detectors as unavailable.
"""


def one_page_request(width_pt: float, height_pt: float, page_index: int = 0) -> dict:
    return {
        "pages": [
            {
                "pageIndex": page_index,
                "widthPt": width_pt,
                "heightPt": height_pt,
                "spans": [
                    {"ordinal": 0, "text": "Gross", "x": 30.0, "y": 255.0, "width": 34.0, "height": 12.0},
                    {"ordinal": 1, "text": "Pay", "x": 68.0, "y": 255.0, "width": 22.0, "height": 12.0},
                ],
            }
        ]
    }


class TestImageAttachment:
    def test_an_image_file_part_does_not_fail_the_layout(
        self, client, layout_of, paystub_image
    ):
        jpeg, _truth = paystub_image("JPEG")

        response = layout_of(client, jpeg, one_page_request(612.0, 792.0))

        assert response.status_code == 200, response.text
        assert [page["pageIndex"] for page in response.json()["pages"]] == [0]

    def test_the_pixel_detectors_still_have_pixels_for_an_image_page(
        self, client, layout_of, paystub_image
    ):
        jpeg, _truth = paystub_image("JPEG")

        page = layout_of(client, jpeg, one_page_request(612.0, 792.0)).json()["pages"][0]

        # `notImplemented` is the per-page "the pixel path could not look" signal.
        # An image page has a raster by definition, so nothing may be retired there.
        assert page["notImplemented"] == []

    def test_an_undecodable_image_attachment_is_corrupt_image(self, client, layout_of):
        response = layout_of(
            client, b"\xff\xd8\xff\xe0" + b"\x00" * 32, one_page_request(612.0, 792.0)
        )

        assert response.status_code == 400
        assert response.json()["error"] == "CORRUPT_IMAGE"
