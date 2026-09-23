"""Tests for the three ecosystem report parsers.

Python, Java, and npm each emit a different shape. A parser that silently drops
a dependency turns the licence gate into decoration, so the case that matters
most in each format is the one where a licence is absent or oddly encoded.
"""

import json

from tools.license_reports import parse_gradle, parse_npm, parse_pip


def test_parse_pip_licenses_json():
    raw = json.dumps(
        [
            {"Name": "pdfplumber", "Version": "0.11.4", "License": "MIT License"},
            {"Name": "pypdf", "Version": "5.1.0", "License": "BSD License"},
        ]
    )

    deps = parse_pip(raw)

    assert [(d.name, d.version, d.license) for d in deps] == [
        ("pdfplumber", "0.11.4", "MIT License"),
        ("pypdf", "5.1.0", "BSD License"),
    ]


def test_parse_pip_semicolon_joined_classifiers_become_a_choice():
    """pip-licenses joins multiple trove classifiers with '; ', not OR."""
    raw = json.dumps(
        [{"Name": "uvloop", "Version": "0.22.1", "License": "Apache Software License; MIT License"}]
    )

    deps = parse_pip(raw)

    assert deps[0].license == "Apache Software License OR MIT License"


def test_parse_pip_treats_unknown_as_absent():
    """pip-licenses writes the literal string UNKNOWN, which must not be trusted."""
    raw = json.dumps([{"Name": "mystery", "Version": "1.0", "License": "UNKNOWN"}])

    deps = parse_pip(raw)

    assert deps[0].license is None


def test_parse_gradle_license_report():
    """The jk1 plugin nests licences in a moduleLicenses ARRAY, not a scalar field."""
    raw = json.dumps(
        {
            "dependencies": [
                {
                    "moduleName": "org.springframework.boot:spring-boot",
                    "moduleVersion": "3.5.0",
                    "moduleUrls": ["https://spring.io"],
                    "moduleLicenses": [
                        {
                            "moduleLicense": "Apache License, Version 2.0",
                            "moduleLicenseUrl": "https://www.apache.org/licenses/LICENSE-2.0",
                        }
                    ],
                }
            ]
        }
    )

    deps = parse_gradle(raw)

    assert deps[0].name == "org.springframework.boot:spring-boot"
    assert deps[0].version == "3.5.0"
    assert deps[0].license == "Apache License, Version 2.0"


def test_parse_gradle_multiple_licences_become_a_choice():
    """logback ships EPL-1.0 OR LGPL-2.1. The gate must see both arms and elect one."""
    raw = json.dumps(
        {
            "dependencies": [
                {
                    "moduleName": "ch.qos.logback:logback-classic",
                    "moduleVersion": "1.5.18",
                    "moduleLicenses": [
                        {"moduleLicense": None, "moduleLicenseUrl": "http://example/both"},
                        {"moduleLicense": "Eclipse Public License - v 1.0"},
                        {"moduleLicense": "GNU Lesser General Public License"},
                    ],
                }
            ]
        }
    )

    deps = parse_gradle(raw)

    assert deps[0].license == "Eclipse Public License - v 1.0 OR GNU Lesser General Public License"


def test_parse_gradle_missing_license_becomes_none():
    raw = json.dumps({"dependencies": [{"moduleName": "some:lib", "moduleVersion": "1.0"}]})

    deps = parse_gradle(raw)

    assert deps[0].license is None


def test_parse_gradle_all_null_licences_become_none():
    raw = json.dumps(
        {
            "dependencies": [
                {
                    "moduleName": "some:lib",
                    "moduleVersion": "1.0",
                    "moduleLicenses": [{"moduleLicense": None, "moduleLicenseUrl": "http://x"}],
                }
            ]
        }
    )

    deps = parse_gradle(raw)

    assert deps[0].license is None


def test_parse_npm_splits_name_and_version():
    raw = json.dumps(
        {
            "react@19.0.0": {"licenses": "MIT"},
            "@scoped/pkg@2.1.0": {"licenses": "(MIT OR Apache-2.0)"},
        }
    )

    deps = {d.name: d for d in parse_npm(raw)}

    assert deps["react"].version == "19.0.0"
    assert deps["react"].license == "MIT"
    assert deps["@scoped/pkg"].version == "2.1.0"
    assert deps["@scoped/pkg"].license == "(MIT OR Apache-2.0)"


def test_parse_npm_license_list_is_joined_as_a_choice():
    raw = json.dumps({"somelib@1.0.0": {"licenses": ["MIT", "Apache-2.0"]}})

    deps = parse_npm(raw)

    assert deps[0].license == "MIT OR Apache-2.0"
