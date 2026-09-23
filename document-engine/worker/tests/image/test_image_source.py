"""The image half of `source.py` — the one place a file becomes pages.

Every expected value is hand-computed from the rule in the module docstring, in
the spirit of test_geometry.py: a bug in a coordinate derivation produces
plausible-looking numbers and raises nothing, so the expectations must come from
arithmetic done here, never from running the code and writing down what it said.
"""

import io

import numpy as np
import pytest
from image_fixtures import (
    HEIC_RECT_PX,
    PAGE_PX,
    heic_bytes,
    heic_multi_frame_bytes,
    heif_ftyp_bytes,
)
from PIL import Image, ImageDraw, ImageFont

from pragmaticds_docengine_worker.source import (
    MAX_IMAGE_DPI,
    MIN_IMAGE_DPI,
    NOMINAL_PAGE_LONG_EDGE_PT,
    ImageDocument,
    looks_like_image,
    nominal_dpi,
    open_image_document,
)


class TestMagicSniffing:
    @pytest.mark.parametrize(
        "magic",
        [
            b"\x89PNG\r\n\x1a\n" + b"rest",
            b"\xff\xd8\xff\xe0" + b"rest",
            b"II\x2a\x00" + b"rest",
            b"MM\x00\x2a" + b"rest",
        ],
    )
    def test_the_prefix_magic_formats_ingestion_accepts_are_image_sources(self, magic):
        assert looks_like_image(magic)

    @pytest.mark.parametrize("brand", [b"heic", b"heix", b"hevc", b"mif1"])
    def test_the_heif_brands_ingestion_accepts_are_image_sources(self, brand):
        # HEIC's signal is not a prefix: bytes 0..4 are a box length that varies, so
        # the brand at 8..12 is what identifies it.
        assert looks_like_image(heif_ftyp_bytes(brand))

    @pytest.mark.parametrize("brand", [b"avif", b"avis", b"mp42", b"isom"])
    def test_an_ftyp_box_from_another_family_is_not_an_image_source(self, brand):
        # AVIF is ISO-BMFF too and lists `mif1` among its COMPATIBLE brands, so a
        # sniffer that scanned that list would divert an AVIF onto the image path —
        # where it would fail, because pi-heif bundles libde265 and no AVIF decoder.
        # The MAJOR brand alone decides, which keeps the accept set equal to the
        # decode set.
        assert not looks_like_image(heif_ftyp_bytes(brand))

    @pytest.mark.parametrize("bytes_", [b"%PDF-1.7\n...", b"", b"garbage", b"\x89PNGnope"])
    def test_everything_else_stays_on_the_pdf_path(self, bytes_):
        # Deliberately conservative: unrecognised bytes keep reporting CORRUPT_PDF,
        # exactly as before this seam existed. Only a real image magic diverts.
        assert not looks_like_image(bytes_)

    @pytest.mark.parametrize("bytes_", [b"\x00\x00\x00\x18ftyphe", b"\x00\x00\x00\x18ftyp", b"ftyp"])
    def test_a_truncated_ftyp_box_is_not_an_image_source(self, bytes_):
        # Twelve bytes are needed before a brand can be read at all. Guessing from
        # fewer would accept any file whose fifth byte happens to be 'f'.
        assert not looks_like_image(bytes_)


class TestNominalDpi:
    def test_a_letter_page_photographed_at_any_resolution_lands_on_letter(self):
        # long edge / 792 pt is the assumed scan resolution, so a full-page capture
        # comes back as a letter-ish canonical box whatever the camera's megapixels.
        assert nominal_dpi(2550, 3300) == 300  # 3300 / 11in
        assert nominal_dpi(1224, 1584) == 144
        assert nominal_dpi(612, 792) == 72

    def test_a_phone_photo_is_document_shaped_not_forty_inches_wide(self):
        dpi = nominal_dpi(3024, 4032)

        assert dpi == 367  # round(4032 * 72 / 792)
        assert round(3024 * 72.0 / dpi, 1) == 593.3
        assert round(4032 * 72.0 / dpi, 1) == 791.0

    def test_landscape_uses_the_long_edge_too(self):
        assert nominal_dpi(1584, 1224) == 144

    def test_the_derived_dpi_is_clamped_to_the_contract_range(self):
        assert nominal_dpi(40, 30) == MIN_IMAGE_DPI  # would be 4
        assert nominal_dpi(20000, 16000) == MAX_IMAGE_DPI  # would be 1818

    def test_the_nominal_page_is_us_letter_height(self):
        assert NOMINAL_PAGE_LONG_EDGE_PT == 792.0


def _png(size, mode="RGB") -> bytes:
    buffer = io.BytesIO()
    Image.new(mode, size, "white").save(buffer, format="PNG")
    return buffer.getvalue()


