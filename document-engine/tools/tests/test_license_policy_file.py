"""Tests for loading the policy from licenses/policy.toml, and the CLI gate.

The final test is the acceptance criterion from IMPLEMENTATION_PLAN.md Phase 0:
the gate must be proven to fail on an AGPL dependency, against the real policy
file the repo actually ships. A gate that is configured but never seen to fail
is not a gate.
"""

import json
from pathlib import Path

from tools.license_gate import Dependency, Policy, run_gate
from tools.license_gate_cli import main

REPO_POLICY = Path("licenses/policy.toml")


def test_policy_loads_allowlist_denylist_aliases_and_exemptions(tmp_path):
    path = tmp_path / "policy.toml"
    path.write_text(
        """
        allowed = ["MIT", "Apache-2.0"]
        denied_patterns = ["AGPL", "GPL"]

        [aliases]
        "MIT License" = "MIT"

        [[exemptions]]
        package = "temurin-jdk"
        license = "GPL-2.0-with-classpath-exception"
        elected = "GPL-2.0-with-classpath-exception"
        reason = "Classpath Exception"
        """
    )

    policy = Policy.load(path)

    assert "MIT" in policy.allowed
    assert "AGPL" in policy.denied_patterns
    assert policy.aliases["MIT License"] == "MIT"
    assert policy.exemptions[0].package == "temurin-jdk"


def test_shipped_policy_file_permits_the_mvp_stack():
    policy = Policy.load(REPO_POLICY)

    mvp = [
        Dependency("pypdfium2", "4.30.0", "Apache-2.0"),
        Dependency("pdfplumber", "0.11.4", "MIT License"),
        Dependency("pypdf", "5.1.0", "BSD License"),
        Dependency("rapidocr-onnxruntime", "1.4.0", "Apache Software License"),
        Dependency("pytesseract", "0.3.13", "Apache Software License"),
        Dependency("numpy", "2.2.0", "BSD License"),
    ]

    result = run_gate(mvp, policy)

    assert result.ok is True, [f"{v.dependency.name}: {v.reason}" for v in result.violations]


def test_shipped_policy_file_rejects_agpl():
    """Phase 0 acceptance criterion 3, against the real policy file."""
    policy = Policy.load(REPO_POLICY)

    result = run_gate([Dependency("PyMuPDF", "1.24.0", "AGPL-3.0")], policy)

    assert result.ok is False
    assert "AGPL" in result.violations[0].reason


def test_cli_exits_nonzero_and_names_the_offender(tmp_path, capsys):
    report = tmp_path / "pip.json"
    report.write_text(
        json.dumps(
            [
                {"Name": "pdfplumber", "Version": "0.11.4", "License": "MIT License"},
                {"Name": "PyMuPDF", "Version": "1.24.0", "License": "GNU Affero General Public License v3"},
            ]
        )
    )

    exit_code = main(["--format", "pip", "--report", str(report), "--policy", str(REPO_POLICY)])

    assert exit_code == 1
    assert "PyMuPDF" in capsys.readouterr().out


def test_cli_exits_zero_when_compliant(tmp_path):
    report = tmp_path / "pip.json"
    report.write_text(json.dumps([{"Name": "pdfplumber", "Version": "0.11.4", "License": "MIT License"}]))

    assert main(["--format", "pip", "--report", str(report), "--policy", str(REPO_POLICY)]) == 0
