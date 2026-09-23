"""Licence gate.

Enforces the policy in docs/LICENSING.md so a copyleft dependency cannot arrive
through a transitive upgrade. The engine is intended for Apache-2.0 release,
which makes GPL, LGPL, and AGPL hard blockers rather than trade-offs.
"""

import re
import tomllib
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class Dependency:
    name: str
    version: str
    license: str | None


@dataclass(frozen=True)
class Exemption:
    """A narrow, documented deviation from the allowlist. Scoped to one package."""

    package: str
    license: str
    elected: str
    reason: str


@dataclass(frozen=True)
class Policy:
    allowed: frozenset[str]
    denied_patterns: tuple[str, ...]
    exemptions: tuple[Exemption, ...] = ()
    aliases: Mapping[str, str] = field(default_factory=dict)

    @classmethod
    def load(cls, path: str | Path) -> "Policy":
        data = tomllib.loads(Path(path).read_text())
        return cls(
            allowed=frozenset(data.get("allowed", [])),
            denied_patterns=tuple(data.get("denied_patterns", ())),
            exemptions=tuple(Exemption(**entry) for entry in data.get("exemptions", [])),
            aliases=dict(data.get("aliases", {})),
        )


@dataclass(frozen=True)
class Verdict:
    dependency: Dependency
    allowed: bool
    reason: str
    elected: str | None = None


@dataclass(frozen=True)
class GateResult:
    verdicts: tuple[Verdict, ...]
    violations: list[Verdict]
    ok: bool


def _arms(raw: str, aliases: Mapping[str, str]) -> list[str]:
    """Split an SPDX expression into its normalised alternatives.

    A dual-licensed dependency is only safe if we can say which arm we took, so
    the elected arm is carried on the verdict rather than inferred later.

    Aliases exist because the three ecosystems disagree on naming: pip-licenses
    emits Python trove classifier names ("Apache Software License"), npm's
    license-checker emits parenthesised SPDX ("(MIT OR Apache-2.0)"). Aliasing
    happens before both the allowlist and the denylist, so it can never rescue
    a denied licence.
    """
    cleaned = raw.strip()
    if cleaned.startswith("(") and cleaned.endswith(")"):
        cleaned = cleaned[1:-1]

    arms = [a.strip() for a in re.split(r"\s+OR\s+", cleaned, flags=re.IGNORECASE) if a.strip()]
    return [aliases.get(arm, arm) for arm in arms]


def evaluate(dependency: Dependency, policy: Policy) -> Verdict:
    raw = (dependency.license or "").strip()

    # An absent licence is denied, not defaulted: no LICENSE file means all
    # rights reserved, not public domain.
    if not raw:
        return Verdict(dependency, False, "no declared licence")

    # Exemptions match on package AND licence, never licence alone — an
    # exemption must not become a global loophole for that licence.
    for exemption in policy.exemptions:
        if exemption.package == dependency.name and exemption.license == raw:
            return Verdict(dependency, True, exemption.reason, elected=exemption.elected)

    aliased = policy.aliases.get(raw.strip(), raw)

    # AND is conjunctive — every arm applies simultaneously (tqdm: "MPL-2.0 AND MIT"),
    # so ALL arms must be allowed. Mixed AND/OR without parentheses is ambiguous and
    # the gate refuses to guess: deny, and let a human write an alias or exemption.
    has_and = re.search(r"\s+AND\s+", aliased, flags=re.IGNORECASE)
    has_or = re.search(r"\s+OR\s+", aliased, flags=re.IGNORECASE)
    if has_and and has_or:
        return Verdict(dependency, False, f"{raw} mixes AND/OR — ambiguous, not evaluated")
    if has_and:
        and_arms = [
            policy.aliases.get(arm.strip(), arm.strip())
            for arm in re.split(r"\s+AND\s+", aliased, flags=re.IGNORECASE)
            if arm.strip()
        ]
        for arm in and_arms:
            for pattern in policy.denied_patterns:
                if pattern in arm.upper():
                    return Verdict(dependency, False, f"{raw} matches denied pattern {pattern}")
        if all(arm in policy.allowed for arm in and_arms):
            elected = " AND ".join(and_arms)
            return Verdict(dependency, True, f"all of {elected} are allowlisted", elected=elected)
        return Verdict(dependency, False, f"{raw} has an arm outside the allowlist")

    arms = _arms(raw, policy.aliases)

    for arm in arms:
        if arm in policy.allowed:
            return Verdict(dependency, True, f"{arm} is allowlisted", elected=arm)

    for arm in arms:
        for pattern in policy.denied_patterns:
            if pattern in arm.upper():
                return Verdict(dependency, False, f"{raw} matches denied pattern {pattern}")

    return Verdict(dependency, False, f"{raw} is not on the allowlist")


def run_gate(dependencies: Iterable[Dependency], policy: Policy) -> GateResult:
    """Evaluate every dependency, reporting all violations rather than the first."""
    verdicts = tuple(evaluate(dependency, policy) for dependency in dependencies)
    violations = [verdict for verdict in verdicts if not verdict.allowed]
    return GateResult(verdicts=verdicts, violations=violations, ok=not violations)
