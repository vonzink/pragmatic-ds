"""The THIRD-PARTY-NOTICES generator: reads all three report shapes, is deterministic, and its
--check mode detects drift (which is what lets CI keep the committed file honest)."""

import json

from tools.third_party_notices import main, render, _read_gradle, _read_npm, _read_pip


def _write(tmp_path, name, obj):
    p = tmp_path / name
    p.write_text(json.dumps(obj))
    return p


def test_reads_all_three_report_shapes(tmp_path):
    gradle = _write(
        tmp_path,
        "g.json",
        {
            "dependencies": [
                {
                    "moduleName": "org.example:lib",
                    "moduleVersion": "1.2.3",
                    "moduleLicenses": [
                        {"moduleLicense": "Apache-2.0", "moduleLicenseUrl": "http://x/apache"}
                    ],
                }
            ]
        },
    )
    pip = _write(
        tmp_path,
        "p.json",
        [{"Name": "reportlab", "Version": "4.0", "License": "BSD", "LicenseText": "BSD TEXT",
          "Author": "RL", "URL": "http://reportlab"}],
    )
    npm = _write(tmp_path, "n.json", {"react@19.0.0": {"licenses": "MIT", "repository": "http://react"}})

    assert _read_gradle(gradle)[0].license == "Apache-2.0"
    assert _read_pip(pip)[0].license_text == "BSD TEXT"
    assert _read_npm(npm)[0].name == "react" and _read_npm(npm)[0].version == "19.0.0"


def test_render_is_deterministic_and_groups_by_ecosystem(tmp_path):
    npm = _write(
        tmp_path,
        "n.json",
        {"b@2": {"licenses": "MIT"}, "a@1": {"licenses": "ISC"}},
    )
    comps = _read_npm(npm)
    first = render(comps)
    assert first == render(comps)  # deterministic
    # sorted by name: 'a' before 'b' regardless of report order
    assert first.index("**a**") < first.index("**b**")
    assert "## JavaScript (npm) — 2 components" in first


def test_npm_readme_fallback_is_not_embedded_as_licence_text(tmp_path):
    readme = tmp_path / "README.md"
    readme.write_text("not a licence")
    npm = _write(tmp_path, "n.json", {"x@1": {"licenses": "MIT", "licenseFile": str(readme)}})
    assert _read_npm(npm)[0].license_text is None  # only a real LICENSE file's text is embedded


def test_check_mode_detects_drift(tmp_path):
    npm = _write(tmp_path, "n.json", {"a@1": {"licenses": "MIT"}})
    out = tmp_path / "NOTICES.md"

    # missing file → stale (exit 1)
    assert main(["--npm", str(npm), "--out", str(out), "--check"]) == 1
    # generate it
    assert main(["--npm", str(npm), "--out", str(out)]) == 0
    # now up to date (exit 0)
    assert main(["--npm", str(npm), "--out", str(out), "--check"]) == 0
    # a changed tree → stale again
    npm2 = _write(tmp_path, "n2.json", {"a@1": {"licenses": "MIT"}, "b@2": {"licenses": "ISC"}})
    assert main(["--npm", str(npm2), "--out", str(out), "--check"]) == 1


def test_no_reports_is_an_error(tmp_path):
    assert main(["--out", str(tmp_path / "x.md")]) == 2
