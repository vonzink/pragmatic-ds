"""Parsers for the three ecosystems' licence reports.

Python, Java, and npm each emit a different shape. Everything is normalised to
`Dependency` so the gate has exactly one thing to reason about.

An absent, empty, or literal-"UNKNOWN" licence normalises to None, which the
gate denies. That is deliberate: a dependency whose licence a tool could not
determine is not thereby permissive.
"""

import json

from tools.license_gate import Dependency

_ABSENT = {"", "unknown", "unknown license", "none", "null"}


def _license_or_none(value: object) -> str | None:
    if isinstance(value, list):
        value = " OR ".join(str(item) for item in value if item)
    if not isinstance(value, str):
        return None
    # pip-licenses joins multiple trove classifiers with "; " — a choice, the
    # same as the jk1 plugin's multiple moduleLicenses entries.
    cleaned = " OR ".join(part.strip() for part in value.split(";") if part.strip())
    return None if cleaned.lower() in _ABSENT else cleaned


def parse_pip(raw: str) -> list[Dependency]:
    """`pip-licenses --format=json`."""
    return [
        Dependency(
            name=entry.get("Name", ""),
            version=entry.get("Version", ""),
            license=_license_or_none(entry.get("License")),
        )
        for entry in json.loads(raw)
    ]


def parse_gradle(raw: str) -> list[Dependency]:
    """`com.github.jk1.dependency-license-report` JSON output.

    Licences are nested in a `moduleLicenses` array, not a scalar field, and a
    module may declare several — a POM listing both EPL-1.0 and LGPL-2.1 means
    a *choice*, so the arms are joined into an SPDX-style OR expression and the
    gate elects a permitted one. Entries whose `moduleLicense` is null carry
    only a URL and are dropped.
    """
    dependencies = []
    for entry in json.loads(raw).get("dependencies", []):
        arms = [
            item.get("moduleLicense")
            for item in entry.get("moduleLicenses", [])
            if item.get("moduleLicense")
        ]
        dependencies.append(
            Dependency(
                name=entry.get("moduleName", ""),
                version=entry.get("moduleVersion", ""),
                license=_license_or_none(arms),
            )
        )
    return dependencies


def parse_npm(raw: str) -> list[Dependency]:
    """`license-checker --json`, keyed by "name@version".

    Scoped packages start with '@', so the version separator is the *last* '@'.
    """
    dependencies = []
    for key, entry in json.loads(raw).items():
        name, _, version = key.rpartition("@")
        if not name:  # unscoped key with no version separator
            name, version = key, ""
        dependencies.append(
            Dependency(name=name, version=version, license=_license_or_none(entry.get("licenses")))
        )
    return dependencies
