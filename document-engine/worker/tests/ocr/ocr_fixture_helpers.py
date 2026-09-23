"""Shared helpers for the OCR test suite: fixture paths, truth lookups, request bodies."""

import json
from pathlib import Path

from PIL import Image

FIXTURES = Path(__file__).resolve().parents[3] / "fixtures"
GOLDEN_DIR = Path(__file__).resolve().parents[1] / "golden"


def fixture_path(name: str) -> Path:
    return FIXTURES / name


def fixture_image(name: str) -> Image.Image:
    return Image.open(FIXTURES / name).convert("RGB")


def fixture_bytes(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def truth(name: str) -> dict:
    return json.loads((FIXTURES / "truth" / name).read_text())


def truth_word(truth_doc: dict, text: str, page_index: int = 0) -> dict:
    """First ground-truth word with this exact text."""
    for word in truth_doc["pages"][page_index]["words"]:
        if word["text"] == text:
            return word
    raise AssertionError(f"no truth word {text!r}")
