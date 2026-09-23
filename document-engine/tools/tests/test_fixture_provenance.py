"""Tests for the fixture provenance check.

Test fixtures must be generated, never collected. This check is the mechanical
guarantee behind that promise: a real borrower document dropped into fixtures/
fails the build, rather than relying on everyone remembering the policy.

Provenance is tracked by a manifest rather than an inline marker, because
fixtures are binary (PDF/PNG) and a manifest also detects hand-editing.
"""

import hashlib
import json

from tools.fixture_provenance import check_fixture_provenance


def write_fixture(root, name: str, content: bytes) -> str:
    (root / name).write_bytes(content)
    return hashlib.sha256(content).hexdigest()


def write_manifest(root, entries: list[dict]) -> None:
    (root / "MANIFEST.json").write_text(
        json.dumps({"generator": "fixtures/generate.py", "entries": entries})
    )


def test_the_generator_script_itself_is_exempt(tmp_path):
    """generate.py IS the provenance — it cannot list its own hash (writing the hash into
    the manifest would change nothing, but the script cannot appear in its own output
    deterministically while remaining editable). The file named in manifest["generator"],
    when it lives inside the fixtures dir, is exempt like MANIFEST.json itself."""
    (tmp_path / "generate.py").write_bytes(b"# the generator")
    write_manifest(tmp_path, [])

    result = check_fixture_provenance(tmp_path)

    assert result.ok is True, result.problems


def test_interpreter_cache_artifacts_are_exempt(tmp_path):
    """Importing fixtures/generate.py (the generator test does) creates __pycache__
    inside the fixtures dir. Bytecode is an interpreter artifact, not fixture data —
    it can never be a smuggled borrower document."""
    cache = tmp_path / "__pycache__"
    cache.mkdir()
    (cache / "generate.cpython-312.pyc").write_bytes(b"\x00bytecode")
    write_manifest(tmp_path, [])

    result = check_fixture_provenance(tmp_path)

    assert result.ok is True, result.problems


def test_passes_when_every_file_is_listed_and_hashes_match(tmp_path):
    digest = write_fixture(tmp_path, "paystub_native.pdf", b"%PDF-1.7 synthetic")
    write_manifest(tmp_path, [{"path": "paystub_native.pdf", "sha256": digest}])

    result = check_fixture_provenance(tmp_path)

    assert result.ok is True
    assert result.problems == []


def test_unlisted_file_is_reported(tmp_path):
    """The case this check exists for: a real document committed by accident."""
    digest = write_fixture(tmp_path, "paystub_native.pdf", b"%PDF-1.7 synthetic")
    write_fixture(tmp_path, "real_borrower_paystub.pdf", b"%PDF-1.7 NOT SYNTHETIC")
    write_manifest(tmp_path, [{"path": "paystub_native.pdf", "sha256": digest}])

    result = check_fixture_provenance(tmp_path)

    assert result.ok is False
    assert any(
        p.kind == "UNLISTED" and p.path == "real_borrower_paystub.pdf" for p in result.problems
    )


def test_modified_fixture_is_reported(tmp_path):
    """A hand-edited fixture has lost its provenance even though it is listed."""
    write_fixture(tmp_path, "paystub_native.pdf", b"%PDF-1.7 edited by hand")
    write_manifest(
        tmp_path, [{"path": "paystub_native.pdf", "sha256": hashlib.sha256(b"original").hexdigest()}]
    )

    result = check_fixture_provenance(tmp_path)

    assert result.ok is False
    assert any(p.kind == "HASH_MISMATCH" for p in result.problems)


def test_manifest_entry_without_a_file_is_reported(tmp_path):
    write_manifest(tmp_path, [{"path": "gone.pdf", "sha256": "0" * 64}])

    result = check_fixture_provenance(tmp_path)

    assert result.ok is False
    assert any(p.kind == "MISSING" and p.path == "gone.pdf" for p in result.problems)


def test_absent_manifest_fails_closed(tmp_path):
    write_fixture(tmp_path, "orphan.pdf", b"anything")

    result = check_fixture_provenance(tmp_path)

    assert result.ok is False
    assert any(p.kind == "NO_MANIFEST" for p in result.problems)


def test_nested_directories_are_scanned(tmp_path):
    (tmp_path / "golden").mkdir()
    write_fixture(tmp_path, "golden/sneaky.json", b"{}")
    write_manifest(tmp_path, [])

    result = check_fixture_provenance(tmp_path)

    assert result.ok is False
    assert any(p.kind == "UNLISTED" and p.path == "golden/sneaky.json" for p in result.problems)
