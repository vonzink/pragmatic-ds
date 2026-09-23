"""OCR test suite plumbing: the --update-goldens flag and the slow marker.

Lives INSIDE the ocr subpackage (not the shared worker/tests/conftest.py) so golden
refresh is invoked explicitly against this suite — and inside the worker's own test
image, which is where the OCR engine these goldens describe actually lives:

    docker build --platform linux/amd64 --target test -t pds-worker-test worker/
    docker run --rm --platform linux/amd64 -v "$PWD:/repo" pds-worker-test \\
        pytest worker/tests/ocr --update-goldens

worker/tests/conftest.py enforces the image half; see its `pytest_configure`.

pytest only registers options from conftests that are "initial" for the invocation,
so the flag exists when this directory is named on the command line — which is the
only sanctioned way to rewrite goldens (IMPLEMENTATION_PLAN.md Phase 2 acceptance 4).
"""

import pytest


def pytest_addoption(parser):
    try:
        parser.addoption(
            "--update-goldens",
            action="store_true",
            default=False,
            help="Rewrite worker/tests/golden/ OCR golden files from current output.",
        )
    except ValueError:
        # Another stage suite already registered the same flag in this invocation.
        pass


def pytest_configure(config):
    config.addinivalue_line(
        "markers", "slow: real-engine OCR test measured in seconds; still part of the full run"
    )


@pytest.fixture()
def update_goldens(request) -> bool:
    try:
        return bool(request.config.getoption("--update-goldens"))
    except ValueError:
        # Bare `pytest` run: this conftest was not initial, so the flag never registered.
        return False
