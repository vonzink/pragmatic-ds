"""Owner-password-only PDFs must parse, on every stage that opens PDF bytes.

Ingestion accepts these files because PDFBox already decrypted them with the empty user
password (see PdfProbe). That acceptance is only worth anything if the worker can read the
same bytes, so this suite proves it on the worker's OWN entry points rather than asserting it
from the Java side: pdfplumber/pdfminer in text.extract_text and layout.rulings.extract_rulings,
PDFium in render.render_pdf.

Owner-password-only encryption restricts printing/editing and leaves reading open — the class
of file every bank, payroll provider and closing agent ships. Both libraries handle it the way
viewers do: they try the empty user password and succeed.

The comparison baseline is always the SAME pypdf round-trip without encryption, so any
difference is attributable to encryption alone and never to the rewrite. The true-user-password
control at the bottom is what makes these assertions load-bearing: it proves the libraries do
fail when the content really is locked, so "identical output" is a fact about owner-only
encryption and not a test that cannot fail.

Fixtures are built in memory. fixtures/ is owned by the provenance manifest and gains no files.
"""

import io
import json
from pathlib import Path

import pypdf
import pytest

from pragmaticds_docengine_worker.layout.rulings import extract_rulings
from pragmaticds_docengine_worker.render import render_pdf
from pragmaticds_docengine_worker.text import extract_text

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


