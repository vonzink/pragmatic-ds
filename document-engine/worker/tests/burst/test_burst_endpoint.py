"""/v1/burst endpoint — wiring, auth, and the multipart/mixed response shape."""

import io
import json

import pypdf


def build_pdf(page_count: int) -> bytes:
    writer = pypdf.PdfWriter()
    for _ in range(page_count):
        writer.add_blank_page(width=612, height=792)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def post_burst(client, sources: dict[str, bytes], sequence: list[dict]):
    files = {
        name: (name, payload, "application/octet-stream")
        for name, payload in sources.items()
    }
    files["request"] = (None, json.dumps({"pages": sequence}), "application/json")
    return client.post("/v1/burst", files=files)


class TestAuth:
    def test_missing_secret_is_401_unauthorized(self, anon_client):
        response = post_burst(
            anon_client, {"file-0": build_pdf(1)}, [{"part": "file-0", "index": 0}]
        )
        assert response.status_code == 401
        assert response.json()["error"] == "UNAUTHORIZED"


class TestBurstEndpoint:
    def test_burst_returns_metadata_then_pdf(self, client, mixed_parts):
        response = post_burst(
            client,
            {"file-0": build_pdf(3)},
            [{"part": "file-0", "index": 2}, {"part": "file-0", "index": 0}],
        )
        assert response.status_code == 200, response.text

        parts = mixed_parts(response)
        assert [part["name"] for part in parts] == ["metadata", "pdf"]

        metadata = json.loads(parts[0]["content"])
        assert metadata["pageCount"] == 2
        assert metadata["pdfPart"] == "pdf"
        assert metadata["worker"]["libraries"]["pypdf"]
        assert metadata["worker"]["libraries"]["pillow"]

        assert parts[1]["contentType"] == "application/pdf"
        reader = pypdf.PdfReader(io.BytesIO(parts[1]["content"]))
        assert len(reader.pages) == 2

    def test_two_source_parts_in_one_call(self, client, mixed_parts):
        response = post_burst(
            client,
            {"file-0": build_pdf(1), "file-1": build_pdf(2)},
            [{"part": "file-1", "index": 1}, {"part": "file-0", "index": 0}],
        )
        assert response.status_code == 200, response.text
        parts = mixed_parts(response)
        reader = pypdf.PdfReader(io.BytesIO(parts[1]["content"]))
        assert len(reader.pages) == 2

    def test_empty_pages_is_400(self, client):
        response = post_burst(client, {"file-0": build_pdf(3)}, [])
        assert response.status_code == 400
        body = response.json()
        assert body["error"] == "INVALID_REQUEST"
        assert body["detail"]["reason"] == "PAGES_REQUIRED"

    def test_out_of_range_is_400(self, client):
        response = post_burst(
            client, {"file-0": build_pdf(1)}, [{"part": "file-0", "index": 4}]
        )
        assert response.status_code == 400
        assert response.json()["error"] == "PAGE_OUT_OF_RANGE"

    def test_unreferenced_missing_part_is_400(self, client):
        response = post_burst(
            client, {"file-0": build_pdf(1)}, [{"part": "file-9", "index": 0}]
        )
        assert response.status_code == 400
        body = response.json()
        assert body["detail"] == {"reason": "FILE_PART_MISSING", "partName": "file-9"}
