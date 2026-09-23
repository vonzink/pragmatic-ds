"""Fixture provenance check.

Test fixtures are generated, never collected. This check is the mechanical
guarantee: a file under fixtures/ that the generator did not produce fails the
build. Provenance lives in a manifest rather than an inline marker because
fixtures are binary, and a manifest additionally catches hand-editing.
"""

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

MANIFEST_NAME = "MANIFEST.json"


@dataclass(frozen=True)
class Problem:
    kind: str
    path: str
    detail: str = ""


@dataclass(frozen=True)
class ProvenanceResult:
    problems: list[Problem]
    ok: bool


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_fixture_provenance(fixtures_dir: Path) -> ProvenanceResult:
    fixtures_dir = Path(fixtures_dir)
    manifest_path = fixtures_dir / MANIFEST_NAME

    # Fail closed. An absent manifest means unverifiable provenance, which is
    # indistinguishable from "someone committed a real borrower document".
    if not manifest_path.is_file():
        return ProvenanceResult(
            problems=[Problem("NO_MANIFEST", MANIFEST_NAME, f"no {MANIFEST_NAME} in {fixtures_dir}")],
            ok=False,
        )

    try:
        manifest = json.loads(manifest_path.read_text())
    except json.JSONDecodeError as exc:
        return ProvenanceResult(problems=[Problem("BAD_MANIFEST", MANIFEST_NAME, str(exc))], ok=False)

    declared = {entry["path"]: entry["sha256"] for entry in manifest.get("entries", [])}

    # The generator script IS the provenance — exempt like the manifest itself when it
    # lives inside the fixtures dir (it cannot deterministically list its own hash).
    generator_name = Path(str(manifest.get("generator", ""))).name
    exempt = {MANIFEST_NAME, generator_name} - {""}

    present = {
        p.relative_to(fixtures_dir).as_posix()
        for p in fixtures_dir.rglob("*")
        if p.is_file()
        and p.name not in exempt
        # Interpreter bytecode appears whenever the generator module is imported;
        # it is an artifact of Python, not fixture data.
        and "__pycache__" not in p.parts
    }

    problems: list[Problem] = []

    for path in sorted(present - declared.keys()):
        problems.append(Problem("UNLISTED", path, "present but not produced by the generator"))

    for path in sorted(declared.keys() - present):
        problems.append(Problem("MISSING", path, "declared in the manifest but absent"))

    for path in sorted(present & declared.keys()):
        actual = _digest(fixtures_dir / path)
        if actual != declared[path]:
            problems.append(
                Problem("HASH_MISMATCH", path, f"expected {declared[path][:12]}, got {actual[:12]}")
            )

    return ProvenanceResult(problems=problems, ok=not problems)
