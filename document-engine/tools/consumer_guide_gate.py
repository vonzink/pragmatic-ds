"""Consumer-guide drift gate.

`docs/consumer/income-extraction-contract.md` is the contract a downstream
consumer integrates against. `docs/consumer/consumer-integration-guide.md` is a
one-page SUMMARY of it, for someone wiring up a new consumer.

A summary of a moving contract goes stale silently. Nothing about editing the
contract makes the summary wrong in a way a test would notice, and the failure
surfaces as a consumer quietly following rules the engine stopped honouring — a
defaulted zero in a rental expense, a REJECTED value served as current. Both are
wrong answers that look like right ones.

So the summary pins the contract revision it was written against, and this gate
recomputes it. When they disagree the build fails and a human must re-read what
changed. The pin is a FREEZE, not a cache: re-recording the hash without reading
the diff defeats the entire mechanism.

A consumer that reads only the canonical envelope should enforce this in a typed
parser instead — the envelope is versioned and byte-frozen, so a schema can fail
closed where prose can only advise. The guide is aimed at the read-model
surfaces, where corrections and review state are live and there is no schema.

Same shape as the fixture-provenance gate: fail closed, name the problem, say
what to do about it.
"""

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

CONTRACT = Path("docs/consumer/income-extraction-contract.md")
GUIDE = Path("docs/consumer/consumer-integration-guide.md")

# The pin lives in the guide's YAML front matter. Matched with a regex rather
# than a YAML parse so the gate has no dependency the contract does not, and so a
# malformed front matter reports "no pin" rather than raising.
PIN = re.compile(r"^contract-sha256:\s*([0-9a-f]{64})\s*$", re.MULTILINE)

# Named so the failure message can point at the exact line to change.
PIN_KEY = "contract-sha256"


@dataclass(frozen=True)
class GateResult:
    ok: bool
    problem: str = ""

    def report(self) -> str:
        return "consumer guide is pinned to the current contract" if self.ok else self.problem


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_consumer_guide(
    contract_path: Path = CONTRACT, guide_path: Path = GUIDE
) -> GateResult:
    """Compare the guide's pinned contract digest against the contract itself."""
    contract_path = Path(contract_path)
    guide_path = Path(guide_path)

    # Fail closed on an absent file. A deleted contract or a deleted guide is not
    # "nothing to check" — it is the gate's subject matter disappearing.
    if not contract_path.is_file():
        return GateResult(False, f"missing contract: {contract_path}")
    if not guide_path.is_file():
        return GateResult(False, f"missing consumer guide: {guide_path}")

    match = PIN.search(guide_path.read_text(encoding="utf-8"))
    if match is None:
        return GateResult(
            False,
            f"{guide_path} has no `{PIN_KEY}:` pin in its front matter.\n"
            f"Add one naming the revision of {contract_path} the guide was written against.",
        )

    pinned = match.group(1)
    actual = _digest(contract_path)
    if pinned == actual:
        return GateResult(True)

    return GateResult(
        False,
        f"{contract_path} has changed since {guide_path} was last reviewed.\n"
        f"  pinned: {pinned}\n"
        f"  actual: {actual}\n"
        f"\n"
        f"Re-read what changed in the contract, update {guide_path} to match, and\n"
        f"only then set `{PIN_KEY}: {actual}`.\n"
        f"Do NOT re-record the hash to make the build green — a contract change is\n"
        f"supposed to force a human to look at the summary a consumer follows.",
    )


def main() -> int:
    result = check_consumer_guide()
    print(result.report())
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
