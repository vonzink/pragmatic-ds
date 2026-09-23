"""Burst-test conftest: the committed-fixture loader, per the subpackage-owns-its-
fixtures convention (see worker/tests/render/conftest.py)."""

from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parents[3] / "fixtures"


@pytest.fixture()
def fixture_bytes():
    def load(name: str) -> bytes:
        return (FIXTURES / name).read_bytes()

    return load
