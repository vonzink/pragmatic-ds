"""Golden file for /v1/render on native_multipage: the metadata part plus each PNG
part's sha256 and byte length — NOT the PNG bytes themselves. pypdfium2 pixel output
is not guaranteed identical across platforms/versions, so the golden pins this
platform's rendering; a hash flip is a signal to attribute (library bump, platform
change), refreshed via:

    .venv/bin/python -m pytest worker/tests/render worker/tests/text --update-goldens
"""

import hashlib
import json


def test_native_multipage_render_metadata_matches_golden(client, fixture_bytes, mixed_parts, golden):
    response = client.post(
        "/v1/render",
        files={"file": ("upload.bin", fixture_bytes("native_multipage.pdf"), "application/pdf")},
        data={"request": json.dumps({"dpi": 200})},
    )

    assert response.status_code == 200
    parts = mixed_parts(response)
    payload = {
        "metadata": json.loads(parts[0]["content"]),
        "pngParts": [
            {
                "name": part["name"],
                "sha256": hashlib.sha256(part["content"]).hexdigest(),
                "length": len(part["content"]),
            }
            for part in parts[1:]
        ],
    }
    golden("render_native_multipage.json", payload)
