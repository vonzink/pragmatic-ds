"""Generate THIRD-PARTY-NOTICES.md — the attribution that permissive licences require.

Every dependency in the three ecosystems ships under a permissive licence (the licence gate
enforces that), and permissive licences all carry the SAME core obligation: preserve the copyright
notice and licence text in what you distribute. This produces that file from the reports the three
gate ecosystems already generate, so it can never drift from the tree the gate checked.

    python -m tools.third_party_notices \
        --gradle build/reports/dependency-license/index.json \
        --pip /tmp/pip-licenses-full.json \
        --npm /tmp/npm-licences.json \
        --out THIRD-PARTY-NOTICES.md

Report shapes (same tools the gate uses, some invoked with richer flags for the licence TEXT):
  gradle : com.github.jk1.dependency-license-report JSON (moduleName/Version/Licenses/LicenseUrl).
           The JSON carries no embedded licence text, so Java components cite the SPDX id + URL.
  pip    : pip-licenses --format=json --with-license-file --with-authors --with-urls
           (LicenseText / Author / URL fields present).
  npm    : license-checker --json (licenses / repository / licenseFile path).

Determinism: components are sorted; licence-text sections are keyed and sorted; nothing depends on
report order. Re-running on an unchanged tree yields a byte-identical file, which is what lets CI
diff it and fail on drift (the same stance as fixture provenance).
"""

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class Component:
    ecosystem: str
    name: str
    version: str
    license: str
    url: str | None
    author: str | None
    license_text: str | None


def _clean(value: object) -> str | None:
    if isinstance(value, list):
        value = " OR ".join(str(v) for v in value if v)
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value or None


def _read_gradle(path: Path) -> list[Component]:
    data = json.loads(path.read_text())
    out = []
    for entry in data.get("dependencies", []):
        arms = [
            item.get("moduleLicense")
            for item in entry.get("moduleLicenses", [])
            if item.get("moduleLicense")
        ]
        url = next(
            (
                item.get("moduleLicenseUrl")
                for item in entry.get("moduleLicenses", [])
                if item.get("moduleLicenseUrl")
            ),
            None,
        )
        out.append(
            Component(
                ecosystem="Java (Gradle)",
                name=_clean(entry.get("moduleName")) or "?",
                version=_clean(entry.get("moduleVersion")) or "",
                license=_clean(arms) or "UNKNOWN",
                url=url,
                author=None,
                license_text=None,
            )
        )
    return out


def _read_pip(path: Path) -> list[Component]:
    out = []
    for entry in json.loads(path.read_text()):
        text = _clean(entry.get("LicenseText"))
        if text and text.strip().upper() in {"UNKNOWN", ""}:
            text = None
        out.append(
            Component(
                ecosystem="Python (pip)",
                name=_clean(entry.get("Name")) or "?",
                version=_clean(entry.get("Version")) or "",
                license=_clean(entry.get("License")) or "UNKNOWN",
                url=_clean(entry.get("URL")),
                author=_clean(entry.get("Author")),
                license_text=text,
            )
        )
    return out


def _read_npm(path: Path) -> list[Component]:
    out = []
    for key, entry in json.loads(path.read_text()).items():
        name, _, version = key.rpartition("@")
        if not name:
            name, version = key, ""
        text = None
        license_file = entry.get("licenseFile")
        if license_file:
            candidate = Path(license_file)
            # license-checker emits absolute paths; only embed a real LICENSE file's text, never a
            # README it fell back to (those carry no licence grant and bloat the notices).
            if candidate.is_file() and "licen" in candidate.name.lower():
                try:
                    text = candidate.read_text(errors="replace").strip() or None
                except OSError:
                    text = None
        out.append(
            Component(
                ecosystem="JavaScript (npm)",
                name=name,
                version=version,
                license=_clean(entry.get("licenses")) or "UNKNOWN",
                url=_clean(entry.get("repository")),
                author=_clean(entry.get("publisher")),
                license_text=text,
            )
        )
    return out


HEADER = """\
# Third-Party Notices

The Pragmatic DS Document Engine bundles third-party software under permissive licences. Those licences
require that their copyright notices and licence text be preserved in redistribution; this file is
that preservation.

**Generated — do not edit by hand.** Produced by `tools/third_party_notices.py` from the same
dependency-licence reports the CI licence gate checks, so it cannot drift from what actually ships.
Regenerate with the command in `docs/LICENSING.md`.

Each component below is listed with its version and SPDX licence. Where a tool exposed the
per-package licence text (Python and npm), the full text is reproduced in the appendix; Java
components cite the SPDX identifier and licence URL, which the JSON report carries in place of the
text.

This file is attribution, not a compliance statement. See `docs/LICENSING.md`.
"""


def render(components: list[Component]) -> str:
    lines = [HEADER, ""]
    by_eco: dict[str, list[Component]] = {}
    for c in components:
        by_eco.setdefault(c.ecosystem, []).append(c)

    for eco in sorted(by_eco):
        comps = sorted(by_eco[eco], key=lambda c: (c.name.lower(), c.version))
        lines.append(f"## {eco} — {len(comps)} components\n")
        for c in comps:
            ver = f" `{c.version}`" if c.version else ""
            url = f" — <{c.url}>" if c.url else ""
            lines.append(f"- **{c.name}**{ver} — {c.license}{url}")
        lines.append("")

    # Appendix: each distinct licence text once, listing the components under it.
    texts: dict[str, list[str]] = {}
    for c in components:
        if c.license_text:
            texts.setdefault(c.license_text.strip(), []).append(f"{c.name} {c.version}".strip())
    if texts:
        lines.append("## Appendix — full licence texts\n")
        lines.append(
            "Each distinct licence text below is reproduced once, followed by the components that "
            "carry it.\n"
        )
        for idx, text in enumerate(sorted(texts, key=lambda t: texts[t][0].lower()), start=1):
            users = ", ".join(sorted(set(texts[text]), key=str.lower))
            lines.append(f"### Licence text {idx}\n")
            lines.append(f"*Applies to: {users}*\n")
            lines.append("```")
            lines.append(text)
            lines.append("```")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="third-party-notices")
    parser.add_argument("--gradle", type=Path)
    parser.add_argument("--pip", type=Path)
    parser.add_argument("--npm", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit 1 if --out is missing or would change, without writing (for CI drift detection).",
    )
    args = parser.parse_args(argv)

    components: list[Component] = []
    if args.gradle:
        components += _read_gradle(args.gradle)
    if args.pip:
        components += _read_pip(args.pip)
    if args.npm:
        components += _read_npm(args.npm)
    if not components:
        print("no reports supplied — pass at least one of --gradle/--pip/--npm", file=sys.stderr)
        return 2

    rendered = render(components)
    if args.check:
        current = args.out.read_text() if args.out.exists() else None
        if current == rendered:
            print(f"THIRD-PARTY-NOTICES up to date — {len(components)} components")
            return 0
        print(
            f"THIRD-PARTY-NOTICES is STALE ({len(components)} components in the tree). "
            f"Regenerate: see docs/LICENSING.md",
            file=sys.stderr,
        )
        return 1

    args.out.write_text(rendered)
    print(f"wrote {args.out} — {len(components)} components")
    return 0


if __name__ == "__main__":
    sys.exit(main())