def fixture_bytes(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def fixture_truth(name: str) -> dict:
    return json.loads((FIXTURES / "truth" / name).read_text())

#: The revisions a real lender document may carry. RC4-128 is what older loan-origination
#: systems emit; AES-256 is what current ones do. All three must read.
ALGORITHMS = ["RC4-128", "AES-128", "AES-256"]


def _rewrite(pdf_bytes: bytes, **encrypt_kwargs) -> bytes:
    """A pypdf round-trip, optionally encrypted. No kwargs => the plaintext baseline."""
    writer = pypdf.PdfWriter()
    writer.append(pypdf.PdfReader(io.BytesIO(pdf_bytes)))
    if encrypt_kwargs:
        writer.encrypt(**encrypt_kwargs)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def plaintext(pdf_bytes: bytes) -> bytes:
    return _rewrite(pdf_bytes)


def owner_password_only(pdf_bytes: bytes, algorithm: str = "AES-256") -> bytes:
    """Owner password set, user password EMPTY — opens with no prompt in any viewer."""
    return _rewrite(
        pdf_bytes, user_password="", owner_password="owner-secret", algorithm=algorithm
    )


def user_password_protected(pdf_bytes: bytes) -> bytes:
    """A real user password — the file ingestion rejects and the worker never receives."""
    return _rewrite(
        pdf_bytes,
        user_password="user-secret",
        owner_password="owner-secret",
        algorithm="AES-256",
    )


@pytest.fixture()
def paystub() -> bytes:
    return fixture_bytes("paystub_complete.pdf")


class TestTheFixturesThemselves:
    """Guard against a vacuous suite: if the builder quietly emitted plaintext, every
    'identical to unencrypted' assertion below would pass while proving nothing."""

    @pytest.mark.parametrize("algorithm", ALGORITHMS)
    def test_the_owner_only_fixture_really_carries_encryption(self, paystub, algorithm):
        reader = pypdf.PdfReader(io.BytesIO(owner_password_only(paystub, algorithm)))

        assert reader.is_encrypted
        # /Encrypt in the trailer is the on-disk fact PdfProbe reports as Result.encrypted.
        assert "/Encrypt" in reader.trailer

    def test_the_owner_only_fixture_opens_with_the_empty_user_password(self, paystub):
        reader = pypdf.PdfReader(io.BytesIO(owner_password_only(paystub)))

        # No decrypt() call and no password: this is what "opens in every viewer" means.
        assert len(reader.pages) == 1

    def test_the_plaintext_baseline_carries_no_encryption(self, paystub):
        assert not pypdf.PdfReader(io.BytesIO(plaintext(paystub))).is_encrypted


class TestTextStage:
    """text.py:131 — pdfplumber.open(io.BytesIO(pdf_bytes))."""

    @pytest.mark.parametrize("algorithm", ALGORITHMS)
    def test_spans_are_identical_to_the_unencrypted_document(self, paystub, algorithm):
        expected = [page.payload() for page in extract_text(plaintext(paystub), [])]

        actual = [
            page.payload()
            for page in extract_text(owner_password_only(paystub, algorithm), [])
        ]

        # Not "close enough" — every span, every coordinate, byte for byte. Encryption that
        # the reader transparently undoes must leave nothing behind for extraction to trip on.
        assert actual == expected

    def test_the_document_verdict_is_still_native_not_a_blank_scan(self, paystub):
        page = extract_text(owner_password_only(paystub), [])[0].payload()

        # The ink/verdict path renders through PDFium (text.py:427) — a separate library from
        # pdfminer, so this asserts the SECOND decryptor on the text stage also succeeded.
        # A failed decrypt would surface as a wordless page classified for OCR.
        assert page["verdict"] == "NATIVE"
        assert page["inkFraction"] > 0

    def test_the_words_field_extraction_consumes_survive_encryption(self, paystub):
        """The point of the whole fix: an encrypted file still reaches extracted fields.

        The Java extraction ITs consume PERSISTED spans rather than PDF bytes, so the worker is
        the only place encryption could break a field. Every value word the ground truth expects
        must come out of the encrypted document — borrower name, employer, pay dates, gross and
        net pay. Geometry is covered by the identical-spans test above rather than here: truth
        coordinates are the fixture generator's, which is a different frame from a pdfplumber
        word box, so comparing them would assert something this test is not about.
        """
        truth = fixture_truth("paystub_complete.json")
        spans = extract_text(owner_password_only(paystub), [])[0].payload()["spans"]
        located = {span["text"] for span in spans}

        expected_fields = truth["expectedFields"]
        assert expected_fields, "truth file carries no expectedFields — test would be vacuous"
        for field in expected_fields:
            for word in field["valueWords"]:
                assert word["text"] in located, (
                    f"field {field['field']!r} lost value word {word['text']!r} to encryption"
                )


class TestRenderStage:
    """render.py:100 — pdfium.PdfDocument(pdf_bytes), the /v1/render raster path."""

    @pytest.mark.parametrize("algorithm", ALGORITHMS)
    def test_pages_raster_identically_to_the_unencrypted_document(self, paystub, algorithm):
        expected = render_pdf(plaintext(paystub), [], dpi=72)

        actual = render_pdf(owner_password_only(paystub, algorithm), [], dpi=72)

        assert [page.metadata() for page in actual] == [
            page.metadata() for page in expected
        ]
        # Byte-identical PNGs: OCR downstream sees the same pixels, so no OCR result can drift.
        assert [page.png for page in actual] == [page.png for page in expected]


class TestLayoutStage:
    """layout/rulings.py:30 — pdfplumber.open on the optional /v1/layout PDF part."""

    @pytest.mark.parametrize("algorithm", ALGORITHMS)
    def test_rulings_are_identical_to_the_unencrypted_document(self, algorithm):
        ruled = fixture_bytes("ruled_table.pdf")
        expected = extract_rulings(plaintext(ruled), [0])

        actual = extract_rulings(owner_password_only(ruled, algorithm), [0])

        assert actual.keys() == expected.keys()
        assert expected[0].horizontals, "baseline found no rulings — test would be vacuous"
        for index, rulings in actual.items():
            assert rulings.horizontals == expected[index].horizontals
            assert rulings.verticals == expected[index].verticals


class TestEndpointPath:
    """The same bytes over the real HTTP contract, not just the module function."""

    def test_v1_text_accepts_an_owner_password_only_upload(self, client, paystub):
        response = client.post(
            "/v1/text",
            files={"file": ("upload.bin", owner_password_only(paystub), "application/pdf")},
            data={"request": json.dumps({"pages": [0]})},
        )

        assert response.status_code == 200
        # Exactly the words fixtures/generate.py drew on the paystub — not a truncated read.
        assert len(response.json()["pages"][0]["spans"]) == 50


class TestTrueUserPasswordControl:
    """Discriminator: the assertions above are only meaningful if a locked file DOES fail.

    Ingestion rejects these at upload with PASSWORD_PROTECTED, so the worker never sees one in
    production. Pinned here purely to prove the suite can fail.
    """

    def test_text_rejects_a_user_password_protected_pdf(self, paystub):
        with pytest.raises(Exception) as caught:
            extract_text(user_password_protected(paystub), [])

        assert "CORRUPT_PDF" in str(getattr(caught.value, "detail", caught.value))

    def test_render_rejects_a_user_password_protected_pdf(self, paystub):
        with pytest.raises(Exception) as caught:
            render_pdf(user_password_protected(paystub), [], dpi=72)

        assert "CORRUPT_PDF" in str(getattr(caught.value, "detail", caught.value))
