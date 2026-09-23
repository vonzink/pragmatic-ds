"""Tests for the licence gate.

The gate is the mechanism that keeps docs/LICENSING.md true over time: an AGPL
dependency must not be able to arrive through a transitive upgrade. These tests
exist because a gate that is configured but never proven to fail is not a gate.
"""

from tools.license_gate import Dependency, Exemption, Policy, evaluate, run_gate

PERMISSIVE = Policy(
    allowed=frozenset({"MIT", "BSD-3-Clause", "Apache-2.0"}),
    denied_patterns=("AGPL", "GPL", "LGPL", "SSPL", "BUSL"),
)

CLASSPATH_EXEMPTION = Exemption(
    package="temurin-jdk",
    license="GPL-2.0-with-classpath-exception",
    elected="GPL-2.0-with-classpath-exception",
    reason="Classpath Exception permits linking without copyleft (LICENSING.md 4.1)",
)

WITH_EXEMPTIONS = Policy(
    allowed=PERMISSIVE.allowed,
    denied_patterns=PERMISSIVE.denied_patterns,
    exemptions=(CLASSPATH_EXEMPTION,),
)


def dep(license_: str | None, name: str = "somelib") -> Dependency:
    return Dependency(name=name, version="1.0.0", license=license_)


def test_allowlisted_license_is_permitted():
    verdict = evaluate(dep("MIT"), PERMISSIVE)

    assert verdict.allowed is True
    assert verdict.elected == "MIT"


def test_agpl_is_denied():
    verdict = evaluate(dep("AGPL-3.0"), PERMISSIVE)

    assert verdict.allowed is False
    assert "AGPL" in verdict.reason


def test_missing_license_is_denied_not_defaulted():
    """No LICENSE file means all rights reserved, not public domain."""
    verdict = evaluate(dep(None), PERMISSIVE)

    assert verdict.allowed is False
    assert "no declared licence" in verdict.reason.lower()


def test_dual_license_elects_the_permissive_arm():
    """Hibernate ships Apache-2.0 OR LGPL-2.1. Pragmatic DS elects Apache-2.0."""
    verdict = evaluate(dep("Apache-2.0 OR LGPL-2.1", name="hibernate-core"), PERMISSIVE)

    assert verdict.allowed is True
    assert verdict.elected == "Apache-2.0"


def test_dual_license_with_no_permissive_arm_is_denied():
    verdict = evaluate(dep("GPL-3.0 OR LGPL-2.1"), PERMISSIVE)

    assert verdict.allowed is False


def test_exemption_permits_the_named_package():
    verdict = evaluate(
        dep("GPL-2.0-with-classpath-exception", name="temurin-jdk"), WITH_EXEMPTIONS
    )

    assert verdict.allowed is True
    assert "Classpath Exception" in verdict.reason


def test_exemption_does_not_leak_to_other_packages():
    """An exemption is package-scoped. It must never become a global loophole."""
    verdict = evaluate(
        dep("GPL-2.0-with-classpath-exception", name="some-other-lib"), WITH_EXEMPTIONS
    )

    assert verdict.allowed is False


def test_parenthesised_spdx_expression_is_parsed():
    """npm license-checker emits '(MIT OR Apache-2.0)'."""
    verdict = evaluate(dep("(MIT OR Apache-2.0)"), PERMISSIVE)

    assert verdict.allowed is True
    assert verdict.elected == "MIT"


def test_alias_maps_classifier_name_to_spdx():
    """pip-licenses emits Python trove classifier names, not SPDX ids."""
    policy = Policy(
        allowed=PERMISSIVE.allowed,
        denied_patterns=PERMISSIVE.denied_patterns,
        aliases={"Apache Software License": "Apache-2.0"},
    )

    verdict = evaluate(dep("Apache Software License"), policy)

    assert verdict.allowed is True
    assert verdict.elected == "Apache-2.0"


def test_alias_does_not_rescue_a_denied_licence():
    policy = Policy(
        allowed=PERMISSIVE.allowed,
        denied_patterns=PERMISSIVE.denied_patterns,
        aliases={"GNU Affero General Public License v3": "AGPL-3.0"},
    )

    verdict = evaluate(dep("GNU Affero General Public License v3"), policy)

    assert verdict.allowed is False
    assert "AGPL" in verdict.reason


def test_gate_fails_and_reports_every_violation():
    result = run_gate(
        [dep("MIT", name="ok-lib"), dep("AGPL-3.0", name="bad-lib"), dep(None, name="mystery-lib")],
        PERMISSIVE,
    )

    assert result.ok is False
    assert [v.dependency.name for v in result.violations] == ["bad-lib", "mystery-lib"]


def test_gate_passes_when_every_dependency_complies():
    result = run_gate([dep("MIT"), dep("BSD-3-Clause")], PERMISSIVE)

    assert result.ok is True
    assert result.violations == []


def test_and_conjunction_requires_every_arm_to_be_allowed():
    """tqdm ships 'MPL-2.0 AND MIT' — BOTH apply. All arms must pass, not any one."""
    policy = Policy(
        allowed=frozenset({"MIT", "MPL-2.0", "Apache-2.0"}),
        denied_patterns=PERMISSIVE.denied_patterns,
    )

    verdict = evaluate(dep("MPL-2.0 AND MIT"), policy)

    assert verdict.allowed is True
    assert verdict.elected == "MPL-2.0 AND MIT"


def test_and_conjunction_with_one_denied_arm_is_denied():
    """AND is conjunctive: a single denied arm poisons the whole expression."""
    verdict = evaluate(dep("MIT AND AGPL-3.0"), PERMISSIVE)

    assert verdict.allowed is False
    assert "AGPL" in verdict.reason


def test_and_conjunction_with_one_unknown_arm_is_denied():
    verdict = evaluate(dep("MIT AND SomeMysteryLicense"), PERMISSIVE)

    assert verdict.allowed is False


def test_or_of_and_groups_is_not_supported_and_denies():
    """Mixed AND/OR without parentheses is ambiguous; the gate refuses to guess."""
    verdict = evaluate(dep("MIT AND X11 OR AGPL-3.0"), PERMISSIVE)

    assert verdict.allowed is False
    assert "ambiguous" in verdict.reason.lower()
