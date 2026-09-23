"""Golden files pin the /v1/text wire output (WORKER_CONTRACT.md: "Golden files pin
the Python side"). They live under worker/tests/golden/ — NOT fixtures/, which the
provenance manifest owns — and change ONLY via:

    .venv/bin/python -m pytest worker/tests/render worker/tests/text --update-goldens

The full response is pinned, worker version block included: a library bump that
changes output must show up as an attributable golden diff.
"""


def test_native_paystub_text_output_matches_golden(client, fixture_bytes, text_of, golden):
    response = text_of(client, fixture_bytes("native_paystub.pdf"))

    assert response.status_code == 200
    golden("text_native_paystub.json", response.json())


def test_mixed_page_text_output_matches_golden(client, fixture_bytes, text_of, golden):
    response = text_of(client, fixture_bytes("mixed_page.pdf"))

    assert response.status_code == 200
    golden("text_mixed_page.json", response.json())
