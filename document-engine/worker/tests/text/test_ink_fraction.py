"""/v1/text inkFraction — Phase 3 (WORKER_CONTRACT.md /v1/text): EVERY page object
carries "inkFraction" (float 0..1, or null when the page could not be rendered for
the check). Phase 2 computed ink only for wordless pages, where it split SCANNED
from NONE; Phase 3 promotes it to a universal page signal (the Java side persists
it as page.blank_score and derives is_blank)."""

class TestInkFractionOnEveryPage:
    def test_native_paystub_ink_fraction_is_present_and_plausible(self, client, fixture_bytes, text_of):
        """A worded page must now carry the signal too — sparse text, so well under 0.2
        but strictly above zero (there IS ink on the page)."""
        response = text_of(client, fixture_bytes("native_paystub.pdf"))

        assert response.status_code == 200
        page = response.json()["pages"][0]
        assert "inkFraction" in page
        assert 0.0 < page["inkFraction"] < 0.2

    def test_blank_page_ink_fraction_is_zero(self, client, fixture_bytes, text_of):
        response = text_of(client, fixture_bytes("blank_page.pdf"))

        assert response.status_code == 200
        page = response.json()["pages"][0]
        assert page["inkFraction"] == 0.0

    def test_every_page_of_a_multipage_file_carries_the_field(self, client, fixture_bytes, text_of):
        response = text_of(client, fixture_bytes("native_multipage.pdf"))

        assert response.status_code == 200
        pages = response.json()["pages"]
        assert len(pages) == 3
        for page in pages:
            assert isinstance(page["inkFraction"], float)
            assert 0.0 <= page["inkFraction"] <= 1.0


def test_one_unrenderable_page_does_not_null_the_signal_for_every_page(monkeypatch):
    from pathlib import Path

    """Phase 3 review finding (confirmed): a single blanket except around the render
    loop threw away fractions already computed — one corrupt image XObject on page 17
    nulled blank_score for all 30 pages, and a genuinely blank page in the same file
    flipped SCANNED (ink unknown) instead of NONE."""
    import pragmaticds_docengine_worker.text as text_module
    import pypdfium2 as pdfium

    real_document = pdfium.PdfDocument

    class Exploding:
        def __init__(self, pdf_bytes):
            self._doc = real_document(pdf_bytes)

        def __len__(self):
            return len(self._doc)

        def __getitem__(self, index):
            page = self._doc[index]
            if index == 1:

                class Boom:
                    def render(self, **kwargs):
                        raise RuntimeError("corrupt image XObject")

                    def get_size(self):
                        return page.get_size()

                return Boom()
            return page

        def close(self):
            self._doc.close()

    monkeypatch.setattr(text_module.pdfium, "PdfDocument", Exploding)

    fixtures = Path(__file__).resolve().parents[3] / "fixtures"
    pdf = (fixtures / "native_multipage.pdf").read_bytes()
    fractions = text_module._ink_fractions(pdf, [0, 1, 2])

    assert fractions.get(0) is not None, "page 0 signal lost to page 1's failure"
    assert 1 not in fractions or fractions.get(1) is None
    assert fractions.get(2) is not None, "page 2 signal lost to page 1's failure"
