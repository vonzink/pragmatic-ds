"""The consumer-guide drift gate, exercised against synthetic trees.

The gate's whole value is that it fails when nobody wanted it to — after an
edit to the contract that looked unrelated to the summary. So what is asserted
here is mostly the failure directions: a moved contract, a missing pin, a
deleted file. The passing case is one line.
"""

import hashlib

import pytest

from tools.consumer_guide_gate import PIN_KEY, check_consumer_guide

CONTRACT_BODY = "# Contract\n\nA field that could not be found still gets a row.\n"


def _digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _guide(pin: str) -> str:
    return f"---\ntitle: guide\n{PIN_KEY}: {pin}\n---\n\n# Guide\n"


@pytest.fixture
def tree(tmp_path):
    """A contract and a guide pinned to it. Cases mutate one or the other."""

    def build(*, contract=CONTRACT_BODY, pin=None, write_guide=True, write_contract=True):
        contract_path = tmp_path / "income-extraction-contract.md"
        guide_path = tmp_path / "consumer-integration-guide.md"
        if write_contract:
            contract_path.write_text(contract, encoding="utf-8")
        if write_guide:
            guide_path.write_text(
                _guide(_digest(CONTRACT_BODY) if pin is None else pin), encoding="utf-8"
            )
        return contract_path, guide_path

    return build


def test_a_guide_pinned_to_the_current_contract_passes(tree):
    contract, guide = tree()
    assert check_consumer_guide(contract, guide).ok


def test_an_edited_contract_fails_and_names_both_digests(tree):
    """The case the gate exists for: the contract moved, the summary did not."""
    contract, guide = tree(contract=CONTRACT_BODY + "\nA new rule nobody told consumers.\n")

    result = check_consumer_guide(contract, guide)

    assert not result.ok
    assert _digest(CONTRACT_BODY) in result.problem, "the stale pin"
    assert _digest(contract.read_text(encoding="utf-8")) in result.problem, "what to re-record"
    # The instruction that keeps the gate from being defeated by the obvious fix.
    assert "Do NOT re-record the hash" in result.problem


def test_a_guide_with_no_pin_fails(tree):
    contract, guide = tree()
    guide.write_text("---\ntitle: guide\n---\n\n# Guide\n", encoding="utf-8")

    result = check_consumer_guide(contract, guide)

    assert not result.ok
    assert PIN_KEY in result.problem


def test_a_malformed_pin_reads_as_no_pin_rather_than_raising(tree):
    """A truncated or non-hex digest must report, not explode."""
    contract, guide = tree(pin="not-a-sha")

    result = check_consumer_guide(contract, guide)

    assert not result.ok
    assert PIN_KEY in result.problem


@pytest.mark.parametrize(
    "name,kwargs",
    [
        ("the contract was deleted or moved", {"write_contract": False}),
        ("the guide was deleted or moved", {"write_guide": False}),
    ],
)
def test_a_missing_file_fails_closed(tree, name, kwargs):
    """An absent file is the subject matter vanishing, never "nothing to check"."""
    contract, guide = tree(**kwargs)

    result = check_consumer_guide(contract, guide)

    assert not result.ok, name
    assert "missing" in result.problem


def test_the_committed_guide_is_pinned_to_the_committed_contract():
    """The gate against the real tree — the assertion CI actually depends on."""
    assert check_consumer_guide().ok
