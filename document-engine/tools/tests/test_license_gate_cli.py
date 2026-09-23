"""The licence gate CLI, including the empty-tree guard.

Phase 6 finding, confirmed by simulating a fresh CI checkout: the frontend
licence step ran `license-checker` without `npm ci` first, so it walked a
`ui/node_modules` that did not exist, found exactly one package — our own —
and reported "licence gate OK, 0 violations" with exit 0. A GPL transitive
would have sailed straight through for the entire frontend.

The instance is fixed in CI. The CLASS is fixed here: "the gate passed" and
"the gate had nothing to inspect" must not be the same observable outcome.
"""

import json

import pytest

from tools.license_gate_cli import main

CLEAN_NPM = {
    "pds-review-ui@0.1.0": {"licenses": "Apache-2.0"},
    "react@19.0.0": {"licenses": "MIT"},
    "pdfjs-dist@4.0.0": {"licenses": "Apache-2.0"},
}


def _report(tmp_path, payload) -> str:
    path = tmp_path / "report.json"
    path.write_text(json.dumps(payload))
    return str(path)


def test_clean_report_passes(tmp_path, capsys):
    report = _report(tmp_path, CLEAN_NPM)
    assert main(["--format", "npm", "--report", report]) == 0
    assert "0 violations" in capsys.readouterr().out


def test_copyleft_is_denied(tmp_path, capsys):
    report = _report(tmp_path, {**CLEAN_NPM, "bad@1.0.0": {"licenses": "GPL-3.0"}})
    assert main(["--format", "npm", "--report", report]) == 1
    assert "GPL-3.0" in capsys.readouterr().out


def test_a_tree_smaller_than_the_floor_fails_instead_of_passing_vacuously(tmp_path, capsys):
    """The exact CI failure: one package, no violations, previously exit 0."""
    report = _report(tmp_path, {"pds-review-ui@0.1.0": {"licenses": "Apache-2.0"}})

    exit_code = main(["--format", "npm", "--report", report, "--min-dependencies", "50"])

    assert exit_code == 1
    output = capsys.readouterr().out
    assert "1" in output and "50" in output
    # The message must say the tree was not installed, not that licences are fine.
    assert "violation" not in output.lower() or "inspected" in output.lower()


def test_the_floor_passes_when_the_tree_is_really_there(tmp_path):
    report = _report(tmp_path, CLEAN_NPM)
    assert main(["--format", "npm", "--report", report, "--min-dependencies", "3"]) == 0


def test_the_floor_is_optional_so_existing_invocations_are_unchanged(tmp_path):
    report = _report(tmp_path, {"solo@1.0.0": {"licenses": "MIT"}})
    assert main(["--format", "npm", "--report", report]) == 0


def test_violations_still_win_over_the_floor(tmp_path, capsys):
    """A denied licence must be reported as such, not masked by the size guard."""
    report = _report(tmp_path, {"bad@1.0.0": {"licenses": "AGPL-3.0"}})

    assert main(["--format", "npm", "--report", report, "--min-dependencies", "50"]) == 1
    assert "AGPL-3.0" in capsys.readouterr().out


@pytest.mark.parametrize("fmt", ["pip", "gradle", "npm"])
def test_the_floor_applies_to_every_ecosystem(tmp_path, fmt):
    payloads = {
        "npm": {"solo@1.0.0": {"licenses": "MIT"}},
        "pip": [{"Name": "solo", "Version": "1.0.0", "License": "MIT"}],
        "gradle": {"dependencies": [{"moduleName": "g:solo", "moduleVersion": "1.0.0",
                                     "moduleLicense": "MIT"}]},
    }
    report = _report(tmp_path, payloads[fmt])
    assert main(["--format", fmt, "--report", report, "--min-dependencies", "10"]) == 1