class TestImageDocument:
    def test_a_single_image_is_a_one_page_document(self):
        with open_image_document(_png((1224, 1584))) as document:
            assert len(document) == 1

    def test_page_geometry_is_derived_from_the_pixels(self):
        with open_image_document(_png((1224, 1584))) as document:
            page = document.page(0)

        assert page.page_index == 0
        assert page.dpi == 144
        assert (page.width_pt, page.height_pt) == (612.0, 792.0)
        assert page.rotation == 0
        assert page.image.size == (1224, 1584)

    def test_pixels_are_never_resampled(self):
        with open_image_document(_png((997, 1301))) as document:
            assert document.page(0).image.size == (997, 1301)

    def test_a_multi_frame_tiff_is_a_multi_page_document(self):
        frames = [Image.new("RGB", (600, 800), shade) for shade in ("white", "gray", "black")]
        buffer = io.BytesIO()
        frames[0].save(buffer, format="TIFF", save_all=True, append_images=frames[1:])

        with open_image_document(buffer.getvalue()) as document:
            assert len(document) == 3
            assert [document.page(i).page_index for i in range(3)] == [0, 1, 2]

    def test_an_animated_png_is_still_one_page(self):
        # Frames are a PAGE concept only for TIFF, the one image format lenders
        # genuinely use for multi-page scans. An APNG is one picture, not 30 pages.
        frames = [Image.new("RGB", (200, 260), shade) for shade in ("white", "black")]
        buffer = io.BytesIO()
        frames[0].save(buffer, format="PNG", save_all=True, append_images=frames[1:])

        with open_image_document(buffer.getvalue()) as document:
            assert len(document) == 1

    def test_an_iphone_heic_is_a_one_page_document_with_letter_geometry(self):
        # The default iPhone camera format. Same rule as every other raster: the long
        # edge is fitted to Letter's 792 pt, so 1584 px => 144 dpi => a 612 x 792 pt
        # page. Nothing about HEIC changes the geometry; it just has to decode.
        with open_image_document(heic_bytes()) as document:
            assert len(document) == 1
            page = document.page(0)

            assert page.image.size == PAGE_PX
            assert page.dpi == 144  # round(1584 * 72 / 792)
            assert (page.width_pt, page.height_pt) == (612.0, 792.0)
            assert page.rotation == 0

    def test_heic_pixels_survive_the_decode(self):
        # Every geometry assertion above would also pass on a blank or mid-grey
        # raster, which is exactly what a half-working decoder hands back. The
        # fixture's black rectangle is the proof that real pixels arrived.
        with open_image_document(heic_bytes()) as document:
            grey = np.asarray(document.page(0).image.convert("L"))

        left, top, right, bottom = HEIC_RECT_PX
        assert grey[top + 10 : bottom - 10, left + 10 : right - 10].max() <= 8, "rectangle lost"
        assert grey[900:1500, 700:1200].min() >= 247, "page should be white outside the rectangle"

    def test_a_multi_image_heic_burst_is_still_one_page(self):
        # A burst or Live Photo HEIC carries several coded images and Pillow reports
        # n_frames = 2 for this one. Frames are a PAGE concept for TIFF alone — the
        # format scanners and fax gateways actually use for multi-page documents.
        with open_image_document(heic_multi_frame_bytes()) as document:
            assert len(document) == 1

    def test_a_heic_header_with_no_image_behind_it_is_corrupt_image(self):
        # This is the case ingestion can no longer catch: the JVM has no HEIF reader,
        # so a truncated HEIC is accepted at upload and must fail HERE, by name.
        with pytest.raises(Exception) as raised:
            open_image_document(heif_ftyp_bytes(b"heic", trailing=b"\x00" * 64))

        assert raised.value.detail["error"] == "CORRUPT_IMAGE"

    def test_undecodable_bytes_raise_corrupt_image(self):
        with pytest.raises(Exception) as raised:
            open_image_document(b"\xff\xd8\xff\xe0" + b"\x00" * 32)

        assert raised.value.detail["error"] == "CORRUPT_IMAGE"

    def test_a_page_index_beyond_the_frames_is_rejected(self):
        with open_image_document(_png((300, 400))) as document:
            with pytest.raises(Exception) as raised:
                document.page(4)

        assert raised.value.detail["error"] == "PAGE_OUT_OF_RANGE"


class TestExifOrientation:
    def test_a_sideways_phone_photo_is_uprighted_at_decode(self):
        # EXIF orientation 6 = "rotate 90 CW for display". Every viewer the borrower
        # used showed it upright; if the worker decodes the stored pixels as-is, the
        # page is sideways and every evidence box is a quarter turn out.
        upright = Image.new("RGB", (400, 600), "white")
        ImageDraw.Draw(upright).text(
            (20, 20), "TOP LEFT", fill="black", font=ImageFont.load_default(size=28)
        )
        stored = upright.transpose(Image.Transpose.ROTATE_90)  # what the sensor wrote
        exif = stored.getexif()
        exif[274] = 6
        buffer = io.BytesIO()
        stored.save(buffer, format="JPEG", exif=exif, quality=92)

        with open_image_document(buffer.getvalue()) as document:
            page = document.page(0)

        assert page.image.size == (400, 600), "EXIF orientation must be applied at decode"
        assert page.rotation == 0, "orientation is baked into pixels, never reported as /Rotate"

    def test_a_heic_arrives_already_uprighted_by_its_decoder(self):
        # HEIC keeps orientation in the container (`irot`), not in EXIF, and libheif
        # applies it while decoding — then reports EXIF orientation 1 so that a second
        # transpose cannot double-rotate the page. `exif_transpose` is therefore a
        # deliberate no-op for HEIC, and the rotation-0 invariant holds by
        # construction rather than by correction. That is the fact this pins: if a
        # future decoder started handing back sideways pixels with orientation 6, the
        # geometry above would silently transpose and every evidence box would move.
        #
        # Coverage limit, stated rather than papered over: a HEIC that actually STORES
        # a rotation cannot be built here. The only encoder that can write one
        # normalises orientation on save, and it is GPLv2, so it cannot enter this
        # environment at all. One real sideways iPhone photo is worth a manual pass.
        #
        # Image.open resolving HEIF at all is itself part of the contract — it works
        # only because importing source.py registered the opener.
        raw = Image.open(io.BytesIO(heic_bytes()))
        raw.load()

        assert raw.getexif().get(274, 1) == 1, "decoder must normalise orientation itself"
        assert raw.size == PAGE_PX


class TestContextManager:
    def test_the_document_is_usable_as_a_context_manager(self):
        document = open_image_document(_png((300, 400)))
        with document as opened:
            assert isinstance(opened, ImageDocument)
